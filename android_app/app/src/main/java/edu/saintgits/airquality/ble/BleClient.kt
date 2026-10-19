package edu.saintgits.airquality.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** BLE GATT protocol shared with firmware/AirQualityMonitor. */
object BleProtocol {
    const val DEVICE_NAME = "AirQ-Monitor"
    val SERVICE: UUID = UUID.fromString("7a3f0001-4b5e-4c8a-9d2e-5f6a7b8c9d00")
    val TELEMETRY: UUID = UUID.fromString("7a3f0002-4b5e-4c8a-9d2e-5f6a7b8c9d00")
    val COMMAND: UUID = UUID.fromString("7a3f0003-4b5e-4c8a-9d2e-5f6a7b8c9d00")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MTU = 247

    fun statusCommand(code: Char) = "AI:$code"
    const val RECALIBRATE = "RECAL"
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object BluetoothOff : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val name: String, val address: String) : ConnectionState
    data class Reconnecting(val reason: String, val attempt: Int) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

/**
 * Connects to the ESP32 sensor node and streams telemetry frames.
 *
 * Flow: scan (filtered by service UUID) → connect → request MTU 247 → discover services →
 * enable notifications → stream. On link loss it reconnects with exponential back-off for
 * as long as the user wants to be connected. Permissions are checked by the caller.
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val onFrame: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var wantConnected = false
    private var attempt = 0
    private var scanning = false
    private var ready = false

    val lastDeviceAddress: String? get() = prefs.getString(KEY_ADDRESS, null)

    fun connect() {
        wantConnected = true
        attempt = 0
        main.post { startConnection() }
    }

    fun disconnect() {
        wantConnected = false
        main.removeCallbacksAndMessages(null)
        main.post {
            stopScan()
            closeGatt()
            _state.value = ConnectionState.Idle
        }
    }

    /** Forgets the remembered device so the next connect scans again. */
    fun forgetDevice() {
        prefs.edit().remove(KEY_ADDRESS).apply()
    }

    private fun startConnection() {
        if (!wantConnected) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.value = ConnectionState.BluetoothOff
            scheduleRetry("Bluetooth is off")
            return
        }
        val known = lastDeviceAddress
        // After a failed direct attempt, fall back to scanning (the board may have changed).
        if (known != null && attempt % 2 == 0) {
            connectTo(a.getRemoteDevice(known))
        } else {
            startScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            connectTo(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scheduleRetry("Scan failed ($errorCode)")
        }
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return scheduleRetry("Scanner unavailable")
        _state.value = ConnectionState.Scanning
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            _state.value = ConnectionState.Failed("Bluetooth permission missing")
            return
        }
        main.postDelayed({
            if (scanning) {
                stopScan()
                scheduleRetry("Sensor not found. Is the board powered on and nearby?")
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        closeGatt()
        _state.value = ConnectionState.Connecting(device.address)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // Guard against stacks that never call back.
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (!ready) {
            closeGatt()
            scheduleRetry("Connection timed out")
        }
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        ready = false
        commandChar = null
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
    }

    private fun scheduleRetry(reason: String) {
        if (!wantConnected) return
        attempt++
        val delay = minOf(MAX_BACKOFF_MS, 1_000L shl minOf(attempt, 5))
        _state.value = if (_state.value is ConnectionState.BluetoothOff) ConnectionState.BluetoothOff
        else ConnectionState.Reconnecting(reason, attempt)
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, delay)
    }

    private val retryRunnable = Runnable { startConnection() }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    if (!g.requestMtu(BleProtocol.MTU)) g.discoverServices()
                } else {
                    val wasReady = ready
                    closeGatt()
                    scheduleRetry(if (wasReady) "Connection lost" else "Could not connect (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g == gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val service = g.getService(BleProtocol.SERVICE)
                val telemetry = service?.getCharacteristic(BleProtocol.TELEMETRY)
                if (telemetry == null) {
                    closeGatt()
                    forgetDevice()
                    scheduleRetry("Device is not an AirQ sensor")
                    return@post
                }
                commandChar = service.getCharacteristic(BleProtocol.COMMAND)
                g.setCharacteristicNotification(telemetry, true)
                val cccd = telemetry.getDescriptor(BleProtocol.CCCD)
                writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt()
                    scheduleRetry("Could not enable notifications")
                    return@post
                }
                ready = true
                attempt = 0
                main.removeCallbacks(connectTimeout)
                prefs.edit().putString(KEY_ADDRESS, g.device.address).apply()
                _state.value = ConnectionState.Connected(g.device.name ?: BleProtocol.DEVICE_NAME, g.device.address)
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == BleProtocol.TELEMETRY) onFrame(String(value, Charsets.UTF_8))
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            if (c.uuid == BleProtocol.TELEMETRY) onFrame(String(value, Charsets.UTF_8))
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    /** Sends a command (write without response). Returns false if not connected. */
    @Suppress("DEPRECATION")
    fun send(command: String): Boolean {
        val g = gatt ?: return false
        val c = commandChar ?: return false
        if (!ready) return false
        val bytes = command.toByteArray(Charsets.US_ASCII)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = bytes
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Command write failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "BleClient"
        private const val KEY_ADDRESS = "device_address"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
