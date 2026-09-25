package com.galaxy.wear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手表 App ↔ galaxy-tailnet 子进程之间的约定。
 *
 * 子进程那一半在 tailnet/(Go),有自己的单测与真 headscale 端到端;这里钉 App 这一半:
 * 解析它报的事件、拼它的参数、决定哪条候选经它走。
 */
class TailnetProtocolTest {

    // ── 事件 ───────────────────────────────────────────────────────────────

    @Test
    fun `ready carries the loopback port and the tailnet address`() {
        val ev = TailnetProtocol.parseEvent(
            """{"event":"ready","listen":"127.0.0.1:41234","tailnet_ip":"100.64.0.7","target":"100.64.0.1:9000"}"""
        )
        assertEquals(TailnetProtocol.Event.Ready("127.0.0.1:41234", "100.64.0.7", "100.64.0.1:9000"), ev)
    }

    @Test
    fun `other events and noise`() {
        assertEquals(TailnetProtocol.Event.Starting, TailnetProtocol.parseEvent("""{"event":"starting"}"""))
        assertEquals(TailnetProtocol.Event.NeedsLogin, TailnetProtocol.parseEvent("""{"event":"needs_login"}"""))
        assertEquals(
            TailnetProtocol.Event.Error("加入 tailnet 失败"),
            TailnetProtocol.parseEvent("""{"event":"error","message":"加入 tailnet 失败"}"""),
        )
        // 认不出来的不当成错误 —— 新版本可能多报事件
        assertNull(TailnetProtocol.parseEvent("""{"event":"something_new"}"""))
        assertNull(TailnetProtocol.parseEvent("not json"))
        // ready 却没有转发口:不能当成就绪,否则会去拨一个不存在的端口
        assertNull(TailnetProtocol.parseEvent("""{"event":"ready","tailnet_ip":"100.64.0.7"}"""))
    }

    // ── 参数 ───────────────────────────────────────────────────────────────

    @Test
    fun `args pin loopback, await the network, and never carry the key`() {
        val args = TailnetProtocol.buildArgs("/lib/x.so", "/files/tailnet", "https://hs", "galaxy-watch-ab12", "100.64.0.1:9000", 41234)
        assertEquals("/lib/x.so", args[0])
        assertTrue(args.windowed(2).contains(listOf("--listen", "127.0.0.1:41234")))
        assertTrue(args.windowed(2).contains(listOf("--control-url", "https://hs")))
        assertTrue(args.windowed(2).contains(listOf("--target", "100.64.0.1:9000")))
        assertTrue("Android 上子进程拿不到网卡与 DNS,必须先等 App 交过去", "--await-network" in args)
        assertFalse("钥匙不许出现在进程参数里", args.any { it.contains("authkey", ignoreCase = true) })
    }

    @Test
    fun `hostname is readable, stable, and does not leak the whole device id`() {
        assertEquals("galaxy-watch-3f9a1c", TailnetProtocol.hostnameFor("3F9A1C22-7b0e-4d0e-9b55-0a1b2c3d4e5f"))
        assertEquals("galaxy-watch", TailnetProtocol.hostnameFor("---"))
    }

    // ── 哪条候选经 tailnet 走 ────────────────────────────────────────────────

    @Test
    fun `tailnet candidates are recognised by address`() {
        assertEquals("100.64.0.1:9000", TailnetProtocol.targetOf("ws://100.64.0.1:9000/ws/device/w"))
        assertEquals("100.127.255.254:9000", TailnetProtocol.targetOf("ws://100.127.255.254:9000/ws"))
        assertEquals("[fd7a:115c:a1e0::1]:9000", TailnetProtocol.targetOf("ws://[fd7a:115c:a1e0::1]:9000/ws"))
        assertEquals("100.64.0.1:80", TailnetProtocol.targetOf("ws://100.64.0.1/ws"))
        // 局域网、公网、CGNAT 段外的 100.x:不是 tailnet
        assertNull(TailnetProtocol.targetOf("ws://192.168.1.5:9000/ws"))
        assertNull(TailnetProtocol.targetOf("ws://100.63.0.1:9000/ws"))
        assertNull(TailnetProtocol.targetOf("ws://100.128.0.1:9000/ws"))
        assertNull(TailnetProtocol.targetOf("wss://box.ts.net/ws"))
        assertNull(TailnetProtocol.targetOf("garbage"))
    }

    @Test
    fun `a tailnet candidate is dialled through the loopback port, path kept`() {
        assertEquals(
            "ws://127.0.0.1:41234/ws/device/watch-1?format=msgpack",
            TailnetProtocol.dialUrlFor(
                "ws://100.64.0.1:9000/ws/device/watch-1?format=msgpack",
                readyListen = "127.0.0.1:41234",
                readyTarget = "100.64.0.1:9000",
            ),
        )
    }

    @Test
    fun `a tailnet candidate is skipped while the daemon is not ready`() {
        // 手表没有 VPN:直接拨 100.x 必然不通,只会白等一个超时。
        assertNull(TailnetProtocol.dialUrlFor("ws://100.64.0.1:9000/ws", null, null))
    }

    @Test
    fun `a tailnet candidate for a different gateway is not sent through this forwarder`() {
        // 转发口只通向启动时指定的那一个目标;把另一台网关的地址改写过来会连错机器。
        assertNull(TailnetProtocol.dialUrlFor("ws://100.64.0.9:9000/ws", "127.0.0.1:41234", "100.64.0.1:9000"))
    }

    @Test
    fun `non-tailnet candidates are dialled as they are`() {
        val lan = "ws://192.168.1.5:9000/ws/device/w"
        assertEquals(lan, TailnetProtocol.dialUrlFor(lan, null, null))
        assertEquals(lan, TailnetProtocol.dialUrlFor(lan, "127.0.0.1:41234", "100.64.0.1:9000"))
    }

    // ── 网络命令 ─────────────────────────────────────────────────────────────

    @Test
    fun `network command has the shape the daemon reads`() {
        val cmd = TailnetProtocol.networkCommand(
            listOf("192.168.1.1"),
            listOf(TailnetProtocol.NetIface("wlan0", 3, 1500, true, listOf("192.168.1.23/24"))),
        )
        assertEquals(
            """{"cmd":"network","dns":["192.168.1.1"],"interfaces":[{"name":"wlan0","index":3,"mtu":1500,"up":true,"addrs":["192.168.1.23/24"]}]}""",
            cmd,
        )
        assertFalse("一行一条命令,不许有换行", cmd.contains('\n'))
    }

    @Test
    fun `restart backoff grows and is capped`() {
        assertEquals(2_000L, TailnetProtocol.restartDelayMs(0))
        assertEquals(4_000L, TailnetProtocol.restartDelayMs(1))
        assertEquals(60_000L, TailnetProtocol.restartDelayMs(50))
    }
}
