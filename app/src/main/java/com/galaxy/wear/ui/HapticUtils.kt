package com.galaxy.wear.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * triggerHaptic — 双参数版本（兼容 DecisionScreen.kt 调用）
 */
fun triggerHaptic(context: Context, type: HapticType) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    val effect = when (type) {
        HapticType.PHASE_CHANGE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        HapticType.TASK_DONE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        HapticType.MESSAGE_ARRIVAL -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        HapticType.DECISION_PROMPT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        HapticType.ERROR -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }
    vibrator.vibrate(effect)
}

/**
 * 单参数版本（兼容 HomeScreen.kt 现有调用）
 */
fun triggerHaptic(context: Context) {
    triggerHaptic(context, HapticType.PHASE_CHANGE)
}
