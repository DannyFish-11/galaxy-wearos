package com.galaxy.wear.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.R
import com.galaxy.wear.MainActivity
import com.ufo.galaxy.shared.protocol.DeviceIdProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 通话期间的前台服务。
 *
 * 为什么必须有它
 * ------------
 * 表盘熄屏就是"应用进入后台"。Android 14 起,后台访问麦克风必须有一个
 * ``foregroundServiceType="microphone"`` 的前台服务在跑 —— 否则麦克风会被系统静音,
 * 表现是**抬手看表时能说话,手一放下 AI 就听不见了**,而且不报任何错。
 *
 * 常驻的 ``GalaxyWearService`` 顶不上:它是 ``dataSync`` 型,拿不到麦克风豁免;而把
 * 麦克风类型永久加到它身上,等于表上常年挂着一个录音指示灯。所以通话期间单起一个,
 * 挂断就停。
 *
 * 通话本体(``VoiceCallController``)也**住在这里**而不是界面里:通话要活过熄屏和
 * 划走界面,而 Compose 的作用域两者都活不过。
 */
class VoiceCallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) {
            _controller.value?.hangup("user_hangup")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        if (_controller.value != null) return START_NOT_STICKY // 已经在通话里

        val app = applicationContext as? GalaxyWearApplication
        if (app == null || !app.isAipClientReady()) {
            Log.w(TAG, "AIP 客户端未就绪,通话无法建立")
            stopSelf()
            return START_NOT_STICKY
        }

        val controller = VoiceCallController(
            context = applicationContext,
            deviceId = DeviceIdProvider.getOrCreateDeviceId(applicationContext),
            gateway = app.aipClient,
        )
        _controller.value = controller

        // 网关的消息只有一个订阅点在 Application 里,且它只认状态类消息。通话信令在
        // 这里单独收一份 —— SharedFlow 支持多订阅者,不会跟那边抢。
        scope.launch {
            app.aipClient.messages.collect { controller.onGatewayMessage(it) }
        }
        scope.launch {
            controller.ui.collect { if (it.state == CallState.ENDED) stopSelf() }
        }

        if (!controller.dial()) {
            // dial() 已经把原因写进 ui.endedReason,界面会显示。这里只负责收摊。
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _controller.value?.dispose()
        _controller.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时通话", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "与 AI 通话期间保持麦克风可用"
                    setShowBadge(false)
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通话中")
            .setContentText("与 AI 实时通话")
            .setSmallIcon(R.drawable.ic_call_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", hangup)
            .build()

        // 类型必须在 startForeground 时也报一遍,只写清单不够 —— 少了这一步,
        // Android 14 上后台麦克风照样被静音。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "GalaxyWear.CallSvc"
        private const val CHANNEL_ID = "galaxy_wear_call"
        private const val NOTIFICATION_ID = 42
        const val ACTION_HANGUP = "com.galaxy.wear.CALL_HANGUP"

        private val _controller = MutableStateFlow<VoiceCallController?>(null)

        /** 当前这通电话。没有通话时是 null。界面据此渲染,不自己持有控制器。 */
        val controller: StateFlow<VoiceCallController?> = _controller.asStateFlow()

        /** 起一通电话。必须从可见界面调 —— 后台起麦克风型前台服务会被系统拒绝。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java))
        }

        /** 挂断。走服务而不是直接调控制器,保证服务也跟着停掉。 */
        fun hangup(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceCallService::class.java).setAction(ACTION_HANGUP),
            )
        }
    }
}
