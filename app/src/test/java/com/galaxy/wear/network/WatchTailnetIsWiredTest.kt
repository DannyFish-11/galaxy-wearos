package com.galaxy.wear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 出门直连这条链上每一环都真的接上了。
 *
 * 这条链跨了三样东西:Go 写的子进程、Gradle 的打包、Kotlin 的进程管理与取址。
 * 任何一环掉了都不会报错 —— 表现只是"出门连不上",而那正是它存在的理由。
 * 所以一环一环钉住:
 *   Go 进程的约定 ↔ Kotlin 的解析 / 打包名 ↔ 运行时找的文件名 / 配对存钥匙 →
 *   App 启动与配对后拉起进程 → 重连取址经它走。
 */
class WatchTailnetIsWiredTest {

    private fun repoRoot(): File {
        val candidates = listOf(File("."), File(".."), File("../.."))
        return candidates.map { it.canonicalFile }.firstOrNull { File(it, "tailnet/main.go").isFile }
            ?: throw AssertionError(
                "找不到仓库根(tailnet/main.go)。试过：${candidates.joinToString { it.canonicalPath }}。" +
                    "这里刻意不跳过 —— 一个『找不到就当通过』的守卫等于没有守卫。"
            )
    }

    private fun read(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("文件不存在：${f.path}", f.isFile)
        return f.readText()
    }

    private val app = "app/src/main/java/com/galaxy/wear"

    // ── Go 进程与 Kotlin 两边说的是同一套话 ────────────────────────────────

    @Test
    fun `the event names and fields the daemon emits are the ones the app parses`() {
        val go = read("tailnet/main.go")
        // gofmt 会把冒号后的空格对齐,所以按正则比,不按字面。
        for (s in listOf(
            "\"event\":\\s*\"starting\"", "\"event\":\\s*\"needs_login\"", "\"event\":\\s*\"ready\"",
            "\"tailnet_ip\":", "\"listen\":", "\"target\":",
        )) assertTrue("tailnet/main.go 里找不到 $s —— 两边的约定漂了", Regex(s).containsMatchIn(go))
        assertTrue(go.contains("exitNeedsLogin = ${TailnetProtocol.EXIT_NEEDS_LOGIN}"))
        val netenv = read("tailnet/netenv.go")
        for (s in listOf("json:\"cmd\"", "json:\"dns\"", "json:\"interfaces\"", "json:\"addrs\"", "json:\"mtu\"", "json:\"up\"", "json:\"index\""))
            assertTrue("tailnet/netenv.go 里找不到 $s", netenv.contains(s))
    }

    @Test
    fun `the key travels in the environment the daemon reads`() {
        assertTrue(read("tailnet/main.go").contains("os.Getenv(\"TS_AUTHKEY\")"))
        assertTrue(read("$app/network/TailnetDaemon.kt").contains("put(\"TS_AUTHKEY\""))
    }

    // ── 打包 ────────────────────────────────────────────────────────────────

    @Test
    fun `gradle builds the daemon for arm64 under the name the app looks for`() {
        val g = read("app/build.gradle.kts")
        assertTrue("没有编 tailnet 的任务", g.contains("val buildTailnet by tasks.registering(Exec::class)"))
        assertTrue(g.contains("environment(\"GOOS\", \"android\")"))
        assertTrue(g.contains("environment(\"GOARCH\", \"arm64\")"))
        assertTrue(g.contains("arm64-v8a/${TailnetProtocol.BINARY_NAME}"))
        assertTrue("编好的文件没并进 jniLibs", g.contains("jniLibs.srcDir(tailnetJniDir"))
        assertTrue("打包前没触发编译", g.contains("tasks.named(\"preBuild\") { dependsOn(buildTailnet) }"))
    }

    @Test
    fun `native libs are extracted so the daemon can be executed`() {
        // 不解压的话 nativeLibraryDir 里没有真实文件,exec 必然失败。
        val g = read("app/build.gradle.kts")
        assertTrue(Regex("""jniLibs\s*\{\s*useLegacyPackaging\s*=\s*true""").containsMatchIn(g))
    }

    // ── 运行时接线 ──────────────────────────────────────────────────────────

    @Test
    fun `pairing stores the key the gateway hands over`() {
        val src = read("$app/auth/PairClaimClient.kt")
        assertTrue(src.contains("body[\"tailnet_join\"]"))
        assertTrue(src.contains("persistTailnet(tailnet)"))
        assertTrue("一次性钥匙要进加密存储", src.contains("encryptedPrefs.edit().putString(KEY_TAILNET_AUTH_KEY"))
    }

    @Test
    fun `the app starts the daemon on launch and after pairing`() {
        val src = read("$app/GalaxyWearApplication.kt")
        val onCreate = src.substringAfter("override fun onCreate()").substringBefore("\n    override fun ")
        assertTrue("App 启动时没拉起 tailnet 进程", onCreate.contains("ensureTailnet()"))
        val login = src.substringAfter("fun loginWithToken(").substringBefore("\n    fun ")
        assertTrue("配完对没按新配置拉起 tailnet 进程", login.contains("ensureTailnet()"))
        assertTrue("钥匙用掉后没删", src.contains("pairClaimClient.clearTailnetAuthKey()"))
    }

    @Test
    fun `reconnect dials tailnet candidates through the daemon`() {
        val src = read("$app/GalaxyWearApplication.kt")
        val pick = src.substringAfter("private fun nextConnectUrl(").substringBefore("\n    private fun ")
        assertTrue("重连取址没经过 tailnet 转发", pick.contains("TailnetProtocol.dialUrlFor("))
        assertTrue("没用转发进程的就绪状态", pick.contains("tailnetDaemon.state.value"))
    }

    @Test
    fun `the binary name is consistent`() {
        assertEquals("libgalaxytailnet.so", TailnetProtocol.BINARY_NAME)
        assertTrue(read("$app/network/TailnetDaemon.kt").contains("TailnetProtocol.BINARY_NAME"))
    }
}
