package com.galaxy.wear.network

/**
 * Wear 侧网关传输抽象 —— 与 Android 仓 `com.ufo.galaxy.network.GatewayClient`
 * 同一契约(跨仓对齐,勿单边加方法)。
 *
 * 唯一的本仓定义。当前唯一的实现是 WS(AIPClient) —— BLE 与 MQTT 两个实现
 * 已随 G 一并删除:它们零调用方,而"多一种传输"这件事的价值全在有人用它。
 */
interface GatewayClient {

    /**
     * Returns `true` when the transport layer has an active connection to the
     * Galaxy Gateway and is ready to send messages.
     */
    fun isConnected(): Boolean

    /**
     * Sends [json] to the Galaxy Gateway.
     *
     * @return `true` if the message was successfully dispatched; `false` if the
     *         connection is unavailable or the send failed. Callers must surface
     *         a send failure to the user.
     */
    fun sendJson(json: String): Boolean
}
