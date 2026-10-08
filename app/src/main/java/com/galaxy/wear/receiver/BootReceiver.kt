package com.galaxy.wear.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.galaxy.wear.service.GalaxyWearService

/**
 * BootReceiver — Handles BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 *
 * Android 8+ (API 26) prohibits Services from directly receiving BOOT_COMPLETED.
 * This receiver delegates to GalaxyWearService via GalaxyWearService.start() (never throws).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action}, starting GalaxyWearService")
                // 开机后拉不起前台服务不是致命的(部分系统 / 版本会拒绝从广播启动前台服务):
                // 应用进程已经起来了,下次用户打开应用时会再拉一次。这里不让异常冒出去 ——
                // 广播接收器里抛出的异常会让整个应用在开机时崩一次。
                if (!GalaxyWearService.start(context)) {
                    Log.w(TAG, "开机后没能拉起常驻服务,等用户下次打开应用时再拉")
                }
            }
        }
    }
}
