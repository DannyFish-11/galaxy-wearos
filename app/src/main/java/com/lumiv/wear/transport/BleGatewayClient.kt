package com.lumiv.wear.transport

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.ufo.lumiv.network.GatewayClient
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE Transport Adapter for Wear OS — AIP v3 over Bluetooth Low Energy.
 *
 * Primary transport for Wear OS watch-phone communication.
 * Wear OS devices typically use BLE to connect to a paired phone,
 * which then proxies messages to the Lumiv Gateway.
 *
 * UUIDs:
 * - Service: 0000aip3-0000-1000-8000-00805f9b34fb
 * - RX (write): 0000aip3-0001-...
 * - TX (notify): 0000aip3-0002-...
 */
class BleGatewayClient(
    private val context: Context,
) : GatewayClient {

    companion object {
        private const val TAG = "BleWearClient"
        val AIP_SERVICE_UUID: UUID = UUID.fromString("0000a1b3-0000-1000-8000-00805f9b34fb")
        val RX_UUID: UUID = UUID.fromString("0000a1b3-0001-1000-8000-00805f9b34fb")
        val TX_UUID: UUID = UUID.fromString("0000a1b3-0002-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val MTU_SIZE = 512

        /** Maximum auto-reconnect attempts before giving up (prevents battery drain) */
        private const val MAX_RECONNECT_RETRIES = 5
        /** Maximum pending message queue size — prevents OOM during BLE disconnection */
        private const val MAX_PENDING_MESSAGES = 50
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager.adapter
    private var gatt: BluetoothGatt? = null

    @Volatile private var connected = false
    private val pendingMessages = ConcurrentLinkedQueue<String>()

    /** Thread-safe scan results — accessed from Binder (scan callback) and main threads */
    private val scanResults = ConcurrentHashMap<String, BluetoothDevice>()
    private var scanCb: ScanCallback? = null

    /** Single Handler for all delayed operations to allow cancellation */
    private val handler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    /** Atomic reconnect counter to prevent infinite reconnection loops */
    private val reconnectCount = AtomicInteger(0)

    val discoveredPeers: Map<String, String>
        get() = scanResults.mapValues { it.value.name ?: "Unknown" }

    /**
     * Check required BLE permissions for Android 12+ (API 31+) and earlier versions.
     * FIX #1: Proactively check BLUETOOTH_SCAN / BLUETOOTH_CONNECT on Android 12+.
     */
    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Start scanning for Lumiv peers. */
    fun startScan(onFound: ((addr: String, name: String) -> Unit)? = null) {
        // FIX #1: Proactive permission check before any BLE operation
        if (!hasBlePermissions()) {
            Log.e(TAG, "BLE permissions not granted (BLUETOOTH_SCAN / BLUETOOTH_CONNECT)")
            return
        }
        if (btAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth disabled")
            return
        }
        val scanner = btAdapter.bluetoothLeScanner ?: return

        // FIX #9: Clear stale scan results before starting a new scan to prevent memory growth
        scanResults.clear()

        scanCb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val dev = result.device
                // FIX #4: ConcurrentHashMap handles thread-safe putIfAbsent logic
                if (scanResults.putIfAbsent(dev.address, dev) == null) {
                    Log.d(TAG, "Found: ${dev.name} (${dev.address})")
                    onFound?.invoke(dev.address, dev.name ?: "Unknown")
                }
            }
            override fun onScanFailed(code: Int) {
                Log.e(TAG, "Scan failed: $code")
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AIP_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER) // Power-saving for watch
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCb)
            // FIX #3: Save scan timeout runnable so we can cancel it later
            scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
            scanTimeoutRunnable = Runnable { stopScan() }
            scanTimeoutRunnable?.let { handler.postDelayed(it, SCAN_TIMEOUT_MS) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Scan permission denied")
        }
    }

    fun stopScan() {
        // FIX #3: Cancel pending scan timeout
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        try { btAdapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { Log.w(TAG, "stopScan failed: ${e.message}") }
    }

    /** Connect to a discovered device. */
    fun connect(address: String): Boolean {
        // FIX #1: Proactive permission check before connectGatt
        if (!hasBlePermissions()) {
            Log.e(TAG, "BLE connect permissions not granted")
            return false
        }
        val device = scanResults[address] ?: return false

        // FIX #9: Clear scan results after locating target device to free memory
        scanResults.clear()

        // FIX #3: Cancel any pending reconnect runnable before starting new connection
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to $address")
                        connected = true
                        // FIX #2: Reset reconnect counter on successful connection
                        reconnectCount.set(0)
                        gatt.requestMtu(MTU_SIZE)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected")
                        connected = false
                        // FIX #2: Enforce maximum reconnect retry limit to prevent battery drain
                        val retries = reconnectCount.incrementAndGet()
                        if (retries <= MAX_RECONNECT_RETRIES) {
                            Log.i(TAG, "Reconnect attempt $retries/$MAX_RECONNECT_RETRIES in 10s")
                            reconnectRunnable = Runnable { connect(address) }
                            reconnectRunnable?.let { handler.postDelayed(it, 10000) }
                        } else {
                            Log.w(TAG, "Max reconnect retries ($MAX_RECONNECT_RETRIES) reached, giving up")
                        }
                    }
                }
            }

            // FIX #6: Check MTU status before discovering services
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU negotiated: $mtu")
                    gatt.discoverServices()
                } else {
                    Log.w(TAG, "MTU request failed with status $status, proceeding with default MTU")
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val svc = gatt.getService(AIP_SERVICE_UUID) ?: return
                val tx = svc.getCharacteristic(TX_UUID)
                tx?.let {
                    gatt.setCharacteristicNotification(it, true)
                    it.getDescriptor(CCCD_UUID)?.let { d ->
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(d)
                    }
                }
                flushPending(gatt)
            }

            // FIX #7: Use non-deprecated onCharacteristicChanged for Android 13+ (API 33)
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (c.uuid == TX_UUID) {
                    Log.d(TAG, "RX: ${String(value)}")
                }
            }

            // Deprecated override kept for backward compatibility with older Android versions
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (c.uuid == TX_UUID) {
                    Log.d(TAG, "RX: ${String(c.value)}")
                }
            }
        }

        return try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun disconnect() {
        connected = false
        // FIX #3: Cancel all pending Handler callbacks to prevent leaks
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        reconnectCount.set(0)

        // FIX #5: Disconnect first, then close after a short delay to ensure proper cleanup
        try {
            gatt?.disconnect()
            handler.postDelayed({
                try {
                    gatt?.close()
                } catch (e: Exception) { Log.w(TAG, "gatt.close failed: ${e.message}") }
                gatt = null
            }, 100)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed: ${e.message}")
            try { gatt?.close() } catch (e2: Exception) { Log.w(TAG, "gatt.close fallback failed: ${e2.message}") }
            gatt = null
        }
    }

    override fun isConnected(): Boolean = connected

    override fun sendJson(json: String): Boolean {
        if (!connected || gatt == null) {
            // CRITICAL-FIX: Enforce max queue size to prevent OOM when BLE is
            // disconnected for extended periods. Drop oldest messages.
            while (pendingMessages.size >= MAX_PENDING_MESSAGES) {
                pendingMessages.poll()
            }
            pendingMessages.offer(json)
            return false
        }
        return try {
            val svc = gatt?.getService(AIP_SERVICE_UUID) ?: return false
            val rx = svc.getCharacteristic(RX_UUID) ?: return false
            rx.value = json.toByteArray()
            // FIX #8: Check writeCharacteristic return value (returns false if queue is full on Android 8+)
            gatt?.writeCharacteristic(rx) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    private fun flushPending(gatt: BluetoothGatt) {
        val svc = gatt.getService(AIP_SERVICE_UUID) ?: return
        val rx = svc.getCharacteristic(RX_UUID) ?: return
        while (pendingMessages.isNotEmpty()) {
            val msg = pendingMessages.poll() ?: break
            rx.value = msg.toByteArray()
            // FIX #8: Check writeCharacteristic return value; re-queue if queue is full
            if (!gatt.writeCharacteristic(rx)) {
                Log.w(TAG, "writeCharacteristic returned false (queue full), re-queueing message")
                pendingMessages.offer(msg)
                break
            }
        }
    }
}
