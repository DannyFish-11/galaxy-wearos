package com.galaxy.wear

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能不能出一个像样的正式包：清单里没有没人用的权限和死组件、release 真被构建过、R8 规则覆盖了
 * 靠反射 / JNI 的东西、签名不会悄悄退回 debug 钥匙。
 *
 * 这些都是「编译器和 debug 构建看不见」的问题 —— 要到装上 release 包、在真表上跑起来才露出来。
 * 判读源码 / 配置本身，不起 Android 运行时。
 */
class ReleaseHygieneTest {

    private fun file(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    private fun exists(relative: String): Boolean =
        listOf(File(relative), File("app/$relative"), File("../app/$relative")).any { it.isFile }

    private val manifest by lazy { file("src/main/AndroidManifest.xml") }

    /** 去掉 XML 注释，只看真正声明的东西。 */
    private val manifestCode by lazy { manifest.replace(Regex("<!--[\\s\\S]*?-->"), "") }

    @Test
    fun `the manifest declares no permission nothing uses`() {
        for (p in listOf(
            "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE",
            "NEARBY_WIFI_DEVICES", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )) {
            assertFalse("$p 被声明了，可全仓没有代码用它", manifestCode.contains(p))
        }
    }

    @Test
    fun `no dead components are registered`() {
        assertFalse("VoiceActivity 不可达（exported=false 且没有调用方）", manifestCode.contains("VoiceActivity"))
        assertFalse("WatchButtonReceiver 监听的 KEY_EVENT 不是真实广播", manifestCode.contains("WatchButtonReceiver"))
        assertFalse("persistent 只对系统应用生效", manifestCode.contains("android:persistent"))
        for (gone in listOf("VoiceActivity.kt", "service/WatchButtonReceiver.kt", "ui/screens/QrCodeView.kt",
            "domain/usecase/ConnectUseCase.kt", "domain/usecase/SendVoiceQueryUseCase.kt", "network/TailscaleAdapter.kt")) {
            assertFalse("$gone 又回来了", exists("src/main/java/com/galaxy/wear/$gone"))
        }
    }

    @Test
    fun `the app has one name`() {
        assertTrue(manifestCode.contains("android:label=\"@string/app_name\""))
        assertFalse("清单里不该再手写应用名", Regex("android:label=\"(?!@)").containsMatchIn(manifestCode.substringBefore("<activity")))
        assertTrue(file("src/main/res/values/strings.xml").contains("<string name=\"app_name\">Galaxy</string>"))
    }

    @Test
    fun `the settings screen has no one-tap preset that writes a wrong address`() {
        val settings = file("src/main/java/com/galaxy/wear/ui/screens/SettingsScreen.kt")
        val code = settings.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("wss://localhost 在手表上指的是手表自己", code.contains("wss://localhost"))
        assertFalse("100.64.0.1 要有 VPN 才拨得通，且网关默认明文", code.contains("100.64.0.1"))
        assertFalse("配对是输入配对码，不是扫码", code.contains("扫码授权"))
    }

    @Test
    fun `r8 rules cover what is loaded by reflection or from native code`() {
        // 只看规则本身，不看解释它们的注释（注释里会提到被删掉的旧类名）。
        val rules = file("proguard-rules.pro").lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        for (needle in listOf(
            "kotlinx.serialization.KSerializer serializer",   // @Serializable 的序列化器
            "\$\$serializer",
            "org.msgpack.core.buffer.**",                     // MessageBuffer 按名字加载
            "org.webrtc.**",                                  // JNI 回调
            "HttpClientEngineContainer",                      // Ktor 引擎的 ServiceLoader
            "GeneratedMessageLite",                           // Tink / EncryptedSharedPreferences
        )) {
            assertTrue("R8 规则里缺 $needle", rules.contains(needle))
        }
        assertFalse("指向已不存在的 AIPMessage 的规则等于没写", rules.contains("com.galaxy.wear.data.AIPMessage"))
    }

    @Test
    fun `release is signed from the environment and never with the debug key`() {
        val gradle = file("build.gradle.kts")
        assertTrue(gradle.contains("GALAXY_KEYSTORE_PATH"))
        assertTrue(gradle.contains("signingConfig = signingConfigs.findByName(\"release\")"))
        assertFalse("release 不许悄悄用 debug 钥匙签", gradle.contains("signingConfigs.getByName(\"debug\")"))
        assertFalse("钥匙库口令不许写进仓库", Regex("storePassword\\s*=\\s*\"").containsMatchIn(gradle))
    }

    @Test
    fun `ci builds the release package and runs once per change`() {
        val ci = file(".github/workflows/wear-compile.yml")
        assertTrue("release 包从没被构建过", ci.contains(":app:assembleRelease"))
        assertFalse("push 到所有分支 + pull_request = 每次提交跑两遍", ci.contains("branches: [\"**\"]\n  pull_request"))
        assertTrue(ci.contains("workflow_dispatch"))
    }
}
