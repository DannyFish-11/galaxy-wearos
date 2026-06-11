package com.galaxy.wear.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DeviceInfo — 设备信息数据类
 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val deviceType: String,  // "wear_os" | "android" | "ios" | "desktop"
    val status: String,      // "connected" | "disconnected" | "unknown"
    val capabilities: List<String> = emptyList(),
    val lastSeen: Long = System.currentTimeMillis(),
    val extra: JsonElement? = null,
)

/**
 * DeviceListResult — 设备列表查询结果
 */
sealed class DeviceListResult {
    data class Success(val devices: List<DeviceInfo>) : DeviceListResult()
    data class Error(val message: String) : DeviceListResult()
    object Loading : DeviceListResult()
}
