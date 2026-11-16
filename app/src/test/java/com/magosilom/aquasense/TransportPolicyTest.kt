package com.magosilom.aquasense

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPolicyTest {
    @Test fun wifiFreshStaysWifi() {
        assertEquals(Transport.WIFI, chooseTransport(Transport.WIFI, lastWifiOkMs = 1000L, nowMs = 10_999L, bleAvailable = true))
        assertEquals(Transport.WIFI, chooseTransport(Transport.BLE, lastWifiOkMs = 1000L, nowMs = 11_000L, bleAvailable = true))
    }

    @Test fun wifiStaleGoesBle() {
        assertEquals(Transport.BLE, chooseTransport(Transport.WIFI, lastWifiOkMs = 1000L, nowMs = 11_001L, bleAvailable = true))
    }

    @Test fun bleUnavailableStaysWifi() {
        assertEquals(Transport.WIFI, chooseTransport(Transport.WIFI, lastWifiOkMs = 1000L, nowMs = 50_000L, bleAvailable = false))
        assertEquals(Transport.WIFI, chooseTransport(Transport.BLE, lastWifiOkMs = null, nowMs = 50_000L, bleAvailable = false))
    }

    @Test fun neverConnectedGoesBleAfter10s() {
        assertEquals(Transport.WIFI, chooseTransport(Transport.WIFI, lastWifiOkMs = 0L, nowMs = 10_000L, bleAvailable = true))
        assertEquals(Transport.BLE, chooseTransport(Transport.WIFI, lastWifiOkMs = 0L, nowMs = 10_001L, bleAvailable = true))
        assertEquals(Transport.BLE, chooseTransport(Transport.WIFI, lastWifiOkMs = null, nowMs = 10_001L, bleAvailable = true))
    }
}
