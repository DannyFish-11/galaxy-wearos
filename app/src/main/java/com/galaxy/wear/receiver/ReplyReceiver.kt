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
        const val EXTRA_DECISION_ID = "decision_id"
        const val EXTRA_OPTION_ID = "option_id"
        const val EXTRA_VOICE_INPUT = "voice_input"
        const val TAG = "ReplyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val decisionId = intent.getStringExtra(EXTRA_DECISION_ID) ?: run {
            Log.w(TAG, "Missing decision_id, ignoring")
            return
        }
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID)
        val voiceInput = intent.getStringExtra(EXTRA_VOICE_INPUT)

        Log.d(TAG, "Reply received: decision=$decisionId, option=$optionId, voice=$voiceInput")

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope.launch {
            try {
                val app = context.applicationContext as GalaxyWearApplication

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
}
