package com.galaxy.wear.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AIPClient 里那些**纯逻辑**的第一批测试。
 *
 * 为什么之前一条都没有
 * ====================
 * `AIPClient.kt` 1200 行,是手表和中心之间**唯一**的协议链路 —— 而它的测试覆盖是 0。
 * 原因不是没人想测,是结构上测不了:
 *
 *   · 构造函数要 `android.content.Context`;
 *   · 本仓单测工具链只有 `junit:junit` 一个依赖 —— 没有 Robolectric,造不出 Context;
 *   · 于是连 `buildWsUrl` 这种纯字符串拼接也跟着沉在水下(它还是 private)。
 *
 * 所以这一轮先做两件事:把两个**逐字未改**的纯函数挪进 companion(见 AIPClient.kt
 * 里那段说明),然后把它们、以及本来就在 companion 里的 msgpack 编解码,一条条钉住。
 *
 * 这里**不**假装测了什么
 * ======================
 * 连接、鉴权、心跳、重连退避、`handleMessage` 的分发 —— 这些都绑着 Ktor 会话、
 * PowerManager、WakeLock,这一层测不到,本文件一个字都不碰它们。
 * 它们要么等 Robolectric,要么等插桩测试(本仓 androidTest 目前是 0 个)。
 * 写清楚,免得这份文件被当成"AIPClient 已经有覆盖了"。
 */
class AIPClientPureLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── buildWsUrl:发现回来的地址要被补成设备端点 ────────────────────────

    @Test
    fun `裸地址补出设备端点`() {
        assertEquals(
            "ws://192.168.1.100:9000/ws/device/watch-1",
            AipPureLogic.buildWsUrl("ws://192.168.1.100:9000", "watch-1"),
        )
    }

    @Test
    fun `末尾斜杠不会拼出双斜杠`() {
        // 这条是真会发生的:mDNS 拼出来的 wsUrl 带不带尾斜杠取决于服务端怎么登记。
        assertEquals(
            "ws://10.0.2.2:9000/ws/device/watch-1",
            AipPureLogic.buildWsUrl("ws://10.0.2.2:9000/", "watch-1"),
        )
    }

    @Test
    fun `已经带路径的地址原样不动`() {
        // 用户在设置页手填了完整端点时,不能再往后面接一截。
        val full = "wss://gw.example.com/ws/device/watch-9"
        assertEquals(full, AipPureLogic.buildWsUrl(full, "watch-1"))
    }

    @Test
    fun `判定靠的是包含 ws 这个子串,不是结尾`() {
        // 钉住当前的**实际**语义(url.contains("/ws")),而不是我以为的语义。
        // 它偏宽:任何含 "/ws" 的地址都被当成"已经有路径"。写在这儿,是因为
        // 将来要收紧判定时,这条会先红 —— 那正是提醒"有个行为要变"的地方。
        val weird = "ws://host:9000/wsapi"
        assertEquals(weird, AipPureLogic.buildWsUrl(weird, "watch-1"))
    }

    @Test
    fun `deviceId 直接进路径`() {
        // deviceId 是本机生成的,不做转义。含斜杠就会拼出多一层路径 ——
        // 钉住现状,好让"要不要转义"成为一个被看见的选择,而不是一个意外。
        assertEquals(
            "ws://h:9000/ws/device/a/b",
            AipPureLogic.buildWsUrl("ws://h:9000", "a/b"),
        )
    }

    // ── parseDeviceList:尽力而为,缺字段回落,整体坏了回空表 ─────────────

    private fun parse(raw: String): List<DeviceInfo> =
        AipPureLogic.parseDeviceList(json.parseToJsonElement(raw))

    @Test
    fun `字段齐全时逐项映射`() {
        val list = parse(
            """
            [{"device_id":"pc-1","display_name":"书房台式机","device_type":"desktop",
              "status":"online","capabilities":["gui","shell"],"last_seen":1700000000000}]
            """.trimIndent(),
        )
        assertEquals(1, list.size)
        val d = list[0]
        assertEquals("pc-1", d.deviceId)
        assertEquals("书房台式机", d.displayName)
        assertEquals("desktop", d.deviceType)
        assertEquals("online", d.status)
        assertEquals(listOf("gui", "shell"), d.capabilities)
        assertEquals(1700000000000L, d.lastSeen)
    }

    @Test
    fun `缺字段回落到占位值,不整条丢掉`() {
        // 设备列表是**展示用**的。少一个字段就把整屏弄空,比显示 "unknown" 更糟。
        val list = parse("""[{"device_id":"pc-1"}]""")
        assertEquals(1, list.size)
        assertEquals("pc-1", list[0].deviceId)
        assertEquals("Unknown Device", list[0].displayName)
        assertEquals("unknown", list[0].deviceType)
        assertEquals("unknown", list[0].status)
        assertTrue(list[0].capabilities.isEmpty())
    }

    @Test
    fun `连 device_id 都没有也回落,而不是抛`() {
        val list = parse("""[{}]""")
        assertEquals(1, list.size)
        assertEquals("unknown", list[0].deviceId)
    }

    @Test
    fun `没有 last_seen 时回落成一个像样的时间戳`() {
        // 回落用的是 System.currentTimeMillis(),所以只能断言"是个近代的毫秒数",
        // 不能钉死具体值 —— 钉死就是造一条会随时间变红的测试。
        val list = parse("""[{"device_id":"pc-1"}]""")
        assertTrue(
            "last_seen 回落值不像毫秒时间戳:${list[0].lastSeen}",
            list[0].lastSeen > 1_600_000_000_000L,
        )
    }

    @Test
    fun `空数组得到空表`() {
        assertTrue(parse("[]").isEmpty())
    }

    @Test
    fun `传进来的不是数组时回空表,而不是崩`() {
        // 服务端返回结构变了(比如包了一层 {"devices":[...]})时,界面该是空的,不是崩的。
        assertTrue(parse("""{"devices":[]}""").isEmpty())
    }

    @Test
    fun `数组里混进非对象元素时整体回空表`() {
        // 钉住的是**当前**行为:map 里任一元素抛,整个 try 就回 emptyList ——
        // 不是"跳过坏的那条"。这两种语义差别很大,写明白哪一种在生效。
        assertTrue(parse("""[{"device_id":"pc-1"}, 42]""").isEmpty())
    }

    // ── msgpack 编解码:双格式的另一半 ───────────────────────────────────

    @Test
    fun `msgpack 打包再解包能回到同一份 JSON`() {
        val original = """{"type":"ping","seq":7,"ok":true,"tags":["a","b"],"nested":{"k":1.5}}"""
        val packed = AipPureLogic.packMsgpack(original)
        assertNotNull("打包失败", packed)
        val unpacked = AipPureLogic.unpackMsgpack(packed!!)
        assertNotNull("解包失败", unpacked)
        // 逐字段比,不比字符串 —— 键序和数字格式化都可能变,那不算差异。
        assertEquals(
            json.parseToJsonElement(original),
            json.parseToJsonElement(unpacked!!),
        )
    }

    @Test
    fun `null 与空容器也能原样走一圈`() {
        val original = """{"a":null,"b":[],"c":{}}"""
        val round = AipPureLogic.unpackMsgpack(AipPureLogic.packMsgpack(original)!!)
        assertEquals(json.parseToJsonElement(original), json.parseToJsonElement(round!!))
    }

    @Test
    fun `解包垃圾字节回 null,让调用方掉回 JSON`() {
        // 这条**必须**能跑:它走的是 catch 分支,而那里有 Log.w ——
        // 正是它要求 build.gradle.kts 里打开 unitTests.isReturnDefaultValues。
        assertNull(AipPureLogic.unpackMsgpack(byteArrayOf(0xC1.toByte(), 0x00, 0x42)))
    }

    @Test
    fun `解包空字节数组回 null`() {
        assertNull(AipPureLogic.unpackMsgpack(ByteArray(0)))
    }

    @Test
    fun `打包非法 JSON 回 null`() {
        assertNull(AipPureLogic.packMsgpack("{ 这不是 JSON"))
    }

    // ── 连接态 ──────────────────────────────────────────────────────────

    @Test
    fun `终态只有断开和出错两种`() {
        // 重连调度靠这个位判断"还要不要再试"。把 CONNECTING 算成终态会让它停在半路,
        // 把 DISCONNECTED 算成非终态会让它永远重试 —— 两边都错得很安静。
        assertEquals(
            setOf(AIPConnectionState.DISCONNECTED, AIPConnectionState.ERROR),
            AIPConnectionState.values().filter { it.isTerminal }.toSet(),
        )
    }

    // ── 防回归:别把这两个纯函数再沉回实例里 ────────────────────────────

    @Test
    fun `纯逻辑文件里不许出现 Android`() {
        // 这十几条测试能跑,唯一的原因就是 AipPureLogic.kt 不需要 Android 环境。
        // 谁往里面 import 一个 android.* ,它就又只能在真机/Robolectric 下跑 ——
        // 而上面那些测试会**静悄悄**地失效:文件还在,只是没人能调用。
        val src = File(locateMainSourceRoot(), "com/galaxy/wear/data/AipPureLogic.kt").readText()
        val code = src.replace(Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        val offenders = Regex("""^import\s+(android\.|androidx\.|io\.ktor\.).*$""", RegexOption.MULTILINE)
            .findAll(code).map { it.value.trim() }.toList()
        assertTrue(
            "AipPureLogic.kt 里出现了 Android/Ktor 依赖:$offenders —— 它就不再是纯的了",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `AIPClient 仍然委派到这里,而不是自己又抄了一份`() {
        // 委派断了的话,线上跑的是另一份实现,而这些测试还在绿 —— 最坏的那种绿。
        val src = File(locateMainSourceRoot(), "com/galaxy/wear/data/AIPClient.kt").readText()
        for (fn in listOf("buildWsUrl", "parseDeviceList", "unpackMsgpack", "packMsgpack")) {
            assertTrue(
                "AIPClient 不再把 $fn 委派给 AipPureLogic 了",
                src.contains("AipPureLogic.$fn("),
            )
        }
    }

    /** 从 Gradle 的工作目录往上找 main 源根。模块根与仓库根两种都试。 */
    private fun locateMainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 main 源根,试过:${candidates.map { it.absolutePath }}")
    }
}
