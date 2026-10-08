package com.galaxy.wear.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.galaxy.wear.domain.pairOptionLabels
import com.galaxy.wear.receiver.ReplyReceiver
import com.galaxy.wear.ui.HapticType
import com.galaxy.wear.ui.HapticVocabulary

/**
 * 智能体的话怎么到手腕上:决策通知(HITL)与普通消息通知。
 *
 * 为什么不放在前台服务里
 * ======================
 * 这两条通知原先由 [GalaxyWearService] 弹,而 Application 收到决策/消息时是靠
 * `startForegroundService` 去「请」服务来弹。问题是:
 *
 *  · 手表在后台时(收到网关消息几乎总是后台),Android 12+ 不许应用启动前台服务,
 *    抛 `ForegroundServiceStartNotAllowedException`,被外层 `catch` 吞掉,只在日志里留一行 ——
 *    于是智能体问「要不要接入这盏灯」,手表上什么都没有;
 *  · 弹一条通知本来就不需要前台服务,只需要一个 Context。
 *
 * 所以弹通知这件事和服务解耦:谁拿着 Context 谁就能弹,不论服务在不在跑。
 * 返回值如实说「通知有没有可能被看到」—— 通知权限被拒时是 false,调用方(例如智能体下发的
 * `notify` 动作)据此回报失败,而不是回一个「已送达」。
 */
object WatchNotifications {

    /** HITL 决策通知专用的高重要性渠道(原因见 [ensureChannels])。 */
    const val CHANNEL_ID_DECISIONS = "galaxy_wear_decisions"

    /**
     * 智能体主动发来的消息用的渠道。
     *
     * 刻意**不**复用决策渠道:那条是 IMPORTANCE_HIGH + CATEGORY_ALARM,
     * 语义是"停下手里的事,等你拿主意"。一条普通消息用闹钟的手感推过来,
     * 用户不看屏幕就分不出哪条是真要他决定的 —— 而那恰恰是决策渠道存在的理由。
     * 这条按平常手表消息的规格走:DEFAULT 重要性、消息类别、消息到达的触感。
     */
    const val CHANNEL_ID_MESSAGES = "galaxy_wear_messages"

    private const val TAG = "WatchNotifications"

    /**
     * 带 RemoteInput 的内联回复必须用**可变**的 PendingIntent:系统要把用户输入的文字填进
     * 这个 Intent。用 FLAG_IMMUTABLE 的话,通知上的「回复」点得开、输得进、发得出,收到的却是
     * 一个空回复 —— 没有异常,没有日志。没有 RemoteInput 的(选项按钮)保持 IMMUTABLE。
     *
     * 回调的 Intent 都是显式的(指定了 ReplyReceiver 组件),所以可变不会被别的应用劫持。
     */
    private fun inlineReplyFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)

    private fun fixedFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 创建两条渠道。重复调用无害。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ROUND-2-FIX: separate HIGH-importance channel for HITL decisions so
        // they actually alert (sound/vibration/heads-up) instead of inheriting
        // the silent LOW importance of the persistent-service channel.
        val decisionChannel = NotificationChannel(
            CHANNEL_ID_DECISIONS,
            "Galaxy 决策提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "OpenClawd 需要人工决策时的提醒"
            enableVibration(true)
            // 让全表最重要的那一类真的用上词汇表里的"等距三拍"。
            // Android O+ 上振动由**渠道**决定,Builder 上的 setVibrate 会被忽略 ——
            // 不接这一行的话,决策提醒用的是系统默认振动,与"消息到达"手感一样,
            // 用户不看屏幕就分不出"有条消息"和"等你拿主意"。
            vibrationPattern = HapticVocabulary
                .patternFor(HapticType.DECISION_PROMPT)
                .toWaveformTimings()
        }
        nm.createNotificationChannel(decisionChannel)

        // 智能体主动发来的消息。DEFAULT 而不是 HIGH:它该像平常手表上那种消息
        // 推送,不该是闹钟 —— 决策渠道的那份"等距三拍"要留给真正需要拿主意的事。
        val messageChannel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            "Galaxy 消息",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "智能体发来的消息"
            enableVibration(true)
            // Android O+ 上振动由**渠道**决定,Builder 上的 setVibrate 被忽略。
            // 用词汇表里那条早就定义好、却从来没有东西产生过的 MESSAGE_ARRIVAL。
            vibrationPattern = HapticVocabulary
                .patternFor(HapticType.MESSAGE_ARRIVAL)
                .toWaveformTimings()
        }
        nm.createNotificationChannel(messageChannel)
    }

    /** 通知有没有可能被看到:权限被拒(Android 13+ 的 POST_NOTIFICATIONS)时系统会静默丢掉。 */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // ── HUMAN-DECISION: 决策通知（OpenClawd 需要人类确认） ──────────

    /**
     * 显示决策通知 — OpenClawd 需要人类确认时调用。
     *
     * 构建 Wear OS 优化的高优先级通知，包含快捷选项按钮和语音输入。
     * 所有回复通过 ReplyReceiver 捕获并经由 AIPClient 发送到 Mesh 网络。
     *
     * @return 通知是否可能被看到(见 [canNotify])
     */
    fun showDecisionNotification(
        context: Context,
        title: String,
        summary: String,
        decisionId: String,
        options: List<String>,
        labels: List<String> = emptyList(),
    ): Boolean = try {
        ensureChannels(context)

        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            putExtra(ReplyReceiver.EXTRA_DECISION_ID, decisionId)
        }
        val replyPending = PendingIntent.getBroadcast(
            context, decisionId.hashCode(), replyIntent, inlineReplyFlags()
        )

        val wearableExtender = NotificationCompat.WearableExtender()
            .setHintShowBackgroundOnly(false)

        // 按钮**显示** label、**回传** id。此前两者都用 id,于是手腕上印的是
        // `approve`/`deny` 这种协议字面量 —— 一个瞟一眼就要按下去的界面,
        // 却要求用户先认识协议。
        pairOptionLabels(options, labels).forEach { option ->
            val optionIntent = Intent(context, ReplyReceiver::class.java).apply {
                action = ReplyReceiver.ACTION_REPLY
                putExtra(ReplyReceiver.EXTRA_DECISION_ID, decisionId)
                putExtra(ReplyReceiver.EXTRA_OPTION_ID, option.id)
            }
            val optionPending = PendingIntent.getBroadcast(
                context, (decisionId + option.id).hashCode(), optionIntent, fixedFlags()
            )
            wearableExtender.addAction(NotificationCompat.Action(
                android.R.drawable.ic_menu_send, option.label, optionPending
            ))
        }

        val remoteInput = RemoteInput.Builder(ReplyReceiver.EXTRA_VOICE_INPUT)
            .setLabel("语音回复...")
            .setAllowFreeFormInput(true)
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_btn_speak_now, "回复", replyPending
        ).addRemoteInput(remoteInput).build()
        wearableExtender.addAction(replyAction)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_DECISIONS)
            .setContentTitle("⚠ $title")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // 同一份词汇表,不再写一串与渠道对不上的魔数。
            .setVibrate(HapticVocabulary.patternFor(HapticType.DECISION_PROMPT).toWaveformTimings())
            .setAutoCancel(true)
            .extend(wearableExtender)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(decisionId.hashCode(), builder.build())
        canNotify(context)
    } catch (e: Exception) {
        Log.e(TAG, "决策通知没弹出来: ${e.message}")
        false
    }

    /**
     * 智能体主动发来的一条消息 —— 就是平常手表上那种消息推送。
     *
     * 与决策通知的三处刻意不同:
     *
     *  · 渠道是 [CHANNEL_ID_MESSAGES](DEFAULT 重要性),不是决策那条 HIGH ——
     *    一条普通消息不该用闹钟的手感;
     *  · 类别是 `CATEGORY_MESSAGE` 而不是 `CATEGORY_ALARM` —— 系统据此决定
     *    免打扰时段怎么处理它,把消息报成闹钟会在深夜把人吵醒;
     *  · 只在协议说了 `reply_expected` 时才给回复入口。每条消息都挂一个回复框,
     *    会让"只是告诉你一声"的那些也显得在等你答话。
     *
     * 通知 id 用 [messageId] 的哈希:同一条消息补发时覆盖而不是再弹一条。
     *
     * @return 通知是否可能被看到(见 [canNotify])
     */
    fun showAgentMessageNotification(
        context: Context,
        text: String,
        title: String,
        messageId: String,
        conversationId: String,
        replyExpected: Boolean,
    ): Boolean = try {
        ensureChannels(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setContentTitle(title.ifBlank { "Galaxy" })
            .setContentText(text)
            // 手表屏幕窄,长消息不展开就只剩第一行。
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)

        if (replyExpected) {
            val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
                action = ReplyReceiver.ACTION_MESSAGE_REPLY
                putExtra(ReplyReceiver.EXTRA_MESSAGE_ID, messageId)
                putExtra(ReplyReceiver.EXTRA_CONVERSATION_ID, conversationId)
            }
            val replyPending = PendingIntent.getBroadcast(
                context, messageId.hashCode(), replyIntent, inlineReplyFlags()
            )
            val remoteInput = RemoteInput.Builder(ReplyReceiver.EXTRA_VOICE_INPUT)
                .setLabel("回复...")
                .setAllowFreeFormInput(true)
                .build()
            builder.extend(
                NotificationCompat.WearableExtender().addAction(
                    NotificationCompat.Action.Builder(
                        android.R.drawable.ic_btn_speak_now, "回复", replyPending
                    ).addRemoteInput(remoteInput).build()
                )
            )
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(messageId.hashCode(), builder.build())
        canNotify(context)
    } catch (e: Exception) {
        Log.e(TAG, "消息通知没弹出来: ${e.message}")
        false
    }
}
