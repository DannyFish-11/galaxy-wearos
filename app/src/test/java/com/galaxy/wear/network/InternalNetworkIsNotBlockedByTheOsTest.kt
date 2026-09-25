package com.galaxy.wear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手表连内网网关，不能被系统层的网络配置悄悄拦掉。
 *
 * 为什么要这道门
 * ==============
 * 设备间只走内网，网关默认说明文 ws://。而手表原先：
 *   * main 那份 network_security_config "禁明文 + 写死 4 个 IP 放行"；
 *   * release 版再用 resValue 整份换成"全禁明文"。
 * 结果 release 版手表连**任何**内网地址都被系统拦下 —— 连接报错只有一句
 * "CLEARTEXT communication not permitted"，出现在用户根本看不到的 logcat 里。
 *
 * 那份 XML 不认网段，所以"明文只许对内网"改由 shared-protocol 的
 * CleartextPolicy 判。这里钉住系统层别再把路堵回去，也钉住代码层那道判定真的接上了。
 */
class InternalNetworkIsNotBlockedByTheOsTest {

    private fun moduleRoot(): File {
        val candidates = listOf(File("."), File("app"), File("../app"))
        return candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: throw AssertionError(
                "找不到 app 模块。试过：${candidates.joinToString { it.absolutePath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private fun read(rel: String): String {
        val f = File(moduleRoot(), rel)
        assertTrue("文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 去掉 XML 注释 —— 查的是配置，不是解释这件事的散文。 */
    private fun xmlBody(rel: String) = read(rel).replace(Regex("<!--[\\s\\S]*?-->"), "")

    /** 去掉 // 行注释 —— 同上。 */
    private fun code(rel: String) = read(rel).lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `there is exactly one network security config`() {
        val dir = File(moduleRoot(), "src/main/res/xml")
        val configs = dir.listFiles().orEmpty().filter { it.name.startsWith("network_security_config") }
        assertEquals(
            "只许有一份 —— 第二份就是给某个构建类型换掉它的入口",
            listOf("network_security_config.xml"),
            configs.map { it.name },
        )
    }

    @Test
    fun `no build type swaps the config out`() {
        val gradle = code("build.gradle.kts")
        assertFalse(
            "build.gradle.kts 又用 resValue 换掉了 network_security_config",
            Regex("""resValue\([^)]*network_security_config""").containsMatchIn(gradle),
        )
    }

    @Test
    fun `cleartext is not blanket-denied at the os layer`() {
        val xml = xmlBody("src/main/res/xml/network_security_config.xml")
        assertTrue(
            "base-config 又禁了明文 —— 内网 ws:// 会被系统拦下",
            Regex("""<base-config[^>]*cleartextTrafficPermitted="true"""").containsMatchIn(xml),
        )
    }

    @Test
    fun `the code-level rule is actually wired into connect`() {
        // 系统层放开之后，"不许拿明文连公网"只剩代码层这一道。手填的地址不经过
        // ConnectionPathPlanner，所以 connect() 自己必须判。
        val src = code("src/main/java/com/galaxy/wear/data/AIPClient.kt")
        assertTrue("AIPClient.connect 没有经过 CleartextPolicy", src.contains("CleartextPolicy.isPermitted("))
    }

    @Test
    fun `the dead certificate pinning stays gone`() {
        // 钉的是一个本系统从不连的域名，pin 值是空串 —— 从未生效过，只会误导读代码的人。
        val src = code("src/main/java/com/galaxy/wear/data/AIPClient.kt")
        assertFalse(src.contains("CertificatePinner"))
        assertFalse(src.contains("galaxy.ufo.ai"))
        assertFalse(code("build.gradle.kts").contains("CERT_PIN"))
    }
}
