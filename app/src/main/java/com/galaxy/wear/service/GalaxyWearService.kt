package com.galaxy.wear.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.MainActivity
import com.galaxy.wear.domain.model.Phase
import com.galaxy.wear.sensing.InterruptibilityMonitor
import com.galaxy.wear.sensing.InterruptibilityReport
import com.galaxy.wear.sensing.InterruptibilityUplinkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * GalaxyWearService — Foreground service for persistent AIP connection
 *
 * Runs continuously in the background to:
 * - Maintain AIP v3 WebSocket
 * - Push phase state to Galaxy
 * - Receive push notifications from Galaxy
 * - Handle voice command wake-ups
 */
class GalaxyWearService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "galaxy_wear"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.galaxy.wear.DISCONNECT"
        /**
         * 拉起常驻服务。**只在应用处于前台时可靠**(首次启动、配对完成、用户点开通知) ——
         * Android 12+ 不许后台应用启动前台服务,抛 `ForegroundServiceStartNotAllowedException`。
         * 所以失败是预期内的情况,返回 false 由调用方决定怎么办,不在这里吞成一行日志。
         */
        fun start(context: Context): Boolean = try {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, GalaxyWearService::class.java)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "常驻服务没拉起来: ${e.message}")
            false
        }

        const val TAG = "GalaxyWearService"

        /** 个人静息心率基线的本地存放键。**只存在本机加密偏好里,不上传。** */
        const val PREF_HR_BASELINE = "interruptibility_hr_baseline"

        /** 可打扰性上行的检查周期(真发不发由 InterruptibilityUplinkPolicy 决定)。 */
        const val UPLINK_TICK_MS = 15_000L
    }

    private val binder = LocalBinder()
    @Volatile
    private var isRunning = false
    private var phaseObserverJob: Job? = null
    private var interruptibilityMonitor: InterruptibilityMonitor? = null
    private var interruptibilityJob: Job? = null
    private val uplinkPolicy = InterruptibilityUplinkPolicy()

    inner class LocalBinder : Binder() {
        fun getService(): GalaxyWearService = this@GalaxyWearService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        // W1-FIX: Channel creation moved to onStartCommand() to ensure
        // it's always called before startForeground() even in BOOT_COMPLETED
        // race conditions. Kept here as well for safety.
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // W1-FIX: Ensure notification channel is created BEFORE startForeground()
        // to prevent ForegroundServiceDidNotStartInTimeException on Android 12+
        createNotificationChannel()

        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "Disconnect requested via notification")
            val app = application as GalaxyWearApplication
            app.disconnect()
            stopGracefully()
            return START_NOT_STICKY
        }

        // CRITICAL-FIX: startForeground() MUST be called every time — skipping it
        // causes ForegroundServiceDidNotStartInTimeException (5s ANR) on Android 12+.
        // isRunning only guards observer launch, not the foreground notification.
        startForeground()

        synchronized(this) {
            if (!isRunning) {
                isRunning = true
                observePhaseChanges()
                observeInterruptibility()
            }
        }

        return START_STICKY
    }

    private fun stopGracefully() {
        isRunning = false
        stopInterruptibility()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Galaxy Wear",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Galaxy Wear OS background service"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        // 决策 / 消息两条渠道归通知这一层管(Application 收到决策时不经服务直接弹)；这里补建一次，
        // 保证服务起来之前渠道就在。
        WatchNotifications.ensureChannels(this)
    }

    // ------------------------------------------------------------------

    private fun buildNotification(
        title: String,
        text: String? = null,
        showDisconnectAction: Boolean = false
    ): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        text?.let { builder.setContentText(it) }

        if (showDisconnectAction) {
            val disconnectIntent = Intent(this, GalaxyWearService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val disconnectPending = PendingIntent.getService(
                this, 0, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "断开",
                disconnectPending
            )
        }

        return builder.build()
    }

    private fun startForeground() {
        val notification = buildNotification(
            title = "Galaxy",
            text = "手表智能体运行中",
            showDisconnectAction = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+:用 specialUse。dataSync 在 Android 15 起每 24 小时只许跑 6 小时,
                // 到点系统调 onTimeout,服务不停就抛 RemoteServiceException 把整个应用崩掉;
                // 而且 BOOT_COMPLETED 接收器不许启动 dataSync。这个服务的本职是「常驻、
                // 保持到网关的连接」,正好是 dataSync 的限制要挡的那种用法。
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
        }
    }

    /**
     * 前台服务类型到期(Android 15+ 的 dataSync / mediaProcessing 才会走到这里)。
     *
     * 系统给几秒让服务自己停,不停就抛 `RemoteServiceException`、整个应用崩。
     * 本服务在 Android 14+ 用的是 specialUse(没有这个期限),所以正常到不了这里;
     * 留着是因为「到不了」是对平台规则的判断,不是保证 —— 真到了,干净地停比崩好。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "前台服务类型到期(startId=$startId type=$fgsType),停止常驻服务")
        stopGracefully()
    }

    private fun observePhaseChanges() {
        val app = application as GalaxyWearApplication

        // FIX: Guard against uninitialized AIPClient (WARNING-7)
        if (!app.isAipClientReady()) {
            Log.w(TAG, "AIPClient not initialized — skipping phase observation")
            return
        }

        // Cancel any previous observer before starting a new one
        phaseObserverJob?.cancel()

        phaseObserverJob = lifecycleScope.launch {
            try {
                app.phase.collectLatest { phase ->
                    if (!isRunning) return@collectLatest

                    val phaseText = when (phase) {
                        Phase.SILENT -> "静默"
                        Phase.LIMINAL -> "临界"
                        Phase.MANIFEST -> "显现"
                    }
                    updateNotification("Galaxy — $phaseText")

                    // Push phase report to Galaxy (best-effort)
                    try {
                        app.aipClient.sendPhaseReport(phase.name.lowercase())
                    } catch (e: CancellationException) {
                        // Normal during shutdown
                    } catch (e: Exception) {
                        Log.w(TAG, "Phase report failed: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Phase observer cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Phase observer crashed: ${e.message}")
            }
        }
    }

    // ── 可打扰性:手表回答"现在能不能打扰他" ──────────────────────────

    /**
     * 起传感 + 上行。
     *
     * 隐私边界(所有者拍板):心率/加速度/静息基线**只在手表本地**参与运算,
     * 上行的只有一个标量报告。基线也只落到本机加密偏好,不进任何同步通道。
     *
     * 不做的事:这里**不**替上游做决定。手表只报"能不能打扰",要不要因此
     * 闭嘴是 V2 侧常驻注意力循环的事(有界延迟,不是丢弃)。
     */
    private fun observeInterruptibility() {
        val app = application as GalaxyWearApplication

        interruptibilityJob?.cancel()
        interruptibilityMonitor?.stop()
        uplinkPolicy.reset()

        val monitor = InterruptibilityMonitor(
            context = applicationContext,
            scope = lifecycleScope,
            baselineStore = object : InterruptibilityMonitor.BaselineStore {
                override fun load(): String =
                    app.encryptedPrefs.getString(PREF_HR_BASELINE, "") ?: ""

                override fun save(encoded: String) {
                    app.encryptedPrefs.edit().putString(PREF_HR_BASELINE, encoded).apply()
                }
            },
        )
        interruptibilityMonitor = monitor
        monitor.start()

        // 刻意**轮询** StateFlow 的当前值,而不是 collect 它。
        // StateFlow 会做等值合并:状态长期不变时(比如权限没给、一直是 UNKNOWN),
        // collect 只会触发一次,于是节流策略里的 10 分钟心跳**永远不会被求值** ——
        // 上游那边就是一条无限期陈旧的数据,恰好是心跳本来要防的事。
        // 轮询周期比监视器自身的采样周期短,响应延迟不会成为瓶颈。
        interruptibilityJob = lifecycleScope.launch {
            try {
                while (isActive) {
                    if (isRunning) uplinkOnce(app, monitor.report.value)
                    delay(UPLINK_TICK_MS)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "可打扰性观察者已取消")
            } catch (e: Exception) {
                Log.e(TAG, "可打扰性观察者崩溃: ${e.message}")
            }
        }
    }

    /**
     * UNKNOWN 也要上报:上游必须能区分"手表在,但没有传感证据"与"根本没有手表"。
     * 节流策略会把它压到最多每心跳一条。
     */
    private suspend fun uplinkOnce(app: GalaxyWearApplication, report: InterruptibilityReport) {
        val now = System.currentTimeMillis()
        if (!uplinkPolicy.shouldSend(report, now)) return
        if (!app.isAipClientReady()) return
        try {
            app.aipClient.sendInterruptibility(report, now)
            // 只有真发出去了才记账,失败时下一拍会重试。
            uplinkPolicy.recordSent(report, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "可打扰性上报失败(下一拍重试): ${e.message}")
        }
    }

    private fun stopInterruptibility() {
        interruptibilityJob?.cancel()
        interruptibilityJob = null
        // 必须显式 stop():否则传感器监听与亮屏广播会一直挂着耗电。
        interruptibilityMonitor?.stop()
        interruptibilityMonitor = null
    }

    override fun onDestroy() {
        stopInterruptibility()
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        if (!isRunning) return
        val notification = buildNotification(title = text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
