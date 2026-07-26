package com.galaxy.wear.network

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import com.galaxy.wear.GalaxyWearApplication
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

        /**
         * ROUND-4-FIX(网段判断与契约不符): 注释/常量声明的是 Tailscale 的
         * 100.64.0.0/10,但旧实现 startsWith("100.") 实际匹配整个 100.0.0.0/8 ——
         * 其中 100.0.x–100.63.x 与 100.128.x–100.255.x 是可路由的公网段
         * (例如 AWS 的 100.2x.x.x)。手表经蜂窝/代理拿到公网 100.x 地址时会
         * 误判"已在 Tailscale 网络",随后向 9 个候选 IP(此时是公网地址)的
         * 9000 端口发 HTTP /health 探测——既泄露探测流量又白等 ~3s。
         * 按 /10 语义收紧:第二段必须落在 [64, 127]。
         */
        fun isTailscaleIp(host: String): Boolean {
            if (!host.startsWith(TAILSCALE_PREFIX)) return false
            val second = host.removePrefix(TAILSCALE_PREFIX).substringBefore('.').toIntOrNull()
                ?: return false
            return second in 64..127
        }

        /** Default Galaxy gateway port */
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
                    // ROUND-4-FIX: 用严格的 100.64.0.0/10 判断,见 isTailscaleIp。
                    if (isTailscaleIp(host)) {
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
     * Scan candidate Tailscale IPs for an alive Galaxy gateway.
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
     * Quick HTTP health check to see if a Galaxy gateway is alive.
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
