package com.magosilom.aquasense

enum class Transport { WIFI, BLE }

private const val WIFI_FALLBACK_MS = 10_000L

/**
 * Chooses between Wi-Fi and BLE for live readings:
 * - Uses [Transport.WIFI] whenever BLE is unavailable or a Wi-Fi reading succeeded within 10 s.
 * - Switches to [Transport.BLE] after > 10 s without a successful Wi-Fi reading.
 */
fun chooseTransport(
    current: Transport,
    lastWifiOkMs: Long?,
    nowMs: Long,
    bleAvailable: Boolean,
): Transport {
    if (!bleAvailable) return Transport.WIFI
    if (lastWifiOkMs != null && nowMs - lastWifiOkMs <= WIFI_FALLBACK_MS) return Transport.WIFI
    return Transport.BLE
}
