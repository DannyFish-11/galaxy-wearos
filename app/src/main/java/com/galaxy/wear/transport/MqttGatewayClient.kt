package com.galaxy.wear.transport

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.galaxy.wear.network.GatewayClient
import java.util.UUID

/**
 * MQTT Transport Adapter for Wear OS — AIP v3 over MQTT.
 *
 * Note: Wear OS devices often don't have direct internet access.
 * MQTT connection may need to go through a phone proxy or WiFi.
 * Consider BLE as the primary transport for watch-phone communication.
 */
/**
 * FIX #15: Default to secure MQTT over TLS (port 8883) instead of plaintext (port 1883).
 * Users must explicitly opt-out of encryption by passing useTls = false.
 */
class MqttGatewayClient(
    private val context: Context,
    private val brokerHost: String,
    private val brokerPort: Int = 8883,
    private val deviceId: String = "",
    private val useTls: Boolean = true,
) : GatewayClient, AutoCloseable {

    companion object {
        private const val TAG = "MqttWearClient"
        private const val TOPIC_PREFIX = "galaxy/aip/v3"

        /** Maximum number of outbound messages to queue while offline.
         *  FIX #16: Limits queue size to prevent unbounded memory growth / OOM. */
        private const val PENDING_QUEUE_MAX = 50
    }

    @Volatile private var client: Mqtt3AsyncClient? = null
    @Volatile private var connected = false
    private val pendingMessages = mutableListOf<String>()

    fun connect() {
        try {
            val clientId = deviceId.ifEmpty { "galaxy_wear_${UUID.randomUUID().toString().take(8)}" }
            val builder = MqttClient.builder()
                // ROUND-4-FIX(断链后 connected 永久为 true): 此前 connected 只在
                // connect 成功回调置 true、主动 disconnect() 回调置 false —— broker
                // 掉线/网络切换这类被动断链没有任何监听,isConnected() 恒真,
                // sendJson() 既不进离线队列也不报错,向已断开的客户端 publish 后
                // 仍返回 true,消息静默丢失。挂上 HiveMQ 的连接生命周期监听,
                // 让 connected 如实反映链路状态,离线消息才会落入 pendingMessages
                // 并在重连回调 flushPending 时补发。
                .addConnectedListener { _ ->
                    Log.i(TAG, "MQTT link up")
                    connected = true
                    // 链路恢复后补发离线期间积压的消息(cleanSession(false) 下
                    // broker 侧订阅仍在,无需重订阅)。
                    client?.let { c -> flushPending(c) }
                }
                .addDisconnectedListener { ctx ->
                    Log.w(TAG, "MQTT link down: ${ctx.cause.message}")
                    connected = false
                }
                // ROUND-4-FIX(被动断链后无重连): 本类没有任何外部重连触发点,
                // broker 掉线后传输永久失效、pendingMessages 永远滞留。启用
                // HiveMQ 内建自动重连(指数退避 1s→2min,自带上限,不会形成
                // 重连风暴),恢复后由上面的 ConnectedListener 补发积压。
                .automaticReconnectWithDefaultConfig()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(brokerHost)
                .serverPort(brokerPort)
            if (useTls) builder.sslWithDefaultConfig()

            val mqttClient = builder.buildAsync()
            mqttClient.connectWith()
                .cleanSession(false)
                .keepAlive(60)
                .willPublish()
                    .topic("$TOPIC_PREFIX/$deviceId/lwt")
                    .payload("{\"status\":\"offline\"}".toByteArray())
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .applyWillPublish()
                .send()
                .whenComplete { _, throwable: Throwable? ->
                    if (throwable == null) {
                        Log.i(TAG, "MQTT connected")
                        connected = true
                        subscribe(mqttClient)
                        flushPending(mqttClient)
                    } else {
                        Log.e(TAG, "MQTT connect failed: ${throwable.message}")
                    }
                }
            client = mqttClient
        } catch (e: Exception) {
            Log.e(TAG, "MQTT error: ${e.message}")
        }
    }

    fun disconnect() {
        client?.disconnect()?.whenComplete { _, _ -> connected = false }
    }

    override fun isConnected(): Boolean = connected

    override fun sendJson(json: String): Boolean {
        if (!connected) {
            // FIX #16: Ring-buffer — drop oldest messages when queue is full
            // to prevent unbounded memory growth leading to OOM.
            synchronized(pendingMessages) {
                if (pendingMessages.size >= PENDING_QUEUE_MAX) {
                    val dropped = pendingMessages.removeAt(0)
                    Log.w(TAG, "Pending queue full — dropped oldest message (${dropped.length} bytes)")
                }
                pendingMessages.add(json)
            }
            return false
        }
        return try {
            client?.publishWith()
                ?.topic("$TOPIC_PREFIX/$deviceId/in")
                ?.payload(json.toByteArray())
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.send()
            // FIX: Remove .get() to avoid blocking IO thread.
            // HiveMQ async client handles delivery guarantee via AT_LEAST_ONCE QoS.
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    override fun close() {
        disconnect()
        client = null
    }

    private fun subscribe(mqttClient: Mqtt3AsyncClient) {
        mqttClient.subscribeWith()
            .topicFilter("$TOPIC_PREFIX/$deviceId/out")
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish ->
                val payload = String(publish.payloadAsBytes)
                Log.d(TAG, "Received: $payload")
            }
            .send()
    }

    private fun flushPending(mqttClient: Mqtt3AsyncClient) {
        synchronized(pendingMessages) {
            pendingMessages.toList().also { pendingMessages.clear() }
                .forEach { msg ->
                    mqttClient.publishWith()
                        .topic("$TOPIC_PREFIX/$deviceId/in")
                        .payload(msg.toByteArray())
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .send()
                }
        }
    }
}
