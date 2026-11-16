package com.magosilom.aquasense

import java.util.Locale

enum class WaterState { CLEAR, GETTING_CLOUDY, CLOUDY }

/** Above this fraction of the limit the water is "Getting cloudy". */
const val NEAR_FRACTION = 0.8

fun waterState(ntu: Double, limit: Double): WaterState = when {
    ntu > limit -> WaterState.CLOUDY
    ntu > NEAR_FRACTION * limit -> WaterState.GETTING_CLOUDY
    else -> WaterState.CLEAR
}

/** Connection state of the ESP32 link. */
enum class Link { IDLE, CONNECTING, ONLINE, OFFLINE }

/** One decimal, always with a dot (numbers on screen and in the share report). */
fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)

sealed interface Screen {
    object Connecting : Screen
    data class NotConnected(val lastSeen: Point?, val lastState: WaterState?) : Screen
    object SensorProblem : Screen
    data class Live(val ntu: Double, val limit: Double, val state: WaterState, val updatedAtMs: Long) : Screen
}

private const val STALE_MS = 10_000L

fun screenFor(link: Link, reading: LiveReading?, receivedAtMs: Long?, lastSeen: Point?): Screen {
    if (link == Link.CONNECTING || (link == Link.IDLE && reading == null)) return Screen.Connecting
    if (link == Link.OFFLINE) {
        val limit = reading?.limit ?: DEFAULT_LIMIT
        return Screen.NotConnected(lastSeen, lastSeen?.let { waterState(it.ntu, limit) })
    }
    if (reading == null) return Screen.Connecting
    val ntu = reading.turbidity
    if (ntu == null || (reading.ageMs ?: 0) > STALE_MS) return Screen.SensorProblem
    return Screen.Live(ntu, reading.limit, waterState(ntu, reading.limit), receivedAtMs ?: 0L)
}

/** dailyMinimums oldest to newest. A rising floor suggests the sensor window needs cleaning. */
fun needsCleaning(dailyMinimums: List<Double>): Boolean {
    val n = dailyMinimums.size
    if (n >= 3) {
        val (a, b, c) = dailyMinimums.takeLast(3)
        if (a < b && b < c && c >= 1.0) return true
    }
    if (n >= 2 && dailyMinimums.takeLast(2).all { it >= 2.0 }) return true
    return false
}
