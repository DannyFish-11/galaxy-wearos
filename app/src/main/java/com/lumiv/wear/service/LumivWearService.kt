package com.lumiv.wear.service

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
import androidx.core.app.RemoteInput
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lumiv.wear.LumivWearApplication
import com.lumiv.wear.MainActivity
import com.lumiv.wear.domain.model.Phase
import com.lumiv.wear.receiver.ReplyReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * LumivWearService — Foreground service for persistent AIP connection
 *
 * Runs continuously in the background to:
 * - Maintain AIP v3 WebSocket
 * - Push phase state to Lumiv
 * - Receive push notifications from Lumiv
 * - Handle voice command wake-ups
 */
class LumivWearService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "lumiv_wear"
        const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "com.lumiv.wear.DISCONNECT"
        const val TAG = "LumivWearService"
    }

    private val binder = LocalBinder()
    @Volatile
    private var isRunning = false
    private var phaseObserverJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): LumivWearService = this@LumivWearService
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
            val app = application as LumivWearApplication
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
            }
        }

        return START_STICKY
    }

    private fun stopGracefully() {
        isRunning = false
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lumiv Wear",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lumiv Wear OS background service"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
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
            val disconnectIntent = Intent(this, LumivWearService::class.java).apply {
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
            title = "Lumiv",
            text = "手表智能体运行中",
            showDisconnectAction = true
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    private fun observePhaseChanges() {
        val app = application as LumivWearApplication

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
                    updateNotification("Lumiv — $phaseText")

                    // Push phase report to Lumiv (best-effort)
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

    private fun updateNotification(text: String) {
        if (!isRunning) return
        val notification = buildNotification(title = text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    // ── HUMAN-DECISION: 决策通知（OpenClawd 需要人类确认） ──────────

    /**
     * 显示决策通知 — OpenClawd 需要人类确认时调用。
     *
     * 构建一个 Wear OS 优化的高优先级通知，包含：
     * - 标题 + 摘要描述
     * - 快捷选项按钮（作为 WearableExtender Action）
     * - 语音/文字输入（RemoteInput）
     * - 振动提醒
     *
     * 所有回复通过 [ReplyReceiver] 捕获并经由 AIPClient 发送到 Mesh 网络。
     *
     * @param title 决策标题（如 "确认删除文件？"）
     * @param summary 决策摘要描述
     * @param decisionId 唯一决策标识（用于后端关联）
     * @param options 用户可选的选项标签列表
     */
    fun showDecisionNotification(
        title: String,
        summary: String,
        decisionId: String,
        options: List<String>,
    ) {
        val context = this

        // 基础回复 Intent（不预填选项）
        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            putExtra(ReplyReceiver.EXTRA_DECISION_ID, decisionId)
        }
        val replyPending = PendingIntent.getBroadcast(
            context,
            decisionId.hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // WearableExtender: Wear OS 特有的通知扩展
        val wearableExtender = NotificationCompat.WearableExtender()
            .setHintShowBackgroundOnly(false)
            .setHintAvoidBackgroundClipping(true)

        // 为每个选项添加快捷 Action
        options.forEach { optionLabel ->
            val optionIntent = Intent(context, ReplyReceiver::class.java).apply {
                action = ReplyReceiver.ACTION_REPLY
                putExtra(ReplyReceiver.EXTRA_DECISION_ID, decisionId)
                putExtra(ReplyReceiver.EXTRA_OPTION_ID, optionLabel)
            }
            val optionPending = PendingIntent.getBroadcast(
                context,
                (decisionId + optionLabel).hashCode(),
                optionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            wearableExtender.addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_send,
                    optionLabel,
                    optionPending
                )
            )
        }

        // RemoteInput: 支持语音/键盘自由输入
        val remoteInput = RemoteInput.Builder(ReplyReceiver.EXTRA_VOICE_INPUT)
            .setLabel("语音回复…")
            .setAllowFreeFormInput(true)
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            "回复",
            replyPending
        ).addRemoteInput(remoteInput).build()

        wearableExtender.addAction(replyAction)

        // 构建高优先级通知
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠ $title")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setAutoCancel(true)
            .extend(wearableExtender)

        // 点击通知打开 DecisionScreen
        val decisionIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("navigate_to", "decision")
            putExtra("decision_id", decisionId)
            putExtra("decision_title", title)
            putExtra("decision_summary", summary)
            putStringArrayListExtra("decision_options", ArrayList(options))
        }
        val decisionPending = PendingIntent.getActivity(
            this,
            decisionId.hashCode() + 9999, // 避免与主 PendingIntent 冲突
            decisionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(decisionPending)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(decisionId.hashCode(), builder.build())
    }
}