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

sealed interface CursorStep {
    /** Fetch the next page from [since]. */
    data class Next(val since: Long, val jumped: Boolean) : CursorStep
    /** Stop paging this cycle; persist [since]. */
    data class Done(val since: Long) : CursorStep
}

/**
 * /log cursor rules, applied after a page fetched with [since]:
 * - ESP seq went backwards (`last_seq < since`: reflashed / NVS erased) -> restart from 0.
 * - empty page while `last_seq > since` and entries were dropped -> jump once to `oldest_seq - 1`;
 *   still empty after the jump (or no gap) -> stop this cycle.
 * - no progress -> stop; otherwise advance to `maxSeqInPage`, done once it reaches `last_seq`.
 */
fun nextCursor(page: LogPage, since: Long, jumped: Boolean): CursorStep {
    if (page.lastSeq < since) return CursorStep.Next(0, jumped)
    if (page.maxSeqInPage == 0L) {
        val jumpTo = page.oldestSeq - 1
        return if (page.lastSeq > since && !jumped && jumpTo > since) CursorStep.Next(jumpTo, true)
        else CursorStep.Done(since)
    }
    if (page.maxSeqInPage <= since) return CursorStep.Done(since)
    val s = page.maxSeqInPage
    return if (s >= page.lastSeq) CursorStep.Done(s) else CursorStep.Next(s, jumped)
}

fun medianOf(xs: List<Double>): Double {
    require(xs.isNotEmpty())
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}
