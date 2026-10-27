package com.galaxy.wear.service

import com.galaxy.wear.service.ForegroundTypes.Kind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 在后台时也测得到心率:常驻服务在手表 App 界面拉起、且给了身体传感器权限时另带 health 类型;
 * 开机自启、粘性重启(后台)只用 specialUse —— Android 14 起在后台以 health 起前台服务会抛 SecurityException。
 */
class ForegroundTypesTest {

    @Test
    fun `health rides along on Android 14 only when granted and started from the watch app`() {
        assertEquals(setOf(Kind.SPECIAL_USE, Kind.HEALTH), ForegroundTypes.forWatch(34, bodySensorsGranted = true, fromVisibleUi = true))
        assertEquals(setOf(Kind.SPECIAL_USE), ForegroundTypes.forWatch(34, bodySensorsGranted = true, fromVisibleUi = false))
        assertEquals(setOf(Kind.SPECIAL_USE), ForegroundTypes.forWatch(35, bodySensorsGranted = false, fromVisibleUi = true))
        assertEquals("health 类型 14 才有", setOf(Kind.DATA_SYNC), ForegroundTypes.forWatch(33, bodySensorsGranted = true, fromVisibleUi = true))
        assertEquals(emptySet<Kind>(), ForegroundTypes.forWatch(28, true, true))
        assertEquals(setOf(Kind.SPECIAL_USE), ForegroundTypes.plain(34))
    }

    @Test
    fun `coming back to the app adds health once it is granted and the service lacks it`() {
        assertTrue(ForegroundTypes.needsUpgrade(34, bodySensorsGranted = true, running = setOf(Kind.SPECIAL_USE)))
        assertFalse(ForegroundTypes.needsUpgrade(34, true, setOf(Kind.SPECIAL_USE, Kind.HEALTH)))
        assertFalse("没给权限不用补", ForegroundTypes.needsUpgrade(34, false, setOf(Kind.SPECIAL_USE)))
        assertFalse("服务没在跑不由这里起", ForegroundTypes.needsUpgrade(34, true, null))
        assertFalse("14 以下没有 health", ForegroundTypes.needsUpgrade(33, true, setOf(Kind.DATA_SYNC)))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative。试过:" + candidates.joinToString { it.absolutePath })
    }

    @Test
    fun `only the watch app's own screen asks for health and the service can always fall back`() {
        val manifest = source("src/main/AndroidManifest.xml").replace(Regex("<!--[\\s\\S]*?-->"), "")
        val decl = manifest.substringAfter("android:name=\".service.GalaxyWearService\"").substringBefore("</service>")
        assertTrue("清单里没声明 health 类型", decl.contains("android:foregroundServiceType=\"dataSync|specialUse|health\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_HEALTH"))

        val activity = source("src/main/java/com/galaxy/wear/MainActivity.kt")
        assertTrue("回到界面时没补 health", activity.substringAfter("override fun onResume()").substringBefore("override fun onCreate(").contains("GalaxyWearService.upgradeIfNeeded(this)"))
        assertFalse("开机自启是后台,不能说自己在前台", source("src/main/java/com/galaxy/wear/receiver/BootReceiver.kt").contains("fromVisibleUi = true"))

        val service = source("src/main/java/com/galaxy/wear/service/GalaxyWearService.kt")
        val start = service.substringAfter("private fun startForeground(fromVisibleUi: Boolean)").substringBefore("private fun startForegroundAs(")
        assertTrue("带 health 起不来时没有退回", start.contains("ForegroundTypes.plain(sdk)") && start.contains("startForegroundAs(notification, plain)"))
        assertTrue(service.contains("startForeground(fromVisibleUi = intent?.getBooleanExtra(EXTRA_FROM_VISIBLE_UI, false) == true)"))
    }
}
