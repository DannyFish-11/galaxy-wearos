package com.galaxy.wear.sensing

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager

/** 从系统读这块表此刻的状态,交给 [WatchState.build]。都不要权限,哪一样读不到就留 null。 */
object AndroidWatchState {

    fun read(context: Context): WatchState.Readings {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = runCatching { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrNull()
        return WatchState.Readings(
            battery = level?.takeIf { it in 0..100 }?.let { it / 100f },
            charging = runCatching { battery?.isCharging }.getOrNull(),
            screenOn = runCatching { context.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull(),
            inCall = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()?.let {
                it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
            },
        )
    }
}
