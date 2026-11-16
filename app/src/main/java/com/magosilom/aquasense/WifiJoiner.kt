package com.magosilom.aquasense

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val AQUASENSE_SSID = "AquaSense-ESP32"
const val AQUASENSE_PASS = "aquasense123"
const val ESP_BASE = "http://192.168.4.1"

/**
 * Joins the AquaSense ESP32 access point by itself (API 29+) without binding the whole
 * process to it, so the phone keeps mobile data / other Wi-Fi for the internet.
 * Only [EspClient] uses [network]. On API < 29 [network] stays null and callers fall
 * back to any Wi-Fi network (v2 behaviour).
 */
class WifiJoiner(ctx: Context) {
    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (!supported || callback != null) return
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(AQUASENSE_SSID)
            .setWpa2Passphrase(AQUASENSE_PASS)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _network.value = network }
            override fun onLost(network: Network) { _network.value = null }
            override fun onUnavailable() { _network.value = null }
        }
        callback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: RuntimeException) {
            callback = null
            _network.value = null
        }
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        try { cm.unregisterNetworkCallback(cb) } catch (_: RuntimeException) {}
        _network.value = null
    }
}
