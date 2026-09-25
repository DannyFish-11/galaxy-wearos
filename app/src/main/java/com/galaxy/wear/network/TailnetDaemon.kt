package com.galaxy.wear.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.NetworkInterface

/**
 * 手表自己加入 tailnet 的那个子进程(galaxy-tailnet)的管家。
 *
 * 为什么要它
 * ==========
 * 手表装不了 Tailscale:Wear OS 把 VPN 授权做成了桩。galaxy-tailnet 以用户态加入
 * tailnet(不需要 VPN 授权),在回环口上开一个固定端口,把连接转发到网关的 100.x
 * 地址。出门在外、只带手表时,这是手表**直连**电脑的那条路。
 *
 * 它负责
 * ======
 * * 拉起子进程(可执行文件随 APK 装进 nativeLibraryDir —— 只有那里允许执行);
 * * 把网卡与 DNS 交给子进程,网络一变就再交一次(Android 11+ 子进程自己拿不到);
 * * 读子进程报上来的状态,变成 [state];
 * * 子进程意外退出时按退避重拉;缺身份(需要重新配对)时不重拉,如实报出来。
 *
 * 纯逻辑(参数、事件、地址改写)在 [TailnetProtocol],那一半有单测。
 */
class TailnetDaemon(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** 子进程此刻的状态。 */
    sealed class State {
        /** 没要求它跑(没配过 tailnet,或已停止)。 */
        object Off : State()

        /** 这台设备跑不了(APK 里没有这个 ABI 的可执行文件,比如在 x86 模拟器上)。 */
        data class Unavailable(val reason: String) : State()
        object Starting : State()
        data class Ready(val listen: String, val tailnetIp: String, val target: String) : State()

        /** 没有节点身份、也没有可用的钥匙 —— 需要重新配对拿一把。不会自动重试。 */
        object NeedsLogin : State()
        data class Failed(val message: String) : State()
    }

    /** 一次启动所需的全部输入。 */
    data class Spec(
        val controlUrl: String,
        val target: String,
        val hostname: String,
        val listenPort: Int,
        /** 一次性钥匙;已经加入过就为 null(凭已存的节点身份重连)。 */
        val authKey: String?,
    )

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var spec: Spec? = null
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var supervisor: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 钥匙用掉(第一次到 ready)后回调 —— 调用方据此把钥匙从存储里删掉。 */
    var onJoined: (() -> Unit)? = null

    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, TailnetProtocol.BINARY_NAME)

    private val stateDir: File
        get() = File(context.filesDir, "tailnet")

    /**
     * 确保子进程按 [newSpec] 在跑。已经按同样的参数在跑就什么都不做
     * (钥匙不参与比较:加入之后钥匙就没用了)。
     */
    fun ensureRunning(newSpec: Spec) {
        synchronized(lock) {
            val cur = spec
            val same = cur != null && cur.copy(authKey = null) == newSpec.copy(authKey = null)
            if (same && supervisor?.isActive == true) return
            stopLocked()
            spec = newSpec
            if (!binary.isFile) {
                _state.value = State.Unavailable("本机 ABI 没有打包 ${TailnetProtocol.BINARY_NAME}(只打了 arm64-v8a)")
                return
            }
            supervisor = scope.launch(Dispatchers.IO) { supervise() }
        }
        registerNetworkCallback()
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            spec = null
            _state.value = State.Off
        }
        unregisterNetworkCallback()
    }

    private fun stopLocked() {
        supervisor?.cancel()
        supervisor = null
        runCatching { stdin?.close() } // 关 stdin = 告诉子进程"App 不要你了",它会自己退出
        stdin = null
        process?.let { p ->
            runCatching { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroy() }
        }
        process = null
    }

    private suspend fun supervise() {
        var attempt = 0
        while (true) {
            val s = synchronized(lock) { spec } ?: return
            val code = runOnce(s)
            if (code == TailnetProtocol.EXIT_NEEDS_LOGIN) {
                if (synchronized(lock) { spec } === s) _state.value = State.NeedsLogin
                return
            }
            // 被 stop() 叫停、或已换成新参数的,不算失败,也不重拉(新参数有自己的 supervisor)。
            if (synchronized(lock) { spec } !== s) return
            val wait = TailnetProtocol.restartDelayMs(attempt++)
            Log.w(TAG, "galaxy-tailnet 退出(code=$code),${wait}ms 后重拉")
            delay(wait)
        }
    }

    /** 跑一次子进程直到它退出,返回退出码。 */
    private fun runOnce(s: Spec): Int {
        stateDir.mkdirs()
        val args = TailnetProtocol.buildArgs(
            binary = binary.absolutePath,
            stateDir = stateDir.absolutePath,
            controlUrl = s.controlUrl,
            hostname = s.hostname,
            target = s.target,
            listenPort = s.listenPort,
        )
        val pb = ProcessBuilder(args)
        pb.environment().apply {
            // Go 运行时与 tsnet 会用到;App 进程的环境里未必有。
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            if (!s.authKey.isNullOrBlank()) put("TS_AUTHKEY", s.authKey) else remove("TS_AUTHKEY")
        }
        _state.value = State.Starting
        val p = try {
            pb.start()
        } catch (e: Exception) {
            _state.value = State.Failed("启动失败:${e.message}")
            return -1
        }
        val writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
        synchronized(lock) {
            process = p
            stdin = writer
        }
        // stderr 必须读掉,否则管道写满后子进程会卡住。
        Thread({
            runCatching { p.errorStream.bufferedReader().forEachLine { Log.d(TAG, it) } }
        }, "tailnet-stderr").apply { isDaemon = true }.start()

        sendNetwork()

        var lastError: String? = null
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                when (val ev = TailnetProtocol.parseEvent(line)) {
                    is TailnetProtocol.Event.Ready -> {
                        _state.value = State.Ready(ev.listen, ev.tailnetIp, ev.target)
                        Log.i(TAG, "已加入 tailnet:${ev.tailnetIp},转发口 ${ev.listen} → ${ev.target}")
                        if (!s.authKey.isNullOrBlank()) onJoined?.invoke()
                    }
                    is TailnetProtocol.Event.Error -> lastError = ev.message
                    TailnetProtocol.Event.NeedsLogin -> Unit
                    TailnetProtocol.Event.Starting -> _state.value = State.Starting
                    null -> Unit
                }
            }
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        // 只有"这一份参数仍是当前的"才写状态:换参数重启时,旧进程的退出不该盖掉新进程的状态。
        if (code != TailnetProtocol.EXIT_NEEDS_LOGIN && synchronized(lock) { spec } === s) {
            _state.value = State.Failed(lastError ?: "进程退出(code=$code)")
        }
        return code
    }

    // ── 网络信息 ────────────────────────────────────────────────────────

    /** 读当前所有网络的网卡与 DNS,交给子进程。 */
    private fun sendNetwork() {
        val cmd = runCatching { TailnetProtocol.networkCommand(currentDns(), currentInterfaces()) }
            .getOrElse {
                Log.w(TAG, "读取网络信息失败:${it.message}")
                return
            }
        synchronized(lock) {
            val w = stdin ?: return
            runCatching {
                w.write(cmd)
                w.newLine()
                w.flush()
            }
        }
    }

    private fun connectivity(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun linkProperties(): List<LinkProperties> {
        val cm = connectivity() ?: return emptyList()
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }
    }

    private fun currentDns(): List<String> {
        val cm = connectivity()
        // 默认网络的 DNS 排前面
        val active = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
        return (listOfNotNull(active) + linkProperties())
            .flatMap { lp -> lp.dnsServers.mapNotNull { it.hostAddress } }
            .map { it.substringBefore('%') }
            .distinct()
    }

    private fun currentInterfaces(): List<TailnetProtocol.NetIface> =
        linkProperties().mapNotNull { lp ->
            val name = lp.interfaceName ?: return@mapNotNull null
            val nif = runCatching { NetworkInterface.getByName(name) }.getOrNull()
            TailnetProtocol.NetIface(
                name = name,
                index = nif?.index ?: (name.hashCode() and 0x7fff),
                mtu = lp.mtu.takeIf { it > 0 } ?: 1500,
                up = true,
                addrs = lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" },
            )
        }.distinctBy { it.name }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivity() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = sendNetwork()
            override fun onLost(network: Network) = sendNetwork()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = sendNetwork()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.w(TAG, "注册网络回调失败:${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { connectivity()?.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    companion object {
        private const val TAG = "TailnetDaemon"
    }
}
