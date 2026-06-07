package com.galaxy.wear.domain.model

/**
 * LOW-FIX: Domain model for Galaxy mesh-connected devices.
 * Replaces the hard-coded DeviceItem in DevicesScreen with a proper
 * domain model that can be populated from gateway state sync messages.
 *
 * @param name      Human-readable device name (e.g., "本机手表")
 * @param type      Device type tag: "wear_os", "android", "desktop", "ios", etc.
 * @param isLocal   True if this device represents the local watch
 * @param online    True if the device is currently connected to the Galaxy mesh
 * @param deviceId  Optional stable device identifier from gateway
 */
data class Device(
    val name: String,
    val type: String,
    val isLocal: Boolean = false,
    val online: Boolean = false,
    val deviceId: String = "",
    val lastSeenMs: Long = System.currentTimeMillis(),
)
