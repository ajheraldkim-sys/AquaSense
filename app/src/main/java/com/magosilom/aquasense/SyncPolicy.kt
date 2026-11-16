package com.magosilom.aquasense

import kotlin.math.min

/** Retry delay after [failures] consecutive failed polls: 1 s, 2 s, 4 s, then capped at 5 s. */
fun backoffMs(failures: Int): Long =
    if (failures <= 0) 0L else min(1000L shl (failures - 1).coerceAtMost(10), 5000L)

data class SyncDecision(val sendTime: Boolean, val importLog: Boolean)

/**
 * What to sync after a live reading. v2 firmware (no boot id) or a firmware without /log: nothing.
 * First reading of a connection ([prevBoot] null) or an ESP32 reboot: send the time and import the log.
 */
fun onReading(prevBoot: Int?, r: LiveReading, logSupported: Boolean): SyncDecision {
    if (!r.isV3 || !logSupported) return SyncDecision(sendTime = false, importLog = false)
    val changed = prevBoot == null || prevBoot != r.boot
    return SyncDecision(sendTime = changed, importLog = changed)
}

const val NOT_CONNECTED_FAILURES = 3
const val READING_STALE_MS = 10_000L

/**
 * R8: "Not connected" only after [NOT_CONNECTED_FAILURES] failed polls in a row, or when the last
 * good reading (or, before any reading, the start of this connection attempt) is over 10 s old.
 */
fun shouldShowNotConnected(consecutiveFailures: Int, lastReceivedMs: Long?, nowMs: Long, startedMs: Long): Boolean =
    consecutiveFailures >= NOT_CONNECTED_FAILURES || nowMs - (lastReceivedMs ?: startedMs) > READING_STALE_MS

/** The screen to show for the live loop's current state; short outages keep the last Live screen. */
fun displayScreen(
    reading: LiveReading?,
    receivedAtMs: Long?,
    lastSeen: Point?,
    failures: Int,
    startedMs: Long,
    nowMs: Long,
): Screen {
    val link = if (shouldShowNotConnected(failures, receivedAtMs, nowMs, startedMs)) Link.OFFLINE else Link.ONLINE
    return screenFor(link, reading, receivedAtMs, lastSeen)
}

fun medianOf(xs: List<Double>): Double {
    require(xs.isNotEmpty())
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}
