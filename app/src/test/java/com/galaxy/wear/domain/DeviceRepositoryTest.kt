package com.galaxy.wear.domain

import com.galaxy.wear.domain.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceRepository previously had zero test coverage despite holding real
 * device-list merge/status logic used by GalaxyWearApplication. These tests
 * pin down its actual behavior (local-entry preservation, id-based status
 * updates, offline sweeps).
 */
class DeviceRepositoryTest {

    @Test
    fun `default state has a single online local watch entry`() {
        val repo = DeviceRepository()
        val devices = repo.currentDevices
        assertEquals(1, devices.size)
        assertTrue(devices[0].isLocal)
        assertTrue(devices[0].online)
        assertEquals("wear_os", devices[0].type)
    }

    @Test
    fun `updateDeviceList preserves the local entry at index 0 even if caller omits it`() {
        val repo = DeviceRepository()
        val remote = Device(name = "Pixel 8", type = "android", deviceId = "dev-1", online = true)

        repo.updateDeviceList(listOf(remote))

        val devices = repo.currentDevices
        assertEquals(2, devices.size)
        assertTrue("local watch entry must always be present at index 0", devices[0].isLocal)
        assertEquals("dev-1", devices[1].deviceId)
    }

    @Test
    fun `updateDeviceList keeps caller-supplied local entry instead of synthesizing a new one`() {
        val repo = DeviceRepository()
        val customLocal = Device(name = "自定义手表", type = "wear_os", isLocal = true, online = false, deviceId = "local-x")

        repo.updateDeviceList(listOf(customLocal))

        val devices = repo.currentDevices
        assertEquals(1, devices.size)
        assertEquals("local-x", devices[0].deviceId)
        assertFalse(devices[0].online)
    }

    @Test
    fun `updateDeviceList keeps only the first isLocal entry, drops any extras`() {
        val repo = DeviceRepository()
        val local1 = Device(name = "Watch A", type = "wear_os", isLocal = true, deviceId = "a")
        val local2 = Device(name = "Watch B", type = "wear_os", isLocal = true, deviceId = "b")
        val remote = Device(name = "Phone", type = "android", isLocal = false, deviceId = "c")

        repo.updateDeviceList(listOf(local1, local2, remote))

        // find() picks the first isLocal match; the second isLocal-tagged entry
        // is not folded into remotes by filterNot { it.isLocal } - it's silently
        // dropped. This test documents that real (if surprising) behavior.
        val devices = repo.currentDevices
        assertEquals(2, devices.size)
        assertEquals("a", devices[0].deviceId)
        assertEquals("c", devices[1].deviceId)
    }

    @Test
    fun `updateDeviceStatus updates only the matching device by id`() {
        val repo = DeviceRepository()
        repo.updateDeviceList(listOf(Device(name = "Phone", type = "android", deviceId = "dev-1", online = false)))

        repo.updateDeviceStatus("dev-1", online = true)

        val updated = repo.currentDevices.first { it.deviceId == "dev-1" }
        assertTrue(updated.online)
    }

    @Test
    fun `updateDeviceStatus is a no-op for unknown device ids`() {
        val repo = DeviceRepository()
        repo.updateDeviceList(listOf(Device(name = "Phone", type = "android", deviceId = "dev-1", online = false)))

        repo.updateDeviceStatus("does-not-exist", online = true)

        val unchanged = repo.currentDevices.first { it.deviceId == "dev-1" }
        assertFalse(unchanged.online)
    }

    @Test
    fun `markAllOffline marks every remote device offline but never touches the local entry`() {
        val repo = DeviceRepository()
        repo.updateDeviceList(
            listOf(
                Device(name = "Phone", type = "android", deviceId = "dev-1", online = true),
                Device(name = "Desktop", type = "desktop", deviceId = "dev-2", online = true),
            )
        )

        repo.markAllOffline()

        val devices = repo.currentDevices
        assertTrue("local watch entry must stay online", devices.first { it.isLocal }.online)
        devices.filterNot { it.isLocal }.forEach {
            assertFalse("remote device ${it.deviceId} should be marked offline", it.online)
        }
    }
}
