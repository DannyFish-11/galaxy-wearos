package com.galaxy.wear.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

/**
 * 手表 App 与 galaxy-tailnet 子进程之间的约定 —— **不碰 Android 的那部分**。
 *
 * galaxy-tailnet(本仓 `tailnet/`,Go)以用户态加入 tailnet,在手表回环口上开一个
 * 端口,把进来的连接原样转发到网关在 tailnet 里的地址。App 这边照旧连
 * `ws://127.0.0.1:<端口>/ws/device/<id>`,协议、鉴权、重连一概不变。
 *
 * 这个文件只放纯逻辑:拼参数、解析事件、改写地址、生成网络命令。
 * 与 [com.galaxy.wear.data.AipPureLogic] 同一条规矩:**不许 import android.\***,
 * 否则下面这些就只能在真机上验证了。进程管理在 [TailnetDaemon]。
 */
object TailnetProtocol {

    /** 打进 APK 的可执行文件名。必须以 lib 开头、.so 结尾,安装器才会把它解压进 nativeLibraryDir。 */
    const val BINARY_NAME = "libgalaxytailnet.so"

    /** 退出码 3 = 没有节点身份也没有密钥,需要重新配对。与 tailnet/main.go 一致。 */
    const val EXIT_NEEDS_LOGIN = 3

    private val json = Json { ignoreUnknownKeys = true }

    /** 子进程报上来的一件事。 */
    sealed class Event {
        object Starting : Event()
        object NeedsLogin : Event()
        data class Ready(val listen: String, val tailnetIp: String, val target: String) : Event()
        data class Error(val message: String) : Event()
    }

    /** 解析 stdout 上的一行。认不出来的行返回 null(不当成错误 —— 新版本可能多报事件)。 */
    fun parseEvent(line: String): Event? {
        val o = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (str("event")) {
            "starting" -> Event.Starting
            "needs_login" -> Event.NeedsLogin
            "ready" -> {
                val listen = str("listen")
                if (listen.isBlank()) null else Event.Ready(listen, str("tailnet_ip"), str("target"))
            }
            "error" -> Event.Error(str("message").ifBlank { "unknown" })
            else -> null
        }
    }

    /**
     * 启动参数。密钥**不在这里** —— 走环境变量 TS_AUTHKEY,免得出现在进程参数里。
     *
     * @param listenPort 回环口上的固定端口。固定是为了让转发地址在子进程重启后不变:
     *   AIPClient 自己的重连循环会一直拿同一个地址去拨,端口一变它就拨了个空。
     */
    fun buildArgs(
        binary: String,
        stateDir: String,
        controlUrl: String,
        hostname: String,
        target: String,
        listenPort: Int,
    ): List<String> = listOf(
        binary,
        "--state-dir", stateDir,
        "--control-url", controlUrl,
        "--hostname", hostname,
        "--target", target,
        "--listen", "127.0.0.1:$listenPort",
        // Android 上本程序自己拿不到网卡与 DNS,必须先等 App 交过去。
        "--await-network",
    )

    /** 节点在 tailnet 里的名字:可读、同一块表始终相同、不泄露完整设备 id。 */
    fun hostnameFor(deviceId: String): String {
        val tail = deviceId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(6)
        return if (tail.isEmpty()) "galaxy-watch" else "galaxy-watch-$tail"
    }

    /** 这个候选地址是不是指向 tailnet 里的主机。是的话返回转发目标 `host:port`,否则 null。 */
    fun targetOf(candidateUrl: String): String? {
        val uri = runCatching { URI(candidateUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        if (!isTailnetHost(host)) return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "wss" || uri.scheme == "https" -> 443
            else -> 80
        }
        return if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    /**
     * 把指向 tailnet 的候选地址改写成经本机转发口的地址。路径与查询原样保留。
     *
     * 协议也原样保留:网关默认明文 ws://(tailnet 由 WireGuard 加密),转发口是
     * 纯 TCP 转发,不改变上层说的是什么。
     */
    fun viaLoopback(candidateUrl: String, listen: String): String {
        val uri = URI(candidateUrl.trim())
        val rest = buildString {
            append(uri.rawPath ?: "")
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
        }
        return "${uri.scheme}://$listen$rest"
    }

    /**
     * 一个候选地址这一次实际该拨哪里。
     *
     * * 不指向 tailnet 的(局域网等):原样拨;
     * * 指向 tailnet 的:只有转发进程已就绪、且转发目标正是它时,改拨本机转发口;
     *   否则返回 null —— 手表没有 VPN,直接拨 100.x 必然不通,白等一个超时。
     */
    fun dialUrlFor(candidateUrl: String, readyListen: String?, readyTarget: String?): String? {
        val t = targetOf(candidateUrl) ?: return candidateUrl
        if (readyListen.isNullOrBlank() || readyTarget != t) return null
        return viaLoopback(candidateUrl, readyListen)
    }

    /** 100.64.0.0/10(CGNAT,tailnet 的 IPv4 段)或 fd7a:115c:a1e0::/48(Tailscale 的 IPv6 段)。 */
    fun isTailnetHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (parts.any { p -> p.toIntOrNull()?.let { it !in 0..255 } ?: true }) return false
        return a == 100 && b in 64..127
    }

    /** 一块网卡,按子进程要的形状。 */
    data class NetIface(
        val name: String,
        val index: Int,
        val mtu: Int,
        val up: Boolean,
        /** "192.168.1.23/24" 这种 前缀长度 形式 */
        val addrs: List<String>,
    )

    /** 发给子进程的 network 命令(一行 JSON)。 */
    fun networkCommand(dns: List<String>, ifaces: List<NetIface>): String {
        val o: JsonObject = buildJsonObject {
            put("cmd", "network")
            put("dns", buildJsonArray { dns.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put(
                "interfaces",
                buildJsonArray {
                    ifaces.forEach { i ->
                        add(
                            buildJsonObject {
                                put("name", i.name)
                                put("index", i.index)
                                put("mtu", i.mtu)
                                put("up", i.up)
                                put("addrs", buildJsonArray { i.addrs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                            }
                        )
                    }
                }
            )
        }
        return o.toString()
    }

    /** 子进程意外退出后多久再拉起:2s、4s、8s……封顶 60s。 */
    fun restartDelayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 5)
        return (2_000L shl shift).coerceAtMost(60_000L)
    }

    /** 回环转发口:首次随机选一个,之后固定(见 [buildArgs] 的说明)。避开常用端口段。 */
    fun pickListenPort(random: kotlin.random.Random = kotlin.random.Random.Default): Int =
        random.nextInt(20_000, 60_000)
}
