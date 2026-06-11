package com.lumiv.wear.network

/**
 * GatewayClient — unified transport interface for Wear OS.
 *
 * Provides a common abstraction for WebSocket, BLE, and MQTT transports.
 * AIPClient implements this interface for WebSocket communication.
 */
interface GatewayClient {
    /**
     * Connect to the gateway with the given credentials.
     *
     * @param url Gateway base URL (e.g., "wss://lumiv.ufo.ai")
     * @param authToken Bearer token for authorization
     * @param devId Stable device identifier
     */
    suspend fun connect(url: String, authToken: String, devId: String)

    /**
     * Disconnect from the gateway and clean up resources.
     */
    suspend fun disconnect()

    /**
     * Send a JSON-encoded message string to the gateway.
     *
     * @param json JSON-encoded message payload
     * @return true if the message was accepted for delivery
     */
    fun sendJson(json: String): Boolean

    /**
     * Check if the gateway connection is active.
     *
     * @return true if connected and authenticated
     */
    fun isConnected(): Boolean
}
