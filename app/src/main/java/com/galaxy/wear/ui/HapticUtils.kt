package com.galaxy.wear.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 触觉渲染 —— 把 [HapticVocabulary] 里的模式落到真实马达上。
 *
 * 词汇表(判什么手感)与渲染(怎么发出去)刻意分开:前者是纯 Kotlin,
 * 能在 CI 里真跑;后者依赖 Android 框架,只能靠编译 + 真机验证。
 *
 * ## 三级降级,依照官方文档给的优先级
 *
 * 1. **单下 → `createPredefined`**。文档明说优先用预定义效果:厂商为自家
 *    马达调过,跨设备手感最一致,也是无障碍上的正确做法。
 * 2. **多下 → `VibrationEffect.Composition` 基元**,但**必须先问
 *    `areAllPrimitivesSupported`** —— 文档特意提醒不是所有设备都支持组合 API。
 * 3. **不支持组合 → `createWaveform`**。普遍可用,手感不如前两者,
 *    但节奏(也就是身份)保得住。
 *
 * 任何一步失败都只记日志、不抛 —— 振动是锦上添花,不该成为崩溃源。
 */

private const val TAG = "HapticUtils"

/**
 * 取振动器。
 *
 * API 31+ 应走 `VibratorManager`;`Context.VIBRATOR_SERVICE` 在 31 起已废弃
 * (旧实现直接用它,CI 每次编译都在告警)。minSdk 30,所以两条分支都要留。
 */
private fun Context.vibratorCompat(): Vibrator? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
} catch (e: Exception) {
    Log.w(TAG, "取振动器失败: ${e.message}")
    null
}

private fun predefinedFor(strength: HapticStrength): Int = when (strength) {
    HapticStrength.LIGHT -> VibrationEffect.EFFECT_TICK
    HapticStrength.MEDIUM -> VibrationEffect.EFFECT_CLICK
    HapticStrength.STRONG -> VibrationEffect.EFFECT_HEAVY_CLICK
}

private fun primitiveFor(strength: HapticStrength): Int = when (strength) {
    // 只用 TICK / CLICK 两个基元:LOW_TICK 在部分设备上缺失,而它与 TICK 的
    // 差别小于 LIGHT/MEDIUM 之间的语义差别 —— 强度交给 scale 拉开更稳。
    HapticStrength.LIGHT -> VibrationEffect.Composition.PRIMITIVE_TICK
    else -> VibrationEffect.Composition.PRIMITIVE_CLICK
}

private fun HapticPattern.toEffect(vibrator: Vibrator): VibrationEffect {
    if (pulses.size == 1) {
        return VibrationEffect.createPredefined(predefinedFor(pulses[0].strength))
    }

    val primitives = pulses.map { primitiveFor(it.strength) }.toIntArray()
    val composable = runCatching { vibrator.areAllPrimitivesSupported(*primitives) }.getOrDefault(false)
    if (composable) {
        val composition = VibrationEffect.startComposition()
        pulses.forEach { pulse ->
            composition.addPrimitive(
                primitiveFor(pulse.strength),
                pulse.strength.primitiveScale,
                pulse.gapBeforeMs.toInt(),
            )
        }
        return composition.compose()
    }

    // 回退:节奏(身份)保住,手感打折。-1 = 不重复。
    return VibrationEffect.createWaveform(toWaveformTimings(), toWaveformAmplitudes(), -1)
}

/**
 * 按类别发一下触觉。
 *
 * 这是**唯一**的入口:任何界面都不该自己拼 `VibrationEffect` —— 那正是原来
 * `HomeScreen` 与 `WatchButtonReceiver` 各写一份、同样是"按了一下"却一个
 * CLICK 一个 HEAVY_CLICK 的由来,直接违反"同类交互同一手感"。
 */
fun triggerHaptic(context: Context, type: HapticType) {
    val vibrator = context.vibratorCompat() ?: return
    if (!vibrator.hasVibrator()) return
    val pattern = HapticVocabulary.patternFor(type)
    try {
        vibrator.vibrate(pattern.toEffect(vibrator))
    } catch (e: Exception) {
        Log.w(TAG, "触觉播放失败(非致命): ${e.message}")
    }
}

/** 无参版本 = 屏幕点按确认。全表最频繁的事件,取最轻的一下。 */
fun triggerHaptic(context: Context) {
    triggerHaptic(context, HapticType.UI_TAP)
}
