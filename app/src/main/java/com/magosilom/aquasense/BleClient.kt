package com.magosilom.aquasense

import android.Manifest
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

const val BLE_SERVICE = "6e1a0001-6a3b-4c2d-9f10-a9a5e0c0a5e1"
const val BLE_READING = "6e1a0002-6a3b-4c2d-9f10-a9a5e0c0a5e1"
const val BLE_CAL = "6e1a0003-6a3b-4c2d-9f10-a9a5e0c0a5e1"
const val BLE_STATION = "6e1a0004-6a3b-4c2d-9f10-a9a5e0c0a5e1"
private const val BLE_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

data class ScannedBtDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val bondState: Int = BluetoothDevice.BOND_NONE,
) {
    val isPaired: Boolean get() = bondState == BluetoothDevice.BOND_BONDED
    val isPairing: Boolean get() = bondState == BluetoothDevice.BOND_BONDING
    val isAquaSense: Boolean
        get() = name.contains("AquaSense", ignoreCase = true) ||
            name.contains("ESP32", ignoreCase = true)
}

fun blePermissionsForRuntime(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * BLE GATT client + Bluetooth scanner/pairing controller for `AquaSense-ESP32`.
 * Supports automatic background connection as well as manual device scanning, pairing (bonding),
 * connecting, live readings (`BLE_READING`), calibration (`BLE_CAL`), and station rename (`BLE_STATION`).
 */
@SuppressLint("MissingPermission")
class BleClient(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val btManager = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serviceUuid: UUID = UUID.fromString(BLE_SERVICE)
    private val readingUuid: UUID = UUID.fromString(BLE_READING)
    private val calUuid: UUID = UUID.fromString(BLE_CAL)
    private val stationUuid: UUID = UUID.fromString(BLE_STATION)
    private val cccdUuid: UUID = UUID.fromString(BLE_CCCD)

    private val _latest = MutableStateFlow<Pair<LiveReading, Long>?>(null)
    val latest: StateFlow<Pair<LiveReading, Long>?> = _latest

    private val _latestCal = MutableStateFlow<CalStatus?>(null)
    val latestCal: StateFlow<CalStatus?> = _latestCal

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _connectedDevice = MutableStateFlow<ScannedBtDevice?>(null)
    val connectedDevice: StateFlow<ScannedBtDevice?> = _connectedDevice

    private val _scanningDevices = MutableStateFlow(false)
    val scanningDevices: StateFlow<Boolean> = _scanningDevices

    private val _devices = MutableStateFlow<List<ScannedBtDevice>>(emptyList())
    val devices: StateFlow<List<ScannedBtDevice>> = _devices

    val supported: Boolean
        get() = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null

    private val discoveredMap = LinkedHashMap<String, ScannedBtDevice>()
    private var receiverRegistered = false
    private var discoveryJob: Job? = null

    @Volatile private var running = false
    @Volatile var manualConnection = false
        private set
    @Volatile private var scanning = false
    @Volatile private var gatt: BluetoothGatt? = null
    private val gattMutex = Mutex()
    @Volatile private var pendingWriteDone: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingReadDone: CompletableDeferred<Boolean>? = null
    private var retryJob: Job? = null
    private var failures = 0

    fun isAvailable(): Boolean = supported && hasPermissions() && (try { adapter?.isEnabled == true } catch (_: Exception) { false })

    fun isBluetoothEnabled(): Boolean = try { adapter?.isEnabled == true } catch (_: Exception) { false }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun safeName(dev: BluetoothDevice): String {
        val n = try { dev.name } catch (_: Exception) { null }
        return if (!n.isNullOrBlank()) n.trim() else "BLE (${dev.address.takeLast(5)})"
    }

    @Synchronized
    private fun upsertDevice(dev: BluetoothDevice, rssi: Int?) {
        val addr = dev.address ?: return
        val prev = discoveredMap[addr]
        val name = try { dev.name?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
            ?: prev?.name ?: "Bluetooth (${addr.takeLast(5)})"
        val bond = try { dev.bondState } catch (_: Exception) { prev?.bondState ?: BluetoothDevice.BOND_NONE }
        val item = ScannedBtDevice(name = name, address = addr, rssi = rssi ?: prev?.rssi, bondState = bond)
        discoveredMap[addr] = item
        if (_connectedDevice.value?.address == addr) _connectedDevice.value = item
        _devices.value = discoveredMap.values.sortedWith(
            compareByDescending<ScannedBtDevice> { it.isAquaSense }
                .thenByDescending { it.isPaired }
                .thenByDescending { it.rssi ?: -120 }
        )
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .takeIf { it != Short.MIN_VALUE }?.toInt()
                    if (dev != null) upsertDevice(dev, rssi)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    if (dev != null) upsertDevice(dev, null)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _scanningDevices.value = false
                }
            }
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(btReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try { appCtx.unregisterReceiver(btReceiver) } catch (_: Exception) {}
    }

    fun clearCalStatus() {
        _latestCal.value = null
    }

    fun refreshPairedDevices() {
        if (!hasPermissions()) return
        try {
            adapter?.bondedDevices?.forEach { upsertDevice(it, null) }
        } catch (_: Exception) {}
    }

    /** Scans for nearby Bluetooth (BLE + Classic) devices for the Settings UI. */
    fun scanDevices() {
        if (!isAvailable()) return
        ensureReceiver()
        refreshPairedDevices()
        _scanningDevices.value = true
        try {
            if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
            adapter?.startDiscovery()
        } catch (_: Exception) {}
        try {
            val scanner = adapter?.bluetoothLeScanner
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner?.startScan(null, settings, discoveryBleCallback)
        } catch (_: Exception) {}
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            delay(10_000)
            stopDeviceScan()
        }
    }

    fun stopDeviceScan() {
        discoveryJob?.cancel()
        discoveryJob = null
        try {
            if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        } catch (_: Exception) {}
        try {
            adapter?.bluetoothLeScanner?.stopScan(discoveryBleCallback)
        } catch (_: Exception) {}
        _scanningDevices.value = false
    }

    private val discoveryBleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val d = result?.device ?: return
            upsertDevice(d, result.rssi)
        }
    }

    fun pairDevice(address: String) {
        if (!hasPermissions()) return
        ensureReceiver()
        stopDeviceScan()
        try {
            val dev = adapter?.getRemoteDevice(address) ?: return
            if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                dev.createBond()
                upsertDevice(dev, null)
            }
        } catch (_: Exception) {}
    }

    fun unpairDevice(address: String) {
        if (!hasPermissions()) return
        try {
            val dev = adapter?.getRemoteDevice(address) ?: return
            dev.javaClass.getMethod("removeBond").invoke(dev)
            discoveredMap[address]?.let {
                discoveredMap[address] = it.copy(bondState = BluetoothDevice.BOND_NONE)
                _devices.value = discoveredMap.values.toList()
            }
        } catch (_: Exception) {}
    }

    fun connectToDevice(address: String) {
        if (!isAvailable()) return
        stopDeviceScan()
        stopScanInternal()
        val prev = gatt
        gatt = null
        try { prev?.disconnect(); prev?.close() } catch (_: Exception) {}
        try {
            val dev = adapter?.getRemoteDevice(address) ?: return
            manualConnection = true
            running = true
            connectGatt(dev)
        } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            upsertDevice(device, result.rssi)
            if (!running || gatt != null) return
            stopScanInternal()
            connectGatt(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scheduleRetry()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                failures = 0
                g.device?.let { upsertDevice(it, null) }
                try {
                    if (!g.requestMtu(512)) g.discoverServices()
                } catch (_: Exception) {
                    try { g.discoverServices() } catch (_: Exception) {}
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connected.value = false
                _connectedDevice.value = null
                pendingWriteDone?.complete(false)
                pendingReadDone?.complete(false)
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                if (running) scheduleRetry()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            try { g.discoverServices() } catch (_: Exception) {}
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(serviceUuid) else null
            val ch = svc?.getCharacteristic(readingUuid)
            if (svc == null || ch == null) {
                // Not an AquaSense device: disconnect and release manual lock
                manualConnection = false
                _connected.value = false
                _connectedDevice.value = null
                if (gatt === g) gatt = null
                try { g.disconnect(); g.close() } catch (_: Exception) {}
                return
            }
            _connected.value = true
            val dev = g.device
            if (dev != null) {
                upsertDevice(dev, null)
                _connectedDevice.value = ScannedBtDevice(
                    name = safeName(dev),
                    address = dev.address,
                    bondState = try { dev.bondState } catch (_: Exception) { BluetoothDevice.BOND_NONE },
                )
            }
            enableCharacteristicNotify(g, ch)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ch = descriptor.characteristic ?: return
            if (ch.uuid == readingUuid) {
                val calCh = g.getService(serviceUuid)?.getCharacteristic(calUuid)
                if (calCh != null && enableCharacteristicNotify(g, calCh)) {
                    return
                }
                try {
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(ch)
                } catch (_: Exception) {}
            } else if (ch.uuid == calUuid) {
                val readCh = g.getService(serviceUuid)?.getCharacteristic(readingUuid) ?: return
                try {
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(readCh)
                } catch (_: Exception) {}
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                handleCharacteristicBytes(characteristic.uuid, characteristic.value)
            }
            pendingReadDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristicBytes(characteristic.uuid, value)
            pendingReadDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicBytes(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicBytes(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWriteDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun enableCharacteristicNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        return try {
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(cccdUuid)
            if (desc != null) {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
                if (!ok) {
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(ch)
                }
                ok
            } else {
                @Suppress("DEPRECATION")
                g.readCharacteristic(ch)
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun handleCharacteristicBytes(uuid: UUID, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val text = bytes.toString(Charsets.UTF_8)
        try {
            when (uuid) {
                readingUuid -> {
                    val r = parseReadings(text)
                    _latest.value = r to System.currentTimeMillis()
                }
                calUuid -> {
                    _latestCal.value = parseCal(text)
                }
            }
        } catch (_: Exception) {}
    }

    /** Actively reads [BLE_READING] over GATT (uses ATT Long Read if MTU is small). */
    suspend fun readReadings(): LiveReading? {
        val g = gatt ?: return null
        val ch = g.getService(serviceUuid)?.getCharacteristic(readingUuid) ?: return null
        if (!readCharacteristicSuspend(g, ch)) return null
        return _latest.value?.first
    }

    /** Actively reads [BLE_CAL] over GATT (uses ATT Long Read if MTU is small). */
    suspend fun readCal(): CalStatus? {
        val g = gatt ?: return null
        val ch = g.getService(serviceUuid)?.getCharacteristic(calUuid) ?: return null
        if (!readCharacteristicSuspend(g, ch)) return _latestCal.value
        return _latestCal.value
    }

    /** Starts calibration over BLE GATT by writing `"START"` to [BLE_CAL]. */
    suspend fun calStart(): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(serviceUuid)?.getCharacteristic(calUuid) ?: return false
        return writeCharacteristicSuspend(g, ch, "START".toByteArray(Charsets.UTF_8))
    }

    /** Renames the station over BLE GATT by writing [name] to [BLE_STATION]. */
    suspend fun setStation(name: String): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(serviceUuid)?.getCharacteristic(stationUuid) ?: return false
        return writeCharacteristicSuspend(g, ch, name.toByteArray(Charsets.UTF_8))
    }

    private suspend fun readCharacteristicSuspend(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
    ): Boolean = gattMutex.withLock {
        val def = CompletableDeferred<Boolean>()
        pendingReadDone = def
        val started = try {
            @Suppress("DEPRECATION")
            g.readCharacteristic(ch)
        } catch (_: Exception) {
            false
        }
        if (!started) {
            pendingReadDone = null
            return@withLock false
        }
        val res = withTimeoutOrNull(2500L) { def.await() } ?: false
        pendingReadDone = null
        res
    }

    private suspend fun writeCharacteristicSuspend(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean = gattMutex.withLock {
        val def = CompletableDeferred<Boolean>()
        pendingWriteDone = def
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (_: Exception) {
            false
        }
        if (!started) {
            pendingWriteDone = null
            return@withLock false
        }
        val res = withTimeoutOrNull(3000L) { def.await() } ?: false
        pendingWriteDone = null
        res
    }

    @Synchronized
    fun start() {
        if (running) return
        if (!isAvailable()) return
        running = true
        failures = 0
        startScanInternal()
    }

    @Synchronized
    fun stopIfAuto() {
        if (!manualConnection) stop()
    }

    @Synchronized
    fun stop() {
        manualConnection = false
        running = false
        retryJob?.cancel()
        retryJob = null
        stopScanInternal()
        unregisterReceiver()
        val g = gatt
        gatt = null
        _connected.value = false
        _connectedDevice.value = null
        try {
            g?.disconnect()
            g?.close()
        } catch (_: Exception) {}
    }

    private fun startScanInternal() {
        if (!running || scanning || gatt != null || !isAvailable()) return
        val scanner = try { adapter?.bluetoothLeScanner } catch (_: Exception) { null } ?: return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanning = true
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (_: Exception) {
            scanning = false
            scheduleRetry()
        }
    }

    private fun stopScanInternal() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (!running) return
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appCtx, false, gattCallback)
            }
        } catch (_: Exception) {
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!running) return
        failures++
        val waitMs = backoffMs(failures)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(waitMs)
            if (running && gatt == null) startScanInternal()
        }
    }
}
