package com.folio.notes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Optional direct-BLE haptics for the OnePlus Pencil Pro.
 *
 * Kept deliberately separate from the drawing engine: a pulse is fire-and-forget, every failure is
 * silent, and drawing behaves identically when Bluetooth is off, the pen is out of range, the
 * permission was never granted, or the device is not a OnePlus at all.
 *
 * Android 12+ only, which keeps BLE scanning free of the legacy location requirement. The
 * connection is opened when the editor needs it and dropped on [stop]; it is never held app-wide.
 *
 * The pen is looked for by remembered address, then among the phone's paired devices, and only then
 * by scanning. The middle step matters because a bonded pen that the system pen service is holding
 * stops advertising, so a scan can never see it and the buzz would stay silent however often the
 * double tap fires.
 *
 * Not proven: whether this coexists reliably with the system IPE connection, and whether the pulse
 * reproduces anything close to writing feedback. It is a single discrete confirmation buzz, which
 * is why it is wired to the Pencil's own double tap rather than to stroke samples or to every
 * manual tool change.
 */
@SuppressLint("MissingPermission", "InlinedApi") // hasPermission() gates every entry point; lint can't follow the helper.
// InlinedApi: the Bluetooth permission names are compile-time constants and only read on Android 12+.
class PenHapticsManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var address: String? = null
    private var scanning = false
    private var writing = false
    private var queued = false
    private var running = false
    private var attempts = 0
    /** The bonded pen is tried once per session; after that a plain scan is the better bet. */
    private var triedBonded = false

    /** Begins watching for the pen. Safe to call repeatedly. */
    fun start() {
        if (running || !isSupported(context) || !hasPermission()) return
        running = true
        open()
    }

    fun stop() {
        running = false
        queued = false
        triedBonded = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
    }

    /**
     * Requests one pulse. Cheap and safe to call when nothing is connected: the request is coalesced
     * and delivered as soon as a link exists, rather than queueing a burst of pulses.
     */
    fun pulse() {
        if (!running) return
        handler.post {
            queued = true
            if (writer != null) flush() else { attempts = 0; open() }
        }
    }

    private fun open() {
        if (!running || !hasPermission() || gatt != null || scanning) return
        val adapter = bluetooth() ?: return
        if (!adapter.isEnabled) return
        // A known address connects without scanning; if it has gone stale the timeout falls back.
        val remembered = address?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        if (remembered != null) { connectAndArm(remembered); return }
        // A pen the phone has already paired, and that the system pen service is holding, stops
        // advertising, so no scan can ever see it. Try the paired device once per session before
        // falling back to searching, otherwise a bonded pen stays invisible at every double tap.
        val bonded = if (triedBonded) null else bondedPen(adapter)
        if (bonded != null) {
            triedBonded = true
            address = bonded.address
            connectAndArm(bonded)
            return
        }
        scan(adapter)
    }

    private fun connectAndArm(device: BluetoothDevice) {
        connect(device, autoConnect = true)
        if (gatt != null) handler.postDelayed(staleTimeout, CONNECT_TIMEOUT_MS)
    }

    /** A paired device that names itself as a pencil, since a bonded pen often stops advertising. */
    private fun bondedPen(adapter: BluetoothAdapter): BluetoothDevice? =
        runCatching { adapter.bondedDevices.firstOrNull { isPenName(it.name) } }.getOrNull()

    private fun scan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        scanning = true
        // Unfiltered: whether the pen advertises the service UUID is not established, so accept any
        // result that identifies itself as the pen and let matching decide.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { scanner.startScan(emptyList(), settings, scanCallback) }.isSuccess
        if (!started) { scanning = false; return }
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        val scanner = bluetooth()?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        if (!running || gatt != null) return
        gatt = runCatching { device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
    }

    private fun closeGatt() {
        val connection = gatt ?: return
        gatt = null; writer = null; writing = false
        handler.removeCallbacks(writeTimeout)
        runCatching { connection.disconnect() }
        runCatching { connection.close() }
    }

    /** Sends at most one pulse at a time: GATT allows a single outstanding operation per connection. */
    private fun flush() {
        if (!running || writing || !queued) return
        val connection = gatt ?: return
        val characteristic = writer ?: return
        queued = false
        writing = true
        // A no-response write may never report back, and one missing callback would otherwise leave
        // the queue busy and silently swallow every later pulse.
        handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
        if (!write(connection, characteristic)) { handler.removeCallbacks(writeTimeout); writing = false }
    }

    private fun write(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val bytes = PenHaptics.functionPulse()
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = type
                characteristic.value = bytes
                connection.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
    }

    private fun bluetooth(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isPen(result)) return
            val device = result.device ?: return
            stopScan()
            address = device.address
            connect(device, autoConnect = false)
        }

        override fun onScanFailed(errorCode: Int) { scanning = false }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { connection.discoverServices() }
                } else {
                    writer = null; writing = false
                    closeGatt()
                    retry()
                }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            handler.post {
                val characteristic = runCatching {
                    connection.getService(PenHaptics.SERVICE)?.getCharacteristic(PenHaptics.WRITE)
                }.getOrNull()
                if (characteristic == null) {
                    // Connected to something that is not the pen, or the cache is stale: start over.
                    address = null
                    closeGatt()
                    retry()
                } else {
                    attempts = 0
                    writer = characteristic
                    handler.removeCallbacks(staleTimeout)
                    flush()
                }
            }
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post { handler.removeCallbacks(writeTimeout); writing = false; flush() }
        }
    }

    /** Reconnection is bounded so an absent pen cannot keep scanning in the background. */
    private fun retry() {
        if (!running || attempts >= MAX_ATTEMPTS) return
        attempts++
        handler.postDelayed(reconnect, RECONNECT_MS)
    }

    private val scanTimeout = Runnable { stopScan() }
    private val reconnect = Runnable { open() }
    /** The cached address stopped working, so forget it and look for the pen again. */
    private val staleTimeout = Runnable { if (running && writer == null) { address = null; closeGatt() } }
    /** Clears a write that never reported back, so later pulses can still go out. */
    private val writeTimeout = Runnable { if (writing) { writing = false; flush() } }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val RECONNECT_MS = 5_000L
        private const val MAX_ATTEMPTS = 3

        /** Android 12+ and a Bluetooth LE radio; older releases would need the location permission. */
        fun isSupported(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        /** A device is the pen if it advertises our service, or names itself as a pencil. */
        private fun isPen(result: ScanResult): Boolean {
            val advertised = result.scanRecord?.serviceUuids?.contains(ParcelUuid(PenHaptics.SERVICE)) == true
            val advertisedName = result.scanRecord?.deviceName
            val deviceName = runCatching { result.device?.name }.getOrNull()
            return advertised || isPenName(advertisedName) || isPenName(deviceName)
        }

        /** Both vendors' pens call themselves a pencil; some firmwares say stylus instead. */
        private fun isPenName(name: String?): Boolean {
            val lower = name?.lowercase(Locale.ROOT) ?: return false
            return lower.contains("pencil") || lower.contains("stylus")
        }
    }
}
