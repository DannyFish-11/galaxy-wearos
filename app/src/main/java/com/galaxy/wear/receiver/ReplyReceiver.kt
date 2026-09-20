package com.galaxy.wear.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.galaxy.wear.GalaxyWearApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ReplyReceiver — 处理 WearOS 决策通知回复
 *
 * 当用户在决策通知上选择选项或语音输入时，此 Receiver 接收广播，
 * 通过 AIPClient 将回复发送到 Galaxy Mesh 网络。
 */
class ReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.galaxy.wear.ACTION_DECISION_REPLY"

        /**
         * 在**消息**通知上直接回复(与决策回复分开)。
         *
         * 两者走的是完全不同的上行:决策回复是 `human_input`(带 decision_id,
         * 服务端要拿它去匹配那次挂起的决策);消息回复是普通的一句话,走
         * `voice_query` —— 和用户对着手表说话走同一条路。
         *
         * 混用会让消息回复带着一个不存在的 decision_id 上去,服务端匹配不到、
         * 静默丢弃,而手表这边看起来"已经回了"。
         */
        const val ACTION_MESSAGE_REPLY = "com.galaxy.wear.ACTION_MESSAGE_REPLY"

        const val EXTRA_DECISION_ID = "decision_id"
        const val EXTRA_OPTION_ID = "option_id"
        const val EXTRA_VOICE_INPUT = "voice_input"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val TAG = "ReplyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MESSAGE_REPLY) {
            handleMessageReply(context, intent)
            return
        }
        if (intent.action != ACTION_REPLY) return

        val decisionId = intent.getStringExtra(EXTRA_DECISION_ID) ?: run {
            Log.w(TAG, "Missing decision_id, ignoring")
            return
        }
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID)
        // ROUND-2-FIX: text/voice entered via the notification's RemoteInput
        // arrives in the RemoteInput results bundle, NOT as a plain string
        // extra — getStringExtra(EXTRA_VOICE_INPUT) was always null, so voice
        // replies silently went out with no voice_input payload.
        val voiceInput = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_VOICE_INPUT)?.toString()
            ?: intent.getStringExtra(EXTRA_VOICE_INPUT)

        Log.d(TAG, "Reply received: decision=$decisionId, option=$optionId, voice=$voiceInput")

        // ROUND-2-FIX: dismiss the decision notification after any reply —
        // action buttons don't auto-dismiss (autoCancel only applies to the
        // content intent), so the answered decision otherwise lingered in the
        // shade and could be answered a second time.
        try {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(decisionId.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel decision notification: ${e.message}")
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            try {
                val app = context.applicationContext as GalaxyWearApplication
                // Guard: aipClient is lateinit — accessing it before Application
                // init completes throws UninitializedPropertyAccessException.
                if (!app.isAipClientReady()) {
                    Log.e(TAG, "AIPClient not initialized — dropping human input")
                    return@launch
                }

                val payload = buildJsonObject {
                    put("decision_id", decisionId)
                    optionId?.let { put("selected_option", it) }
                    voiceInput?.let { put("voice_input", it) }
                    put("device", "wear_os")
                    put("timestamp", System.currentTimeMillis())
                }

                app.aipClient.sendCommand("human_input", payload)
                Log.i(TAG, "Human input sent to mesh: decision=$decisionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send human input: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 在消息通知上直接回复。
     *
     * 走 [com.galaxy.wear.data.AIPClient.sendVoiceQuery] —— 和用户对着手表说话
     * 同一条路。这一点很要紧:sendVoiceQuery 本身会把这句话记进会话上下文,
     * 所以**从通知里回的话也会出现在会话列表里**。
     *
     * 若改成自己拼一条命令发出去,这句话就只上了行、没进本地会话 ——
     * 用户在通知里回完,回头翻记录却找不到自己说过什么。
     */
    private fun handleMessageReply(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        val replyText = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_VOICE_INPUT)?.toString()
            ?: intent.getStringExtra(EXTRA_VOICE_INPUT)
        if (replyText.isNullOrBlank()) {
            Log.w(TAG, "消息回复为空,忽略")
            return
        }

        // 回过的消息通知要收起来:动作按钮不会自动消失(autoCancel 只管内容点击),
        // 否则回完它还挂在那儿,可以被回第二次。
        try {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(messageId.hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "收起消息通知失败: ${e.message}")
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val app = context.applicationContext as GalaxyWearApplication
                if (!app.isAipClientReady()) {
                    Log.e(TAG, "AIPClient 未就绪 —— 这句回复发不出去")
                    return@launch
                }
                app.aipClient.sendVoiceQuery(replyText)
                Log.i(TAG, "消息回复已发出: message=$messageId")
            } catch (e: Exception) {
                Log.e(TAG, "消息回复发送失败: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
