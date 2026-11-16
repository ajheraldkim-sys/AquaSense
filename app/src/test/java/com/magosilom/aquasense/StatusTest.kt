package com.magosilom.aquasense

import org.junit.Assert.*
import org.junit.Test

class StatusTest {
    private fun r(t: Double?, age: Long? = 500, limit: Double = 5.0) =
        LiveReading(t, 1.0, age, 10, limit, 1, null, null, null)

    @Test fun waterStateThresholds() {
        assertEquals(WaterState.CLEAR, waterState(3.9, 5.0))
        assertEquals(WaterState.CLEAR, waterState(4.0, 5.0))
        assertEquals(WaterState.GETTING_CLOUDY, waterState(4.1, 5.0))
        assertEquals(WaterState.GETTING_CLOUDY, waterState(5.0, 5.0))
        assertEquals(WaterState.CLOUDY, waterState(5.01, 5.0))
    }

    @Test fun staleIsSensorProblem() {
        assertEquals(Screen.SensorProblem, screenFor(Link.ONLINE, r(2.0, 10_001), 1000, null))
        assertTrue(screenFor(Link.ONLINE, r(2.0, 10_000), 1000, null) is Screen.Live)
    }

    @Test fun nullIsSensorProblem() {
        assertEquals(Screen.SensorProblem, screenFor(Link.ONLINE, r(null), 1000, null))
    }

    @Test fun offlineShowsLastSeen() {
        val p = Point(5, 6.0)
        val s = screenFor(Link.OFFLINE, null, null, p)
        assertEquals(Screen.NotConnected(p, WaterState.CLOUDY), s)
        assertEquals(Screen.NotConnected(null, null), screenFor(Link.OFFLINE, null, null, null))
    }

    @Test fun connectingAndLive() {
        assertEquals(Screen.Connecting, screenFor(Link.CONNECTING, null, null, null))
        assertEquals(Screen.Connecting, screenFor(Link.IDLE, null, null, null))
        assertEquals(Screen.Live(2.0, 5.0, WaterState.CLEAR, 1234), screenFor(Link.ONLINE, r(2.0), 1234, null))
    }

    @Test fun cleaning() {
        assertTrue(needsCleaning(listOf(0.2, 0.5, 1.1)))
        assertFalse(needsCleaning(listOf(0.2, 0.2, 0.3)))
        assertTrue(needsCleaning(listOf(2.1, 2.3)))
        assertFalse(needsCleaning(listOf(2.1)))
        assertFalse(needsCleaning(listOf(0.2, 0.5, 0.9)))
    }
}
