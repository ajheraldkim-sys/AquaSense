package com.magosilom.aquasense

sealed interface AlertEvent {
    data class Opened(val atMs: Long, val ntu: Double, val notify: Boolean) : AlertEvent
    data class Closed(val atMs: Long) : AlertEvent
}

private const val NOTIFY_FRESH_MS = 3_600_000L

class AlertEngine(
    private val notifyAfterMs: Long = 30_000,
    private val closeAfterMs: Long = 600_000,
) {
    var isOpen: Boolean = false
        private set
    private var runStart: Long? = null
    private var peak = 0.0
    private var clearStart: Long? = null
    private var lastT = Long.MIN_VALUE

    fun onLive(ntu: Double?, limit: Double, rawNowMs: Long): AlertEvent? {
        if (ntu == null) return null
        val nowMs = maxOf(rawNowMs, lastT)
        lastT = nowMs
        if (ntu > limit) {
            clearStart = null
            val start = runStart ?: nowMs.also { runStart = it; peak = ntu }
            if (ntu > peak) peak = ntu
            if (!isOpen && nowMs - start >= notifyAfterMs) {
                isOpen = true
                return AlertEvent.Opened(nowMs, peak, true)
            }
            return null
        }
        if (!isOpen) { runStart = null; return null }
        return clearStep(nowMs)
    }

    /**
     * Historical minute points. Must arrive oldest to newest and before live samples for the
     * same period; points not newer than the latest sample already seen are ignored.
     * One above-limit minute opens immediately.
     */
    fun onImported(p: Point, limit: Double, nowMs: Long): AlertEvent? {
        if (p.tsMs <= lastT) return null
        lastT = p.tsMs
        if (p.ntu > limit) {
            clearStart = null
            if (isOpen) return null
            isOpen = true
            runStart = p.tsMs
            peak = p.ntu
            return AlertEvent.Opened(p.tsMs, p.ntu, nowMs - p.tsMs <= NOTIFY_FRESH_MS)
        }
        if (!isOpen) { runStart = null; return null }
        return clearStep(p.tsMs)
    }

    private fun clearStep(t: Long): AlertEvent? {
        val start = clearStart ?: t.also { clearStart = it }
        if (t - start >= closeAfterMs) {
            isOpen = false
            runStart = null
            clearStart = null
            return AlertEvent.Closed(t)
        }
        return null
    }
}
