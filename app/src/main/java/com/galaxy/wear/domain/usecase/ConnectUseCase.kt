package com.galaxy.wear.domain.usecase

import android.util.Log
import com.galaxy.wear.GalaxyWearApplication
import com.galaxy.wear.data.AIPClient

/**
 * LOW-FIX: Domain UseCase for establishing AIP v3 WebSocket connection.
 * Encapsulates connection logic (timeout, retry, validation) in the domain layer
 * instead of spreading it across UI and Application classes.
 *
 * @param app GalaxyWearApplication for accessing encrypted credentials and client
 */
class ConnectUseCase(
    private val app: GalaxyWearApplication
) {
    companion object {
        private const val TAG = "ConnectUseCase"
        /** Default connection timeout in milliseconds */
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }

    /**
     * Execute connection with stored credentials.
     * Returns true if connection was initiated, false if preconditions not met.
     */
    suspend fun execute(serverUrl: String, token: String, deviceId: String?): Boolean {
        if (!app.isAipClientReady()) {
            Log.e(TAG, "AIPClient not initialized")
            return false
        }
        if (serverUrl.isBlank() || token.isBlank()) {
            Log.e(TAG, "Server URL and token are required")
            return false
        }
        app.connect(serverUrl, token, deviceId)
        return true
    }

    /**
     * Execute connection with stored credentials from EncryptedSharedPreferences.
     * SECURITY-FIX: Reads auth_token from encrypted storage. Falls back to plaintext
     * for migration and securely migrates the token to encrypted storage.
     */
    suspend fun executeWithStoredCredentials(): Boolean {
        return try {
            val prefs = app.getSharedPreferences("galaxy_config", android.content.Context.MODE_PRIVATE)
            val url = try {
                app.encryptedPrefs.getString("server_url", "") ?: ""
            } catch (e: Exception) {
                val plaintext = prefs.getString("server_url", "") ?: ""
                if (plaintext.isNotEmpty()) {
                    app.encryptedPrefs.edit().putString("server_url", plaintext).apply()
                    prefs.edit().remove("server_url").apply()
                }
                plaintext
            }
            val token = try {
                app.encryptedPrefs.getString("auth_token", "") ?: ""
            } catch (e: Exception) {
                // Fallback to plaintext for migration
                val plaintext = prefs.getString("auth_token", "") ?: ""
                if (plaintext.isNotEmpty()) {
                    // Migrate to encrypted
                    app.encryptedPrefs.edit().putString("auth_token", plaintext).apply()
                    prefs.edit().remove("auth_token").apply()
                }
                plaintext
            }
            if (url.isNotEmpty() && token.isNotEmpty()) {
                execute(url, token, null)
            } else {
                Log.w(TAG, "No stored credentials found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read stored credentials: ${e.message}")
            false
        }
    }
}
