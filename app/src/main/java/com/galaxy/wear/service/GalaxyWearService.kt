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
import com.galaxy.wear.domain.HomeStatus
import com.galaxy.wear.sensing.InterruptibilityMonitor
import com.galaxy.wear.sensing.InterruptibilityReport
import com.galaxy.wear.sensing.InterruptibilityUplinkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * GalaxyWearService — Foreground service for persistent AIP connection
 *
 * Runs continuously in the background to:
 * - Maintain AIP v3 WebSocket
 * - Carry the agent's commands, questions and messages to the wrist
 * - Report interruptibility so the agent knows whether now is a good time
 * - Keep the ongoing notification honest about the connection
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
        fun start(context: Context, fromVisibleUi: Boolean = false): Boolean = try {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, GalaxyWearService::class.java).putExtra(EXTRA_FROM_VISIBLE_UI, fromVisibleUi),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "常驻服务没拉起来: ${e.message}")
            false
        }

        /** 起服务的是手表上 App 自己可见的界面:可以带上 health 类型(见 [ForegroundTypes])。开机自启不是。 */
        const val EXTRA_FROM_VISIBLE_UI = "com.galaxy.wear.FROM_VISIBLE_UI"

        /** 服务此刻以哪些类型在前台;没在跑是 null。 */
        @Volatile
        var runningTypes: Set<ForegroundTypes.Kind>? = null
            private set

        /** 回到 App 界面时调:身体传感器权限是服务起来之后才给的(或服务是在后台重启的),从界面再起一次把 health 补上。 */
        fun upgradeIfNeeded(context: Context) {
            if (ForegroundTypes.needsUpgrade(Build.VERSION.SDK_INT, bodySensorsGranted(context), runningTypes)) {
                start(context, fromVisibleUi = true)
            }
        }

        private fun bodySensorsGranted(context: Context): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BODY_SENSORS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        const val TAG = "GalaxyWearService"

        /** 个人静息心率基线的本地存放键。**只存在本机加密偏好里,不上传。** */
        const val PREF_HR_BASELINE = "interruptibility_hr_baseline"

        /** 可打扰性上行的检查周期(真发不发由 InterruptibilityUplinkPolicy 决定)。 */
        const val UPLINK_TICK_MS = 15_000L
    }

    private val binder = LocalBinder()
    @Volatile
    private var isRunning = false
    private var statusObserverJob: Job? = null
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
        startForeground(fromVisibleUi = intent?.getBooleanExtra(EXTRA_FROM_VISIBLE_UI, false) == true)

        synchronized(this) {
            if (!isRunning) {
                isRunning = true
                observeStatus()
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

    /**
     * 起前台,类型见 [ForegroundTypes]。带 health 起不来(系统认为 App 不在前台)就退回不带 health 的类型 ——
     * 不能让常驻服务因为多要一个类型而起不来(那样 5 秒后就是 ForegroundServiceDidNotStartInTimeException)。
     */
    private fun startForeground(fromVisibleUi: Boolean) {
        val notification = buildNotification(
            title = "Galaxy",
            text = "手表智能体运行中",
            showDisconnectAction = true
        )
        val sdk = Build.VERSION.SDK_INT
        val wanted = ForegroundTypes.forWatch(sdk, bodySensorsGranted(this), fromVisibleUi)
        runningTypes = try {
            startForegroundAs(notification, wanted)
            wanted
        } catch (e: Exception) {
            val plain = ForegroundTypes.plain(sdk)
            if (wanted == plain) {
                Log.e(TAG, "Failed to start foreground: ${e.message}")
                null
            } else {
                Log.w(TAG, "前台服务带 health 没起来(${e.message}),退回 $plain;后台测不了心率")
                try {
                    startForegroundAs(notification, plain)
                    plain
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to start foreground: ${e2.message}")
                    null
                }
            }
        }
    }

    private fun startForegroundAs(notification: android.app.Notification, kinds: Set<ForegroundTypes.Kind>) {
        if (kinds.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification, typeBits(kinds))
        }
    }

    // 常量是编译期整数;每个只在 ForegroundTypes 为支持它的系统版本选中时才会用到
    @android.annotation.SuppressLint("InlinedApi")
    private fun typeBits(kinds: Set<ForegroundTypes.Kind>): Int = kinds.fold(0) { bits, kind ->
        bits or when (kind) {
            ForegroundTypes.Kind.SPECIAL_USE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            ForegroundTypes.Kind.DATA_SYNC -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ForegroundTypes.Kind.HEALTH -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
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

    /**
     * 常驻通知的文字跟着「与智能体的连接」走。
     *
     * 不再跟三态：三态是电脑上的东西，手表既不显示它也不上报它。
     * 这条通知唯一该诚实回答的是：连着没有、要不要重新配对。
     */
    private fun observeStatus() {
        val app = application as GalaxyWearApplication

        // Cancel any previous observer before starting a new one
        statusObserverJob?.cancel()

        statusObserverJob = lifecycleScope.launch {
            try {
                combine(app.connectionState, app.needsRepair) { state, repair ->
                    HomeStatus.of(state, repair, pending = 0).label
                }.collectLatest { label ->
                    if (!isRunning) return@collectLatest
                    updateNotification("Galaxy — $label")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Status observer cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Status observer crashed: ${e.message}")
            }
        }
    }

    // ── 可打扰性:手表回答"现在能不能打扰他" ──────────────────────────

    /**
     * 起传感 + 上行。
     *
     * 隐私边界(所有者拍板):这条常驻采样里,心率/加速度/静息基线**只在手表本地**参与运算,
     * 上行的只有一个标量报告。基线也只落到本机加密偏好,不进任何同步通道。
     * (智能体调 `heart_rate` / `steps` 是另一条路:被问到时读一次、回那一次的数,见 sensing/BodyReadings.kt。)
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
        runningTypes = null
    }

    private fun updateNotification(text: String) {
        if (!isRunning) return
        val notification = buildNotification(title = text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
