package com.magosilom.aquasense

import org.junit.Assert.*
import org.junit.Test

class CalSessionTest {
    private fun st(p: CalPhase, reason: CalReason? = null, secs: Int = 0) = CalStatus(p, reason, 4.1, secs, 4.2)

    @Test fun failedStartIsTerminalFailure() {
        val s = CalUiSession.startFailed()
        assertTrue(s.startFailed)
        assertTrue(s.terminal)
        assertFalse(s.active)
        assertFalse(s.calibrated)
        // A late poll cannot revive it.
        assertEquals(s, calSessionStep(s, st(CalPhase.DONE), 1))
    }

    @Test fun staleDoneBeforeSettlingIsIgnored() {
        val s = calSessionStep(CalUiSession.started(), st(CalPhase.DONE), 1)
        assertNull(s.phase)
        assertFalse(s.terminal)
        assertFalse(s.calibrated)
    }

    @Test fun staleRejectedAndExpiredAreIgnored() {
        var s = calSessionStep(CalUiSession.started(), st(CalPhase.REJECTED, CalReason.TOO_LOW), 1)
        assertNull(s.phase); assertFalse(s.terminal)
        s = calSessionStep(s, st(CalPhase.EXPIRED), 2)
        assertNull(s.phase); assertFalse(s.terminal)
    }

    @Test fun settlingReadyDoneIsRecorded() {
        var s = calSessionStep(CalUiSession.started(), st(CalPhase.SETTLING, secs = 10), 1_000)
        assertEquals(CalPhase.SETTLING, s.phase)
        s = calSessionStep(s, st(CalPhase.READY), 2_000)
        assertEquals(2_000L, s.readyAtMs)
        s = calSessionStep(s, st(CalPhase.READY), 3_000)
        assertEquals("first READY time is kept", 2_000L, s.readyAtMs)
        s = calSessionStep(s, st(CalPhase.DONE), 4_000)
        assertEquals(CalPhase.DONE, s.phase)
        assertTrue(s.terminal)
        assertTrue(s.calibrated)
    }

    @Test fun autoSavedDoneAfterSettlingIsRecorded() {
        var s = calSessionStep(CalUiSession.started(), st(CalPhase.SETTLING), 1)
        s = calSessionStep(s, st(CalPhase.DONE), 2)
        assertEquals(CalPhase.DONE, s.phase)
        assertTrue(s.calibrated)
    }

    @Test fun rejectedAfterSettlingIsShown() {
        var s = calSessionStep(CalUiSession.started(), st(CalPhase.SETTLING), 1)
        s = calSessionStep(s, st(CalPhase.REJECTED, CalReason.OUT_OF_RANGE), 2)
        assertEquals(CalPhase.REJECTED, s.phase)
        assertTrue(s.terminal)
        assertFalse(s.calibrated)
    }

    @Test fun failedPollKeepsState() {
        val s = calSessionStep(CalUiSession.started(), st(CalPhase.SETTLING), 1)
        assertEquals(s, calSessionStep(s, null, 2))
    }

    @Test fun lastCalibrationIsTheNewerOfRecordAndDevice() {
        assertNull(lastCalibrationMs(null, null))
        assertNull(lastCalibrationMs(null, 0))
        assertEquals(5_000L, lastCalibrationMs(5_000L, null))
        assertEquals(1_800_000_000_000L, lastCalibrationMs(null, 1_800_000_000L))
        assertEquals(1_800_000_000_000L, lastCalibrationMs(1_700_000_000_000L, 1_800_000_000L))
        assertEquals(1_900_000_000_000L, lastCalibrationMs(1_900_000_000_000L, 1_800_000_000L))
    }
}
