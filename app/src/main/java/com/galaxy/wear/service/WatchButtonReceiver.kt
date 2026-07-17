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

        // ROUND-2-FIX: tap state MUST live in the companion object. The system
        // creates a NEW WatchButtonReceiver instance for every broadcast, so
        // instance fields were always zeroed — double-tap could never fire.
        // onReceive runs on the main thread, so plain fields are safe here.
        private var lastTapTime: Long = 0L
        private var tapCount: Int = 0
        /** Set once a long-press fires; reset on ACTION_UP so repeats don't relaunch. */
        private var longPressFired: Boolean = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Intent 里没有 ACTION_KEY_EVENT 这个常量(Unresolved),按键广播的 action
        // 就是清单 intent-filter 里声明的字符串 "android.intent.action.KEY_EVENT"。
        if (intent.action != "android.intent.action.KEY_EVENT") return

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
        // ROUND-2-FIX: KeyEvent timestamps are SystemClock.uptimeMillis(), NOT
        // wall-clock. The old code computed System.currentTimeMillis() -
        // event.downTime (epoch minus uptime ≈ billions of ms), so pressDuration
        // was ALWAYS >= LONG_PRESS_MS and every single key-down immediately
        // launched VoiceActivity as a "long press". Use event.eventTime, which
        // shares downTime's timebase.
        val now = event.eventTime
        val pressDuration = now - event.downTime

        // Long press → launch voice (fire once per physical press)
        if (pressDuration >= LONG_PRESS_MS) {
            if (!longPressFired) {
                longPressFired = true
                Log.i(TAG, "[BUTTON] Long press detected — launching voice")
                triggerHaptic(context)
                launchVoice(context)
            }
            return
        }

        // Double tap detection (only the first DOWN of each press, not key repeats)
        if (event.repeatCount == 0) {
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
    }

    private fun handleKeyUp(context: Context) {
        // ROUND-2-FIX: re-arm the long-press latch when the button is released.
        longPressFired = false
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
