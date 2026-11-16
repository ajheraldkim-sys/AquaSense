package com.magosilom.aquasense

import java.util.Locale

enum class WaterState { CLEAR, GETTING_CLOUDY, CLOUDY }

/**
 * 5-level Water Clarity Classification based on Nephelometric Turbidity Units (NTU),
 * aligned with WHO (< 1 NTU target) and PNSDW 2017 (5 NTU maximum allowable limit).
 */
enum class ClarityLevel {
    EXCELLENT,  // 0 - 1 NTU (Green: Very clear water, meets WHO target < 1 NTU)
    GOOD,       // > 1 - 5 NTU (Blue: Slightly cloudy, within PNSDW 5 NTU limit, monitor)
    FAIR,       // > 5 - 20 NTU (Yellow: Noticeable cloudiness, exceeds PNSDW 5 NTU limit)
    POOR,       // > 20 - 50 NTU (Orange: Highly cloudy, exceeds PNSDW 5 NTU limit)
    VERY_POOR   // > 50 NTU (Red: Extremely cloudy, exceeds PNSDW 5 NTU limit)
}

fun clarityLevel(ntu: Double): ClarityLevel = when {
    ntu <= 1.0 -> ClarityLevel.EXCELLENT
    ntu <= 5.0 -> ClarityLevel.GOOD
    ntu <= 20.0 -> ClarityLevel.FAIR
    ntu <= 50.0 -> ClarityLevel.POOR
    else -> ClarityLevel.VERY_POOR
}

/** Maps an NTU value to a 0..1 position across the 5 equal-width visual zones (0-1, 1-5, 5-20, 20-50, 50-100+). */
fun clarityScaleFraction(ntu: Double): Float {
    val v = ntu.coerceAtLeast(0.0)
    val f = when {
        v <= 1.0 -> 0.20 * (v / 1.0)
        v <= 5.0 -> 0.20 + 0.20 * ((v - 1.0) / 4.0)
        v <= 20.0 -> 0.40 + 0.20 * ((v - 5.0) / 15.0)
        v <= 50.0 -> 0.60 + 0.20 * ((v - 20.0) / 30.0)
        else -> 0.80 + 0.20 * ((v - 50.0) / 50.0).coerceAtMost(1.0)
    }
    return f.toFloat().coerceIn(0f, 1f)
}

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
