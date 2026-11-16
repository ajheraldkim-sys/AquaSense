package com.magosilom.aquasense

import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    private fun v3(boot: Int = 7, t: Double? = 1.0, age: Long? = 500) =
        LiveReading(t, 1.0, age, 10, 5.0, boot, null, null, null)
    private fun v2() = LiveReading(1.0, 1.0, 500, null, 5.0, null, null, null, null)

    @Test fun backoff() {
        assertEquals(0L, backoffMs(0))
        assertEquals(1000L, backoffMs(1))
        assertEquals(2000L, backoffMs(2))
        assertEquals(4000L, backoffMs(3))
        assertEquals(5000L, backoffMs(4))
        assertEquals(5000L, backoffMs(60))
    }

    @Test fun firstReadingSyncs() {
        assertEquals(SyncDecision(sendTime = true, importLog = true), onReading(null, v3(), true))
    }

    @Test fun sameBootNoResync() {
        assertEquals(SyncDecision(sendTime = false, importLog = false), onReading(7, v3(7), true))
    }

    @Test fun bootChangeTriggersResync() {
        assertEquals(SyncDecision(sendTime = true, importLog = true), onReading(7, v3(8), true))
    }

    @Test fun v2FirmwareSkipsSync() {
        assertEquals(SyncDecision(sendTime = false, importLog = false), onReading(null, v2(), true))
        assertEquals(SyncDecision(sendTime = false, importLog = false), onReading(null, v3(), false))
    }

    // ---- R8 display rule

    @Test fun twoFailuresWithFreshReadingStayConnected() {
        assertFalse(shouldShowNotConnected(2, lastReceivedMs = 10_000, nowMs = 15_000, startedMs = 0))
    }

    @Test fun threeFailuresShowNotConnected() {
        assertTrue(shouldShowNotConnected(3, lastReceivedMs = 10_000, nowMs = 11_000, startedMs = 0))
    }

    @Test fun readingOlderThan10sShowsNotConnected() {
        assertFalse(shouldShowNotConnected(0, lastReceivedMs = 10_000, nowMs = 20_000, startedMs = 0))
        assertTrue(shouldShowNotConnected(0, lastReceivedMs = 10_000, nowMs = 20_001, startedMs = 0))
    }

    @Test fun noReadingYetConnectingThenNotConnectedAfter10s() {
        assertFalse(shouldShowNotConnected(0, lastReceivedMs = null, nowMs = 10_000, startedMs = 0))
        assertTrue(shouldShowNotConnected(0, lastReceivedMs = null, nowMs = 10_001, startedMs = 0))
    }

    @Test fun displayKeepsLastLiveDuringShortOutage() {
        val s = displayScreen(v3(t = 2.0), receivedAtMs = 1000, lastSeen = null, failures = 2, startedMs = 0, nowMs = 4000)
        assertEquals(Screen.Live(2.0, 5.0, WaterState.CLEAR, 1000), s)
    }

    @Test fun displayConnectingBeforeFirstReading() {
        assertEquals(Screen.Connecting, displayScreen(null, null, null, failures = 1, startedMs = 0, nowMs = 2000))
    }

    @Test fun displayNotConnectedAfterOutage() {
        val seen = Point(1000, 7.0)
        val s = displayScreen(v3(t = 7.0), 1000, seen, failures = 3, startedMs = 0, nowMs = 9000)
        assertEquals(Screen.NotConnected(seen, WaterState.CLOUDY), s)
    }

    @Test fun medianOfMinute() {
        assertEquals(2.0, medianOf(listOf(3.0, 1.0, 2.0)), 1e-9)
        assertEquals(2.5, medianOf(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
    }
}
