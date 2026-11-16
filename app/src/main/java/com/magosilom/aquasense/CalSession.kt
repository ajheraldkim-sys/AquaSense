package com.magosilom.aquasense

/**
 * One calibration session as the app sees it. The ESP32 keeps its previous terminal state
 * (done / rejected / expired) until the next start, so a terminal phase only counts after this
 * session has seen SETTLING or READY.
 *
 * - [active]: Start was pressed and the session is not closed.
 * - [startFailed]: POST /cal/start failed; nothing is polled and Start is offered again.
 * - [status]: the last /cal answer that belongs to this session (stale ones are dropped).
 * - [readyAtMs]: when this session first saw READY (drives the 60 s button countdown).
 * - [calibrated]: DONE after a READY of this session; only then is a calibration recorded.
 */
data class CalUiSession(
    val active: Boolean = false,
    val startFailed: Boolean = false,
    val status: CalStatus? = null,
    val seenProgress: Boolean = false,
    val readyAtMs: Long? = null,
    val calibrated: Boolean = false,
) {
    val phase: CalPhase? get() = status?.phase
    /** Finished: stop polling. */
    val terminal: Boolean get() = startFailed || phase == CalPhase.DONE || phase == CalPhase.REJECTED || phase == CalPhase.EXPIRED

    companion object {
        val Idle = CalUiSession()
        fun started() = CalUiSession(active = true)
        fun startFailed() = CalUiSession(startFailed = true)
    }
}

/** Applies one /cal poll ([status] null = the poll failed) to the session. Pure. */
fun calSessionStep(state: CalUiSession, status: CalStatus?, nowMs: Long): CalUiSession {
    if (!state.active || state.terminal || status == null) return state
    return when (status.phase) {
        CalPhase.IDLE -> if (state.seenProgress) state else state.copy(status = status)
        CalPhase.SETTLING -> state.copy(status = status, seenProgress = true)
        CalPhase.READY -> state.copy(status = status, seenProgress = true, readyAtMs = state.readyAtMs ?: nowMs)
        CalPhase.DONE, CalPhase.REJECTED, CalPhase.EXPIRED -> when {
            !state.seenProgress -> state // stale result of an earlier calibration
            else -> state.copy(
                status = status,
                calibrated = status.phase == CalPhase.DONE && state.readyAtMs != null,
            )
        }
    }
}

/** Effective last calibration: the newer of the app's record and the ESP32's own `cal_epoch` (seconds). */
fun lastCalibrationMs(recordedMs: Long?, calEpochS: Long?): Long? {
    val device = calEpochS?.takeIf { it > 0 }?.times(1000L)
    return listOfNotNull(recordedMs, device).maxOrNull()
}
