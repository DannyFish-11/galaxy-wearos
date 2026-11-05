package com.galaxy.wear.sensing

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * 智能体问到时读一次的身体数据:心率现测,步数读当天的账。
 *
 * 和 [InterruptibilityMonitor] 不是一回事:那边常驻采样、只上报一个标量;这里只在智能体调
 * `heart_rate` / `steps` 时读,回那一次的数,不留副本。读不了(没传感器、没给权限)给出原因,
 * 动作清单据此不报这两个动作。
 */
object BodyReadings {

    /** 测不了心率的原因;测得了是 null。 */
    fun heartRateBlocker(context: Context): String? = when {
        sensorManager(context)?.getDefaultSensor(Sensor.TYPE_HEART_RATE) == null -> "这块表没有心率传感器"
        !granted(context, android.Manifest.permission.BODY_SENSORS) ->
            "戴表的人没给手表「身体传感器」权限,测不了心率(在手表的应用权限里打开)"
        else -> null
    }

    /**
     * 现测心率:注册 → 收到 [enough] 个可信读数或等满 [windowMs] → 注销,返回这期间的可信读数。
     * 无论中途发生什么都注销(放在 finally):注册泄漏会让传感器一直供电。
     */
    suspend fun heartRate(context: Context, windowMs: Long = HR_WINDOW_MS, enough: Int = HR_ENOUGH): List<Float> {
        val manager = sensorManager(context) ?: return emptyList()
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return emptyList()
        val collected = ArrayList<Float>()
        val full = CompletableDeferred<Unit>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // accuracy 低于 LOW 表示没贴合手腕 / 读数不可信,此时 values[0] 常为 0
                val bpm = event.values.firstOrNull() ?: return
                if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_LOW || bpm <= 0f) return
                synchronized(collected) {
                    collected.add(bpm)
                    if (collected.size >= enough) full.complete(Unit)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
        if (!registered) return emptyList()
        try {
            withTimeoutOrNull(windowMs) { full.await() }
        } finally {
            runCatching { manager.unregisterListener(listener) }
        }
        return synchronized(collected) { ArrayList(collected) }
    }

    internal fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    internal fun sensorManager(context: Context): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 心率传感器一般要几秒才读稳;10 秒还没有就是没戴好。 */
    const val HR_WINDOW_MS = 10_000L
    const val HR_ENOUGH = 5
}

/**
 * 今天的步数账:常驻听计步传感器(硬件批量上报,省电),每次读数交给 [DailySteps] 记账,落本机加密偏好。
 *
 * 只在进程活着时记;进程不在时走的步,下次读数时由传感器的累计数补上(累计数本身不丢)。
 */
class StepTracker(
    private val context: Context,
    private val prefs: () -> SharedPreferences,
) : SensorEventListener {

    @Volatile
    private var day: StepDay? = null
    private var loaded = false
    private var listening = false
    private var lastSavedAt = 0L

    /** 读不了步数的原因;读得了是 null。 */
    fun blocker(): String? = when {
        BodyReadings.sensorManager(context)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null -> "这块表没有计步传感器"
        !BodyReadings.granted(context, android.Manifest.permission.ACTIVITY_RECOGNITION) ->
            "戴表的人没给手表「体能活动」权限,读不了步数(在手表的应用权限里打开)"
        else -> null
    }

    /** 开始记账。幂等;没传感器、没权限就不开,下次(用户给了权限之后)再调。 */
    @Synchronized
    fun ensureStarted() {
        if (listening || blocker() != null) return
        load()
        val manager = BodyReadings.sensorManager(context) ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        listening = runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US)
        }.getOrDefault(false)
    }

    /** 今天的账;今天还没读到过计步数就是 null。 */
    fun today(): StepDay? {
        synchronized(this) { load() }
        return day?.takeIf { it.date == localDate() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val counter = event.values.firstOrNull()?.toLong() ?: return
        val nowMs = System.currentTimeMillis()
        synchronized(this) {
            load()
            val prev = day
            val next = DailySteps.observe(prev, counter, localDate(), nowMs)
            day = next
            // 换天立刻落盘(当天起点丢了就算不对了);平时每分钟最多写一次 —— 加密偏好每次写都要加密一遍
            if (prev?.date != next.date || nowMs - lastSavedAt >= SAVE_EVERY_MS) {
                lastSavedAt = nowMs
                runCatching { prefs().edit().putString(PREF_STEP_DAY, DailySteps.encode(next)).apply() }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun load() {
        if (loaded) return
        loaded = true
        day = DailySteps.decode(runCatching { prefs().getString(PREF_STEP_DAY, null) }.getOrNull())
    }

    private fun localDate(): String = LocalDate.now().toString()

    companion object {
        const val PREF_STEP_DAY = "step_day"

        /** 计步数批量上报的最长延迟:步数不用实时,攒一会儿一起报最省电。 */
        const val MAX_REPORT_LATENCY_US = 5 * 60 * 1_000_000
        const val SAVE_EVERY_MS = 60_000L
    }
}
