package com.magosilom.aquasense

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val AQUASENSE_SSID = "AquaSense-ESP32"
const val AQUASENSE_PASS = "aquasense123"
const val ESP_BASE = "http://192.168.4.1"

data class ScannedWifi(
    val ssid: String,
    val rssi: Int,
    val secured: Boolean,
    val isAquaSense: Boolean = ssid.contains("AquaSense", ignoreCase = true) ||
        ssid.contains("ESP32", ignoreCase = true),
)

/**
 * Joins the AquaSense ESP32 access point by itself (API 29+) without binding the whole
 * process to it, so the phone keeps mobile data / other Wi-Fi for the internet.
 * Also supports scanning nearby Wi-Fi networks and connecting to a chosen SSID + password.
 */
@SuppressLint("MissingPermission")
class WifiJoiner(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _networks = MutableStateFlow<List<ScannedWifi>>(emptyList())
    val networks: StateFlow<List<ScannedWifi>> = _networks

    private val _connectedSsid = MutableStateFlow<String?>(null)
    val connectedSsid: StateFlow<String?> = _connectedSsid

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var scanReceiver: BroadcastReceiver? = null
    private var targetSsid: String = AQUASENSE_SSID
    private var targetPass: String? = AQUASENSE_PASS

    /** Returns the bound specifier network, or any active Wi-Fi network if joined via system Wi-Fi. */
    fun activeNetwork(): Network? {
        _network.value?.let { return it }
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun start() {
        if (!supported || callback != null) return
        requestSpecifierNetwork(targetSsid, targetPass)
    }

    @Synchronized
    fun connectTo(ssid: String, pass: String?) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) return
        targetSsid = cleanSsid
        targetPass = pass?.takeIf { it.isNotEmpty() }
        stop()
        if (supported) {
            try {
                val sug = WifiNetworkSuggestion.Builder().setSsid(cleanSsid).apply {
                    if (!targetPass.isNullOrEmpty()) setWpa2Passphrase(targetPass!!)
                }.build()
                wifiManager.addNetworkSuggestions(listOf(sug))
            } catch (_: Exception) {}
            requestSpecifierNetwork(cleanSsid, targetPass)
        } else {
            @Suppress("DEPRECATION")
            try {
                val conf = WifiConfiguration().apply {
                    SSID = "\"$cleanSsid\""
                    if (targetPass.isNullOrEmpty()) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    } else {
                        preSharedKey = "\"$targetPass\""
                    }
                }
                val netId = wifiManager.addNetwork(conf)
                wifiManager.disconnect()
                if (wifiManager.enableNetwork(netId, true) && wifiManager.reconnect()) {
                    _connectedSsid.value = cleanSsid
                }
            } catch (_: Exception) {}
        }
    }

    private fun requestSpecifierNetwork(ssid: String, pass: String?) {
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (!pass.isNullOrEmpty() && pass.length >= 8) {
            specBuilder.setWpa2Passphrase(pass)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _network.value = network
                _connectedSsid.value = ssid
            }

            override fun onLost(network: Network) {
                _network.compareAndSet(network, null)
                if (_connectedSsid.value == ssid) _connectedSsid.value = null
            }

            override fun onUnavailable() {
                synchronized(this@WifiJoiner) { if (callback === this) callback = null }
                try { cm.unregisterNetworkCallback(this) } catch (_: RuntimeException) {}
                _network.value = null
                if (_connectedSsid.value == ssid) _connectedSsid.value = null
            }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: RuntimeException) {
            callback = null
            _network.value = null
        }
    }

    fun scan() {
        val hasPerm = ContextCompat.checkSelfPermission(
            appCtx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return
        _scanning.value = true
        updateScanResults()
        unregisterScanReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateScanResults()
                _scanning.value = false
                unregisterScanReceiver()
            }
        }
        scanReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                appCtx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            }
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (!started) {
                updateScanResults()
                _scanning.value = false
                unregisterScanReceiver()
            } else {
                scope.launch {
                    delay(6000)
                    if (_scanning.value) {
                        updateScanResults()
                        _scanning.value = false
                        unregisterScanReceiver()
                    }
                }
            }
        } catch (_: Exception) {
            _scanning.value = false
            unregisterScanReceiver()
        }
    }

    private fun updateScanResults() {
        try {
            val results = wifiManager.scanResults ?: emptyList()
            val bySsid = LinkedHashMap<String, ScannedWifi>()
            for (r in results) {
                @Suppress("DEPRECATION")
                val ssid = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    r.wifiSsid?.toString()?.removePrefix("\"")?.removeSuffix("\"")
                } else {
                    r.SSID
                })?.trim().orEmpty()
                if (ssid.isEmpty()) continue
                val caps = r.capabilities.orEmpty()
                val secured = caps.contains("WPA", ignoreCase = true) ||
                    caps.contains("WEP", ignoreCase = true) ||
                    caps.contains("SAE", ignoreCase = true)
                val item = ScannedWifi(ssid = ssid, rssi = r.level, secured = secured)
                val prev = bySsid[ssid]
                if (prev == null || item.rssi > prev.rssi) bySsid[ssid] = item
            }
            _networks.value = bySsid.values.sortedWith(
                compareByDescending<ScannedWifi> { it.isAquaSense }.thenByDescending { it.rssi }
            )
            @Suppress("DEPRECATION")
            val curSsid = wifiManager.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
            if (!curSsid.isNullOrBlank() && curSsid != "<unknown ssid>") {
                _connectedSsid.value = curSsid
            }
        } catch (_: Exception) {}
    }

    private fun unregisterScanReceiver() {
        val r = scanReceiver ?: return
        scanReceiver = null
        try { appCtx.unregisterReceiver(r) } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        unregisterScanReceiver()
        val cb = callback ?: return
        callback = null
        try { cm.unregisterNetworkCallback(cb) } catch (_: RuntimeException) {}
        _network.value = null
        _connectedSsid.value = null
    }
}
