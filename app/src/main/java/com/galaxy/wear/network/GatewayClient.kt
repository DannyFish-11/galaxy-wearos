package com.galaxy.wear.network

/**
 * Wear 侧网关传输抽象 —— 与 Android 仓 `com.ufo.galaxy.network.GatewayClient`
 * 同一契约(跨仓对齐,勿单边加方法)。
 *
 * 修复背景:AIPClient 声明实现 `com.galaxy.wear.network.GatewayClient`,
 * Ble/MqttGatewayClient 却 import Android 仓包名
 * `com.ufo.galaxy.network.GatewayClient` —— 而两个包里这个接口在本仓都
 * 不存在,三个实现类全部编译不过。本文件补上唯一的本仓定义,传输实现
 * (WS / BLE / MQTT)统一实现它。
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
