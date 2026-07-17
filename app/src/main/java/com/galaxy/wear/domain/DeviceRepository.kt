package com.galaxy.wear.domain

import com.galaxy.wear.domain.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LOW-FIX: Repository for Galaxy mesh device list.
 * Follows repository pattern to abstract data source (gateway state-sync)
 * from UI presentation. Device list is populated from AIP state_event
 * messages and maintained as an observable StateFlow.
 *
 * In future: This could be backed by a local database cache for offline display.
 */
class DeviceRepository {
    // Internal mutable state — defaults to single local watch entry
    private val _devices = MutableStateFlow<List<Device>>(
        listOf(Device(name = "本机手表", type = "wear_os", isLocal = true, online = true))
    )

    /** Observable device list for UI consumption */
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /** Current snapshot of device list */
    val currentDevices: List<Device> get() = _devices.value

    /**
     * Replace the entire device list (e.g., from gateway state sync).
     * Always preserves the local watch entry at index 0.
     */
    fun updateDeviceList(newDevices: List<Device>) {
        val localEntry = newDevices.find { it.isLocal }
            ?: Device(name = "本机手表", type = "wear_os", isLocal = true, online = true)
        val remoteDevices = newDevices.filterNot { it.isLocal }
        _devices.value = listOf(localEntry) + remoteDevices
    }

    /**
     * Update the online status of a specific device by its ID.
     */
    fun updateDeviceStatus(deviceId: String, online: Boolean) {
        _devices.value = _devices.value.map { device ->
            if (device.deviceId == deviceId) {
                device.copy(online = online, lastSeenMs = System.currentTimeMillis())
            } else {
                device
            }
        }
    }

    /** Mark all remote devices as offline (e.g., on disconnect) */
    fun markAllOffline() {
        _devices.value = _devices.value.map { device ->
            if (device.isLocal) device else device.copy(online = false)
        }
    }
}
