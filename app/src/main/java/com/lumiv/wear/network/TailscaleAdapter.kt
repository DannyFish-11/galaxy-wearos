package com.lumiv.wear.network

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.lumiv.wear.LumivWearApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * Tailscale Network Adapter for Wear OS
 *
 * Detects Tailscale VPN (100.64.0.0/10) and auto-discovers the gateway node.
 * No third-party Tailscale SDK required — uses standard Android network APIs.
 *
 * Usage:
 *     val adapter = TailscaleAdapter(context)
 *     if (adapter.isInTailscaleNetwork()) {
 *         val gateway = adapter.autoDiscoverGateway()
 *         // gateway == "100.64.0.1" or null
 *     }
 */
class TailscaleAdapter(private val context: Context) {

    companion object {
        private const val TAG = "TailscaleAdapter"

        /** Tailscale IP range prefix: 100.64.0.0/10 */
        const val TAILSCALE_PREFIX = "100."

        /** Default Lumiv gateway port */
        const val DEFAULT_PORT = 9000

        /** Candidate gateway IPs to scan (Tailscale tailnet common assignments) */
        val CANDIDATE_IPS = listOf(
            "100.64.0.1", "100.64.0.2", "100.64.0.3",
            "100.64.0.4", "100.64.0.5",
            "100.100.100.100", "100.101.102.103",
            "100.76.76.76", "100.88.88.88"
        )

        /** Health check timeout (ms) */
        const val HEALTH_TIMEOUT_MS = 3000L
    }

    // ------------------------------------------------------------------
    // Tailscale network detection
    // ------------------------------------------------------------------

    /**
     * Returns true if any network interface has a 100.x.x.x address.
     */
    fun isInTailscaleNetwork(): Boolean {
        return getTailscaleAddresses().isNotEmpty()
    }

    /**
     * Returns the local Tailscale IP of this device (first 100.x match).
     */
    fun getLocalTailscaleIp(): String? {
        return getTailscaleAddresses().firstOrNull()
    }

    /**
     * Enumerate all network interfaces for 100.x addresses.
     */
    private fun getTailscaleAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces.asSequence()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith(TAILSCALE_PREFIX)) {
                        result += host
                        Log.i(TAG, "Tailscale IP detected: $host on ${iface.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces: ${e.message}")
        }
        return result
    }

    // ------------------------------------------------------------------
    // Gateway auto-discovery
    // ------------------------------------------------------------------

    /**
     * Scan candidate Tailscale IPs for an alive Lumiv gateway.
     * Returns the IP string if found, null otherwise.
     *
     * FIX #12: Parallelizes IP health checks instead of serial scanning,
     * reducing worst-case time from 27s (9 IPs × 3s) to ~3s.
     */
    suspend fun autoDiscoverGateway(): String? = supervisorScope {
        Log.i(TAG, "Scanning ${CANDIDATE_IPS.size} Tailscale candidates in parallel...")

        // Launch all health checks concurrently on the IO dispatcher
        val checks = CANDIDATE_IPS.map { ip ->
            async(Dispatchers.IO) {
                if (checkGatewayAlive(ip, DEFAULT_PORT)) ip else null
            }
        }

        // Collect results — take the first successful response
        var gatewayIp: String? = null
        for (check in checks) {
            val result = check.await()
            if (result != null && gatewayIp == null) {
                gatewayIp = result
                // Cancel remaining pending checks once we found a gateway
                checks.forEach { if (it.isActive) it.cancel() }
            }
        }

        if (gatewayIp != null) {
            Log.i(TAG, "Gateway discovered at $gatewayIp:$DEFAULT_PORT")
        } else {
            Log.w(TAG, "No gateway found in Tailscale network")
        }
        gatewayIp
    }

    /**
     * Quick HTTP health check to see if a Lumiv gateway is alive.
     *
     * FIX #13: Marked with @WorkerThread to enforce background-thread usage.
     * FIX #14: Redirects are explicitly disabled to prevent SSRF / redirection attacks.
     */
    @WorkerThread
    private fun checkGatewayAlive(ip: String, port: Int): Boolean {
        return try {
            val url = java.net.URL("http://$ip:$port/health")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = HEALTH_TIMEOUT_MS.toInt()
            conn.readTimeout = HEALTH_TIMEOUT_MS.toInt()
            conn.requestMethod = "GET"
            // FIX #14: Disable automatic redirect following for security
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Convenience
    // ------------------------------------------------------------------

    /**
     * Build the full WebSocket URL for a discovered gateway.
     */
    fun buildWsUrl(ip: String, port: Int = DEFAULT_PORT, secure: Boolean = true): String {
        val scheme = if (secure) "wss" else "ws"
        return "$scheme://$ip:$port"
    }

    /**
     * Returns a status summary for the Settings screen.
     */
    fun getStatusText(): String {
        return if (isInTailscaleNetwork()) {
            val ip = getLocalTailscaleIp()
            "Tailscale ON (this device: $ip)"
        } else {
            "Tailscale not detected"
        }
    }
}
