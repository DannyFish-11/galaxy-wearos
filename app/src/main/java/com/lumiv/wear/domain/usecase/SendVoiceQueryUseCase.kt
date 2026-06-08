package com.lumiv.wear.domain.usecase

import android.util.Log
import com.lumiv.wear.data.AIPClient
import com.lumiv.wear.data.AIPConnectionState

/**
 * LOW-FIX: Domain UseCase for sending voice queries from Wear OS to Lumiv.
 * Encapsulates voice query logic including pre-send validation (connected state).
 *
 * @param aipClient The AIP WebSocket client for transport
 */
class SendVoiceQueryUseCase(
    private val aipClient: AIPClient
) {
    companion object {
        private const val TAG = "SendVoiceQueryUseCase"
    }

    /**
     * Send a voice query transcript to the gateway.
     * Returns true if the query was sent, false if not connected.
     */
    suspend fun execute(transcript: String): Boolean {
        if (transcript.isBlank()) {
            Log.w(TAG, "Empty transcript — not sending")
            return false
        }
        if (!aipClient.isConnected()) {
            Log.w(TAG, "Cannot send voice query — not connected")
            return false
        }
        return try {
            aipClient.sendVoiceQuery(transcript)
            Log.i(TAG, "Voice query sent: ${transcript.take(50)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send voice query: ${e.message}")
            false
        }
    }
}
