package com.galaxy.wear.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.galaxy.wear.GalaxyWearApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * mDNS Service Discovery for Wear OS
 *
 * Searches for _galaxy._tcp services on the local Wi-Fi network.
 * When the gateway is on the same LAN, this finds it automatically
 * with zero configuration — no manual IP entry needed.
 *
 * Uses Android's built-in NSD API (no third-party dependencies).
 *
 * Usage:
 *     val discovery = MdnsDiscovery(context)
 *     val gatewayUrl = discovery.discoverGateway() // suspends, returns ws://IP:PORT
 */
class MdnsDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "MdnsDiscovery"
        const val SERVICE_TYPE = "_galaxy._tcp"
        const val DISCOVER_TIMEOUT_MS = 2000L // P2-FIX: 2s instead of 5s for faster discovery
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /**
     * Discover Galaxy gateway on the same LAN.
     * Returns the WebSocket URL (e.g. "ws://192.168.1.100:9000") or null.
     */
    suspend fun discoverGateway(): String? {
        val result = CompletableDeferred<String?>()
        var resolvedCount = 0

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "mDNS discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName} (${service.serviceType})")
                // ROUND-3-FIX: Android's NSD is inconsistent about the trailing dot.
                // discoverServices() is invoked with "_galaxy._tcp" (no trailing dot),
                // but on several Android builds onServiceFound() reports the type WITH
                // a trailing dot ("_galaxy._tcp."). A strict `==` then never matches, so
                // on those devices mDNS finds the gateway service yet never resolves it —
                // LAN auto-discovery silently falls through to Tailscale/manual entry.
                // Normalize by trimming trailing dots on both sides before comparing.
                if (service.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) {
                    try {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "Resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(s: NsdServiceInfo) {
                                val host = s.host?.hostAddress ?: return
                                val port = s.port
                                Log.i(TAG, "Resolved: $host:$port")
                                resolvedCount++
                                if (resolvedCount == 1) {
                                    result.complete("ws://$host:$port")
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "Resolve error: ${e.message}")
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery start failed: $errorCode")
                result.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        // FIX #11: Wrap the entire discovery lifecycle in try/finally to ensure
        // the discoveryListener is always cleaned up, even if withTimeoutOrNull
        // times out or the coroutine is cancelled.
        val url = try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { result.await() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start or complete discovery: ${e.message}")
            null
        } finally {
            // Always stop discovery to prevent NSD thread / listener leak
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.d(TAG, "stopServiceDiscovery cleanup error: ${e.message}")
            }
        }

        return url
    }

    /**
     * Quick check: is mDNS likely to work (Wi-Fi connected)?
     *
     * FIX #10: Checks for ACCESS_NETWORK_STATE permission before querying
     * NetworkCapabilities. Falls back to deprecated API on older devices.
     */
    @Suppress("DEPRECATION")
    fun isWifiAvailable(): Boolean {
        // Must hold ACCESS_NETWORK_STATE to query network capabilities
        val hasPermission = context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false

        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                val networkInfo = cm.activeNetworkInfo
                networkInfo != null &&
                        networkInfo.type == android.net.ConnectivityManager.TYPE_WIFI &&
                        networkInfo.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }
}
