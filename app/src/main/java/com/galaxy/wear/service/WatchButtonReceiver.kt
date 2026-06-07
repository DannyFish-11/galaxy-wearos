package com.galaxy.wear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import com.galaxy.wear.VoiceActivity

/**
 * WatchButtonReceiver — 侧键硬键唤醒语音
 *
 * 通过监听系统按键事件实现:
 *   - 上侧键(KEYCODE_STEM_PRIMARY) 双击 → 启动 VoiceActivity
 *   - 上侧键 长按(1.5s) → 启动 VoiceActivity
 *
 * 需要在 AndroidManifest.xml 中注册:
 *   <receiver android:name=".service.WatchButtonReceiver">
 *     <intent-filter>
 *       <action android:name="android.intent.action.KEY_EVENT"/>
 *     </intent-filter>
 *   </receiver>
 *
 * 如果系统级 KEY_EVENT 广播不可用（部分 Wear OS 设备限制），
 * 回退方案: 在 GalaxyWearService 中通过 AccessibilityService 监听。
 */
class WatchButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Galaxy"
        private const val DOUBLE_TAP_WINDOW_MS = 400L
        private const val LONG_PRESS_MS = 800L
    }

    private var lastTapTime: Long = 0L
    private var tapCount: Int = 0

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_KEY_EVENT) return

        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            ?: return

        // Only handle side button (STEM_PRIMARY on Galaxy Watch)
        if (keyEvent.keyCode != KeyEvent.KEYCODE_STEM_PRIMARY) return

        when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(context, keyEvent)
            KeyEvent.ACTION_UP -> handleKeyUp(context)
        }
    }

    private fun handleKeyDown(context: Context, event: KeyEvent) {
        val now = System.currentTimeMillis()
        val pressDuration = now - event.downTime

        // Long press → launch voice
        if (pressDuration >= LONG_PRESS_MS) {
            Log.i(TAG, "[BUTTON] Long press detected — launching voice")
            triggerHaptic(context)
            launchVoice(context)
            return
        }

        // Double tap detection
        if (now - lastTapTime <= DOUBLE_TAP_WINDOW_MS) {
            tapCount++
            if (tapCount >= 2) {
                Log.i(TAG, "[BUTTON] Double tap detected — launching voice")
                triggerHaptic(context)
                launchVoice(context)
                tapCount = 0
            }
        } else {
            tapCount = 1
        }
        lastTapTime = now
    }

    private fun handleKeyUp(context: Context) {
        // Single tap logic can go here if needed
    }

    private fun launchVoice(context: Context) {
        val intent = Intent(context, VoiceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            action = VoiceActivity.ACTION_VOICE_COMMAND
        }
        context.startActivity(intent)
    }

    private fun triggerHaptic(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }
}
