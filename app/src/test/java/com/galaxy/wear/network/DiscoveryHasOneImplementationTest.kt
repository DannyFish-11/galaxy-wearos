package com.galaxy.wear.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mDNS 发现在这块表上只能有一份实现。
 *
 * 手表侧原先自带 `MdnsDiscovery`，安卓仓 `:shared-transport` 里有 `GatewayDiscovery` ——
 * 同一个服务类型 `_galaxy._tcp`、同一个「Android 各版本对服务类型尾点不一致」的坑、
 * 同一套 listener 必须解注册否则泄漏系统线程的清理。两份实现意味着补丁只会打在一边：
 * 尾点那个坑当初就是先在一边修好、另一边过了一阵才补上的。
 *
 * 手表仓通过 `settings.gradle.kts` 把兄弟仓的 `:shared-transport` 当子项目引进来，
 * 所以共用同一份是做得到的 —— 前提是那个类真的住在 `:shared-transport` 里
 * （它一度住在 `:app`，手表看不见，见安卓仓 `NoClassLivesInTwoModulesTest`）。
 *
 * 为什么是源码级检查：两边都改成调 `GatewayDiscovery` 之后，「比一比两个发现口相不相等」
 * 是恒真的。会真实发生的倒退是「有人在手表侧重新写一个 NsdManager 监听」——
 * 只有扫源码拦得住。
 */
class DiscoveryHasOneImplementationTest {

    private fun mainSourceRoot(): File {
        // 工作目录在不同调用方式下不一样（模块目录 / 仓库根），两种都试。
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "找不到 src/main/java。试过：${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private fun kotlinSources(): List<File> =
        mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the watch does not implement its own mDNS listener`() {
        val offenders = kotlinSources().filter { f ->
            val t = f.readText()
            // NsdManager 的监听接口是「自己写发现」的唯一入口；只提到类名不算。
            t.contains("NsdManager.DiscoveryListener") || t.contains("discoverServices(")
        }
        assertTrue(
            "手表侧又出现了自己的 mDNS 发现实现：${offenders.map { it.name }}。" +
                "发现逻辑应当共用 com.ufo.galaxy.network.GatewayDiscovery（:shared-transport），" +
                "否则尾点、listener 泄漏这类坑要在两个地方各修一次。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the old MdnsDiscovery class is gone`() {
        val f = File(mainSourceRoot(), "com/galaxy/wear/network/MdnsDiscovery.kt")
        assertFalse("MdnsDiscovery.kt 又回来了 —— 那正是被收敛掉的那一份", f.isFile)
    }

    @Test
    fun `the application actually calls the shared discovery`() {
        // 光是删掉旧类不够：删了却没接上新的，等于把局域网自动发现整条去掉，
        // 而表现只是「以前能自动连、现在要手填」，没有任何报错。
        val src = File(mainSourceRoot(), "com/galaxy/wear/GalaxyWearApplication.kt").readText()
        assertTrue(
            "GalaxyWearApplication 没有导入共享的 GatewayDiscovery",
            src.contains("import com.ufo.galaxy.network.GatewayDiscovery"),
        )
        assertTrue(
            "导入了却没真的调 discover() —— 局域网发现这条路等于被删掉了",
            src.contains("gatewayDiscovery.discover("),
        )
    }

    @Test
    fun `the watch keeps its own two second discovery window`() {
        // 共享类默认 2.5 秒。手表上多等半秒肉眼可见，收敛不该顺手改掉本端的取舍。
        val src = File(mainSourceRoot(), "com/galaxy/wear/GalaxyWearApplication.kt").readText()
        assertTrue(
            "手表侧的发现窗口不再是 2 秒 —— 收敛实现可以，顺手改掉本端行为不行",
            src.contains("MDNS_TIMEOUT_MS = 2000L"),
        )
        assertTrue(
            "调用时没有显式传 timeoutMs，会落到共享类的 2.5 秒默认值上",
            src.contains("timeoutMs = MDNS_TIMEOUT_MS"),
        )
    }

    @Test
    fun `the wifi precheck survived the move`() {
        // isWifiAvailable 原本长在 MdnsDiscovery 里，但它从来不是发现逻辑。
        // 删掉旧类时如果把它一起删了，每次都会白花一个发现窗口去等一个没有 Wi-Fi 的网络。
        val src = File(mainSourceRoot(), "com/galaxy/wear/network/WifiAvailability.kt").readText()
        assertTrue("Wi-Fi 预检丢了", src.contains("fun isWifiAvailable("))
        assertTrue(
            "预检不再要求 NET_CAPABILITY_VALIDATED —— 连上但没通的 Wi-Fi 会被当成可用",
            src.contains("NET_CAPABILITY_VALIDATED"),
        )
        val app = File(mainSourceRoot(), "com/galaxy/wear/GalaxyWearApplication.kt").readText()
        assertTrue("发现前不再做 Wi-Fi 预检", app.contains("isWifiAvailable(this)"))
    }
}
