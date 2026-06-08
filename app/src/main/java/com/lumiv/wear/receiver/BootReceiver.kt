package com.lumiv.wear.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lumiv.wear.service.LumivWearService

/**
 * BootReceiver — Handles BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 *
 * Android 8+ (API 26) prohibits Services from directly receiving BOOT_COMPLETED.
 * This receiver delegates to LumivWearService via startForegroundService().
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received ${intent.action}, starting LumivWearService")
                val serviceIntent = Intent(context, LumivWearService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
