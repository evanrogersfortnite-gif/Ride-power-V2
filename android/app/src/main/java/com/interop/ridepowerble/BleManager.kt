package com.interop.ridepowerble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Shared, process-wide BLE connection to the RidePower controller (com.wx.dfw).
 * Both MainActivity (map) and SettingsActivity use this single connection.
 *
 * GATT: service FFE0, write FFE1, notify FFE2.
 * Frame: 0x55 + payload + checksum(sum of previous bytes & 0xFF).
 * Lengths: 5 = command, 13 = realtime, 20 = parameters.
 */
object BleManager {

    data class Realtime(
        val batteryV: Double, val throttleV: Double, val speedRpm: Int,
        val hall: Int, val learning: Boolean, val gear: Int, val fault: Int,
        val ctrlTemp: Int, val motorTemp: Int, val workState: Int = 0
    ) {
        // status flags share the work-state byte with the gear (as decoded by the official app)
        val reverse get() = workState and 0x10 != 0
        val antiTheft get() = workState and 0x20 != 0
        val cruise get() = workState and 0x40 != 0
        val brake get() = workState and 0x80 != 0
        /**
         * The "speed" field is NOT rpm: it's the time between motor pulses (smaller = faster,
         * 30000 = stopped). On this bike mph × value ≈ 3820 (learned from GPS; see [motorK]).
         */
        val motorMph: Double get() = if (speedRpm <= 0 || speedRpm >= 20000) 0.0 else motorK / speedRpm
        /** Fault names for each set bit of the fault byte (official app's decoding). */
        val faults: List<String> get() = FAULTS.filterIndexed { i, _ -> fault and (1 shl i) != 0 }
    }

    /** mph × speed-field constant (wheel size dependent); refined from GPS while riding. */
    @Volatile var motorK = 3820.0

    val FAULTS = listOf("Under voltage", "Throttle error", "Motor phase lost", "Controller MOSFET error",
        "Over current", "Current sensor or over temperature", "Motor stalled", "Hall sensor error")

    /** Gear from the low 4 bits of the work-state byte (official app's thresholds). */
    fun gearOf(ws: Int): Int = when (ws and 0x0F) { in 13..15 -> 5; in 10..12 -> 4; in 7..9 -> 3; in 4..6 -> 2; in 1..3 -> 1; else -> 0 }

    interface Listener {
        fun onStatus(status: String, connected: Boolean) {}
        fun onRealtime(d: Realtime) {}
        fun onParams(params: ByteArray) {}
        fun onLog(line: String) {}
    }

    private val SVC: UUID = uuid16("ffe0")
    private val CH_WRITE: UUID = uuid16("ffe1")
    private val CH_NOTIFY: UUID = uuid16("ffe2")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_REALTIME = 0x00
    const val CMD_READ_PARAMS = 0x01
    const val CMD_SELF_LEARN = 0x02

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    /** Rider's voltage correction (true volts / reported volts), set in Settings. */
    @Volatile var voltCal = 1.0
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var polling = false

    @Volatile var connected = false; private set
    @Volatile var status = "offline"; private set
    @Volatile var lastParams: ByteArray? = null; private set
    @Volatile var lastRealtime: Realtime? = null; private set

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()
    private val txQueue = ArrayDeque<ByteArray>()
    private var txBusy = false

    fun init(ctx: Context) {
        if (appCtx == null) {
            appCtx = ctx.applicationContext
            voltCal = appCtx!!.getSharedPreferences("app", Context.MODE_PRIVATE).getFloat("voltCal", 1f).toDouble()
            adapter = (appCtx!!.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onStatus(status, connected)
        lastRealtime?.let { l.onRealtime(it) }
        lastParams?.let { l.onParams(it) }
    }
    fun removeListener(l: Listener) = listeners.remove(l)

    fun isBluetoothOn() = adapter?.isEnabled == true

    // ---------- connect / scan ----------
    @SuppressLint("MissingPermission")
    fun connect() {
        if (connected || scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: run { setStatus("no bluetooth", false); return }
        scanning = true; setStatus("scanning", false)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed({
            if (scanning && gatt == null) { stopScan(); setStatus("no device", false) }
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() { try { gatt?.disconnect() } catch (_: Exception) {} }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            stopScan(); emitLog("found ${dev.name ?: dev.address}"); setStatus("connecting", false)
            gatt = dev.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; setStatus("scan failed", false) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { setStatus("discovering", false); g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) onDisconnected()
        }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(SVC) ?: run { setStatus("no FFE0", false); g.disconnect(); return }
            writeChar = svc.getCharacteristic(CH_WRITE)
            val notify = svc.getCharacteristic(CH_NOTIFY)
            if (writeChar == null || notify == null) { setStatus("no FFE1/FFE2", false); g.disconnect(); return }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd != null) writeDescriptorCompat(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else onReady()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { onReady() }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { txBusy = false; pump() }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { handleFrame(value) }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handleFrame(c.value ?: return)
        }
    }

    private fun onReady() {
        connected = true; setStatus("connected", true)
        startPolling()
        handler.postDelayed({ ensureParams(1) }, 400)
    }

    private fun ensureParams(attempt: Int) {
        if (lastParams != null || !connected) return
        read()
        if (attempt < 4) handler.postDelayed({ ensureParams(attempt + 1) }, 1300)
    }

    @SuppressLint("MissingPermission")
    private fun onDisconnected() {
        connected = false; polling = false
        synchronized(txQueue) { txQueue.clear(); txBusy = false }
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; writeChar = null
        setStatus("offline", false)
    }

    // ---------- tx ----------
    private fun enqueue(bytes: ByteArray, tag: String) {
        synchronized(txQueue) { txQueue.addLast(bytes) }
        emitLog("TX${if (tag.isNotEmpty()) " $tag" else ""}: ${hex(bytes)}")
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        val g = gatt ?: return
        val wc = writeChar ?: return
        val bytes: ByteArray
        synchronized(txQueue) {
            if (txBusy || txQueue.isEmpty()) return
            txBusy = true; bytes = txQueue.removeFirst()
        }
        val type = if ((wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writeCharCompat(g, wc, bytes, type)
        handler.postDelayed({ if (txBusy) { txBusy = false; pump() } }, 1500)
    }

    /** Live-data poll interval: fast while the app is on screen / navigating, slow in the background. */
    @Volatile var pollMs = 700L

    private fun startPolling() {
        if (polling) return
        polling = true
        val r = object : Runnable {
            override fun run() {
                if (!polling) return
                if (connected) enqueue(queryFrame(CMD_REALTIME), "")
                handler.postDelayed(this, pollMs)
            }
        }
        handler.postDelayed(r, 300)
    }

    // ---------- public commands ----------
    fun read() { if (connected) enqueue(queryFrame(CMD_READ_PARAMS), "read-params") }
    fun selfLearn() { if (connected) enqueue(queryFrame(CMD_SELF_LEARN), "self-learn") }
    fun writeParams(payload: IntArray) { if (connected) { enqueue(frame(payload), "write-params"); handler.postDelayed({ read() }, 500) } }

    // ---------- protocol ----------
    private fun checksum(b: ByteArray, len: Int): Int { var s = 0; for (i in 0 until len) s = (s + (b[i].toInt() and 0xff)) and 0xff; return s }
    private fun frame(payload: IntArray): ByteArray {
        val out = ByteArray(payload.size + 2); out[0] = 0x55
        for (i in payload.indices) out[i + 1] = (payload[i] and 0xff).toByte()
        out[out.size - 1] = checksum(out, out.size - 1).toByte(); return out
    }
    private fun queryFrame(cmd: Int) = frame(intArrayOf(cmd, 0x00, 0xA3))
    private fun verify(b: ByteArray) = b.size > 1 && (b[0].toInt() and 0xff) == 0x55 &&
            checksum(b, b.size - 1) == (b[b.size - 1].toInt() and 0xff)

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, h: Int, l: Int) = (u(b, h) shl 8) or u(b, l)

    private fun handleFrame(b: ByteArray) {
        emitLog("RX: ${hex(b)}")
        if (!verify(b)) return
        when (b.size) {
            13 -> {
                val d = Realtime(
                    batteryV = u16(b, 1, 2) / 7.6222222 * voltCal,
                    throttleV = u16(b, 3, 4) * 5.0 / 1024.0,
                    speedRpm = u16(b, 5, 6),
                    hall = u(b, 7) and 0x07,
                    learning = ((u(b, 7) shr 7) and 1) == 1,
                    gear = gearOf(u(b, 8)), fault = u(b, 9),
                    ctrlTemp = u(b, 10), motorTemp = u(b, 11), workState = u(b, 8)
                )
                lastRealtime = d
                handler.post { for (l in listeners) l.onRealtime(d) }
            }
            20 -> {
                val p = b.copyOf(); lastParams = p
                emitLog("parameters loaded")
                handler.post { for (l in listeners) l.onParams(p) }
            }
        }
    }

    // ---------- helpers ----------
    private fun setStatus(s: String, conn: Boolean) {
        status = s; connected = conn
        handler.post { for (l in listeners) l.onStatus(s, conn) }
    }
    private fun emitLog(line: String) { handler.post { for (l in listeners) l.onLog(line) } }
    private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    private fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02x", it.toInt() and 0xff) }

    @SuppressLint("MissingPermission")
    private fun writeCharCompat(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeCharacteristic(c, value, type)
        else { @Suppress("DEPRECATION") run { c.writeType = type; c.value = value; g.writeCharacteristic(c) } }
    }
    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(d, value)
        else { @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) } }
    }
}
