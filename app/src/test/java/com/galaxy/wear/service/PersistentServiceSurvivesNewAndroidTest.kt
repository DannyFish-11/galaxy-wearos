package com.galaxy.wear.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常驻服务在 Android 14/15 之后还活着。
 *
 *  · dataSync 前台服务在 Android 15(targetSdk 35)起每 24 小时只许跑 6 小时,到点系统调 `onTimeout`,
 *    服务不在几秒内停就抛 `RemoteServiceException` 把整个应用崩掉;而且 `BOOT_COMPLETED` 接收器不许启动
 *    dataSync。这个服务的本职就是「常驻、保持到网关的连接」。
 *  · 此前只有开机 / 覆盖安装才会拉起它,装完不重启就一直没有。
 *
 * 这些是平台规则带来的、运行期才暴露的缺陷,单测证明不了「真机上不崩」,只能钉住声明:
 * 用了不带期限的类型、声明了用途、实现了 onTimeout、开机拉不起来也不抛。
 */
class PersistentServiceSurvivesNewAndroidTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val service by lazy { source("src/main/java/com/galaxy/wear/service/GalaxyWearService.kt") }
    private val boot by lazy { source("src/main/java/com/galaxy/wear/receiver/BootReceiver.kt") }
    private val mainActivity by lazy { source("src/main/java/com/galaxy/wear/MainActivity.kt") }

    @Test
    fun `the persistent service declares specialUse with a purpose`() {
        val decl = manifest.substringAfter("android:name=\".service.GalaxyWearService\"").substringBefore("</service>")
        assertTrue("没声明 specialUse", decl.contains("specialUse"))
        assertTrue("没声明 specialUse 的用途", decl.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertFalse("persistent=true 只对系统应用生效,写了是误导", decl.contains("android:persistent"))
    }

    @Test
    fun `on Android 14 and up the service starts as specialUse not dataSync`() {
        val body = service.substringAfter("private fun startForeground()")
        val modern = body.substringBefore("Build.VERSION_CODES.Q")
        assertTrue(modern.contains("UPSIDE_DOWN_CAKE"))
        assertTrue(modern.contains("FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        // 老系统(29..33)仍用 dataSync,那里没有这条期限
        assertTrue(body.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
    }

    @Test
    fun `the service stops cleanly if the platform ever times it out`() {
        assertTrue(service.contains("override fun onTimeout(startId: Int, fgsType: Int)"))
        val body = service.substringAfter("override fun onTimeout(startId: Int, fgsType: Int)").substringBefore("\n    }")
        assertTrue("onTimeout 里没有停服务 —— 到点不停会被系统崩掉整个应用", body.contains("stopGracefully()"))
    }

    @Test
    fun `starting the service never throws out of a broadcast receiver`() {
        assertFalse("BootReceiver 又在直接调 startForegroundService", boot.contains("startForegroundService"))
        assertTrue(boot.contains("GalaxyWearService.start(context)"))
        val start = service.substringAfter("fun start(context: Context): Boolean")
        assertTrue("start() 必须吞掉启动失败并如实返回 false", start.contains("catch (e: Exception)") && start.contains("false"))
    }

    @Test
    fun `the app pulls the service up when it is opened`() {
        assertTrue(
            "首次启动不拉服务 —— 装完不重启就没有保活",
            mainActivity.contains("GalaxyWearService.start(this)"),
        )
    }
}
