package com.galaxy.wear.sensing

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 可打扰性监视器 —— 采集侧的 Android 胶水。
 *
 * 职责边界刻意收得很窄:**采集 + 组装快照**,判断逻辑全在纯 Kotlin 的
 * [InterruptibilityEstimator] 里。所有数值判定因此都能在
 * `:app:testDebugUnitTest` 里被真跑,而不是只能靠真机蹲守。
 *
 * ## 隐私
 * 心率、加速度、静息基线**只存在于本进程内存 + 本机加密偏好**。
 * 对外只经 [report] 暴露 [InterruptibilityReport]。
 *
 * ## 省电:占空比采样,而不是常驻监听
 * 常驻注册加速度计与心率传感器会显著吃电。这里改成"采 [SAMPLE_WINDOW_MS]
 * 秒、歇一个周期"。用户在看表时周期缩短([PERIOD_ACTIVE_MS]),息屏时拉长
 * ([PERIOD_IDLE_MS])—— 息屏时本来也不会主动打扰,不需要高频判定。
 *
 * ## 权限
 * 只额外要一个 `BODY_SENSORS`(心率)。加速度计不需要权限;抬腕靠亮屏代理,
 * 也不需要权限;勿扰状态是只读查询,同样不需要权限。
 * **没拿到 `BODY_SENSORS` 也能工作** —— 心率那一路自动退出加权,
 * `confidence` 相应下降,而不是整条链路失效。
 */
class InterruptibilityMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val baselineStore: BaselineStore? = null,
) {

    /** 基线持久化钩子。做成接口是为了让监视器不直接依赖 EncryptedSharedPreferences。 */
    interface BaselineStore {
        fun load(): String
        fun save(encoded: String)
    }

    private val _report = MutableStateFlow(
        InterruptibilityReport(
            score = 0.5f,
            band = InterruptibilityBand.UNKNOWN,
            reasons = listOf(InterruptibilityReason.NO_SENSOR_DATA),
            confidence = 0f,
        )
    )
    val report: StateFlow<InterruptibilityReport> = _report.asStateFlow()

    private val estimator = InterruptibilityEstimator()
    private val baseline = HeartRateBaseline()

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val notificationManager: NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private var loopJob: Job? = null
    private var stillnessSeconds = 0L
    private var samplesSinceSave = 0
    private var lastTickAt = 0L

    @Volatile
    private var lastInteractionAt: Long = 0L

    /** 亮屏 = 用户抬腕在看(Wear OS 抬腕亮屏使这个代理成立)。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                lastInteractionAt = SystemClock.elapsedRealtime()
            }
        }
    }
    private var receiverRegistered = false

    // ── 生命周期 ────────────────────────────────────────────────────────

    fun start() {
        if (loopJob?.isActive == true) return

        baselineStore?.let { store ->
            runCatching { baseline.restore(store.load()) }
                .onFailure { Log.w(TAG, "静息基线恢复失败,从零重建: ${it.message}") }
        }

        if (!receiverRegistered) {
            runCatching {
                // ContextCompat 版本:targetSdk 34+ 要求动态注册时显式声明导出与否,
                // 由它按 API 级别分派,免得在低版本上撞不存在的 RECEIVER_NOT_EXPORTED 常量。
                ContextCompat.registerReceiver(
                    context,
                    screenReceiver,
                    IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON) },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }.onFailure { Log.w(TAG, "亮屏广播注册失败: ${it.message}") }
        }

        loopJob = scope.launch {
            while (isActive) {
                // 刻意**不**用 runCatching 包 suspend 调用:它会把 CancellationException
                // 一起吞掉,让协程在被取消后还继续跑一圈。这里显式重抛。
                val period = try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "可打扰性采样失败(本拍跳过): ${e.message}")
                    PERIOD_IDLE_MS
                }
                delay(period)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        baselineStore?.let { store ->
            runCatching { store.save(baseline.snapshot()) }
                .onFailure { Log.w(TAG, "静息基线持久化失败: ${it.message}") }
        }
        // 复位累计量:停机期间流逝的时间不该被算作"一直静止"。
        stillnessSeconds = 0L
        samplesSinceSave = 0
        lastTickAt = 0L
    }

    // ── 一拍 ────────────────────────────────────────────────────────────

    /** @return 下一拍应等待的毫秒数。 */
    private suspend fun tick(): Long {
        // 静止时长按**实际流逝**累计,而不是按"计划等待的周期"累计 ——
        // 采样窗本身要花几秒,系统还可能因打盹把这一拍推迟很久,
        // 用计划值会系统性低估,睡眠判据就永远够不着门槛。
        val tickStart = SystemClock.elapsedRealtime()
        val elapsedSeconds = if (lastTickAt > 0L) (tickStart - lastTickAt) / 1000L else 0L
        lastTickAt = tickStart

        val attending = powerManager?.isInteractive == true
        if (attending) lastInteractionAt = tickStart

        val motion = sampleMotion()
        val heartRate = if (hasBodySensorPermission()) sampleHeartRate() else null
        heartRate?.let {
            baseline.add(it)
            // 不每拍都落盘:EncryptedSharedPreferences 每次写都要走一遍加密。
            // 攒够 BASELINE_SAVE_EVERY 条再写一次,剩下的靠 stop() 兜底。
            samplesSinceSave += 1
            if (samplesSinceSave >= BASELINE_SAVE_EVERY) {
                samplesSinceSave = 0
                baselineStore?.let { store -> runCatching { store.save(baseline.snapshot()) } }
            }
        }

        val periodMs = if (attending) PERIOD_ACTIVE_MS else PERIOD_IDLE_MS
        stillnessSeconds = if (motion != null && motion < InterruptibilityThresholds.MOTION_STILL) {
            stillnessSeconds + elapsedSeconds
        } else {
            0L
        }

        val signals = InterruptibilitySignals(
            heartRateBpm = heartRate,
            restingHeartRateBpm = baseline.resting(),
            motionIntensity = motion,
            attending = attending,
            dndActive = isDndActive(),
            stillnessSeconds = stillnessSeconds,
            // 本进程内还没观察到任何亮屏时保持 null(= 这一路无证据),
            // 而不是谎报一个"很久没交互"的巨大值。
            lastInteractionAgeMs = lastInteractionAt
                .takeIf { it > 0L }
                ?.let { SystemClock.elapsedRealtime() - it },
        )

        _report.value = estimator.estimate(signals)
        return periodMs
    }

    private fun isDndActive(): Boolean {
        val filter = notificationManager?.currentInterruptionFilter ?: return false
        // INTERRUPTION_FILTER_UNKNOWN(0) 表示查不到 —— 查不到不等于开了勿扰。
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun hasBodySensorPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

    // ── 传感器采样 ──────────────────────────────────────────────────────

    /** 加速度合矢量在采样窗内的标准差(取标准差自然消掉重力直流分量)。 */
    private suspend fun sampleMotion(): Float? {
        val samples = collect(Sensor.TYPE_ACCELEROMETER, SAMPLE_WINDOW_MS) { event ->
            if (event.values.size < 3) {
                null
            } else {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                sqrt(x * x + y * y + z * z)
            }
        }
        if (samples.size < MIN_MOTION_SAMPLES) return null
        val mean = samples.average().toFloat()
        val variance = samples.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / samples.size
        return sqrt(variance).toFloat()
    }

    /** 心率取采样窗内的中位数;单个读数抖动大,中位数比平均值抗离群。 */
    private suspend fun sampleHeartRate(): Float? {
        val samples = collect(Sensor.TYPE_HEART_RATE, HR_WINDOW_MS) { event ->
            // accuracy 低于 LOW 表示"没贴合手腕/读数不可信",此时 values[0] 常为 0。
            val usable = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
            val bpm = event.values.firstOrNull() ?: 0f
            if (usable && bpm > 0f) bpm else null
        }
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * 注册 → 等一个窗口 → 注销,把窗口内的读数收集起来。
     *
     * 无论中途发生什么都必须注销:注册泄漏会让传感器一直供电,是手表上
     * 最典型的耗电 bug,所以注销放在 finally 里。
     */
    private suspend fun collect(
        sensorType: Int,
        windowMs: Long,
        map: (SensorEvent) -> Float?,
    ): List<Float> {
        val manager = sensorManager ?: return emptyList()
        val sensor = runCatching { manager.getDefaultSensor(sensorType) }.getOrNull() ?: return emptyList()

        val collected = ArrayList<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                map(event)?.let { value -> synchronized(collected) { collected.add(value) } }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = runCatching {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        if (!registered) return emptyList()

        return try {
            delay(windowMs)
            synchronized(collected) { ArrayList(collected) }
        } finally {
            runCatching { manager.unregisterListener(listener) }
        }
    }

    companion object {
        private const val TAG = "InterruptibilityMonitor"

        /** 用户在看表时判定得快一点,息屏时慢一点省电。 */
        const val PERIOD_ACTIVE_MS = 30_000L
        const val PERIOD_IDLE_MS = 120_000L

        const val SAMPLE_WINDOW_MS = 3_000L

        /** 手表光电心率传感器的首个有效读数往往要几秒,窗口给得比运动采样长。 */
        const val HR_WINDOW_MS = 8_000L

        /** 样本太少算出来的标准差没有意义,宁可返回 null(= 这一路无证据)。 */
        const val MIN_MOTION_SAMPLES = 8

        /** 每攒够这么多条心率样本才落盘一次基线。 */
        const val BASELINE_SAVE_EVERY = 10
    }
}
