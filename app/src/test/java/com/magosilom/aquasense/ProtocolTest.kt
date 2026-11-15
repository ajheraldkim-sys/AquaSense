package com.magosilom.aquasense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun parsesV3Readings() {
        val r = parseReadings(
            """{"turbidity":1.8,"turbidity_v":4.1,"age_ms":300,"uptime_s":5,"limit":5.0,"boot":3,"cal_v":4.2,"cal_epoch":1800000000,"station":"Brgy 1"}"""
        )
        assertEquals(1.8, r.turbidity!!, 1e-9)
        assertEquals(4.1, r.volts!!, 1e-9)
        assertEquals(300L, r.ageMs)
        assertEquals(5L, r.uptimeS)
        assertEquals(5.0, r.limit, 1e-9)
        assertEquals(3, r.boot)
        assertEquals(4.2, r.calVolts!!, 1e-9)
        assertEquals(1800000000L, r.calEpochS)
        assertEquals("Brgy 1", r.station)
        assertTrue(r.isV3)
    }

    @Test fun parsesV2Readings() {
        val r = parseReadings("""{"turbidity":1.8,"turbidity_v":4.1,"age_ms":300,"uptime_s":5,"ph":7.1,"temperature":20}""")
        assertEquals(5.0, r.limit, 1e-9)
        assertFalse(r.isV3)
        assertNull(r.boot)
        assertNull(r.station)
    }

    @Test fun nullTurbidity() {
        val r = parseReadings("""{"turbidity":null,"turbidity_v":null,"age_ms":300,"uptime_s":5}""")
        assertNull(r.turbidity)
        assertNull(r.volts)
    }

    @Test fun limitFromDevice() {
        val r = parseReadings("""{"turbidity":1.0,"turbidity_v":4.0,"age_ms":1,"uptime_s":5,"limit":4.0}""")
        assertEquals(4.0, r.limit, 1e-9)
    }

    @Test fun logTimestampsFromAnchor() {
        val p = parseLog(
            """{"boot":3,"uptime_s":200,"last_seq":7,"oldest_seq":1,"anchors":[[3,1800000000,100]],"entries":[[7,3,160,2.5,0]]}"""
        )
        assertEquals(listOf(Point(1800000060000L, 2.5)), p.points)
        assertEquals(7L, p.maxSeqInPage)
        assertEquals(3, p.boot)
        assertEquals(200L, p.uptimeS)
        assertEquals(7L, p.lastSeq)
        assertEquals(1L, p.oldestSeq)
    }

    @Test fun logEntryBeforeAnchorUptime() {
        val p = parseLog(
            """{"boot":3,"uptime_s":200,"last_seq":7,"oldest_seq":1,"anchors":[[3,1800000000,100]],"entries":[[7,3,40,2.5,0]]}"""
        )
        assertEquals(listOf(Point(1800000000000L - 60000L, 2.5)), p.points)
    }

    @Test fun logDropsUnanchoredBoot() {
        val p = parseLog(
            """{"boot":3,"uptime_s":200,"last_seq":9,"oldest_seq":1,"anchors":[[3,1800000000,100]],"entries":[[8,2,50,1.0,0],[9,3,160,2.5,0]]}"""
        )
        assertEquals(listOf(Point(1800000060000L, 2.5)), p.points)
        assertEquals(9L, p.maxSeqInPage)
    }

    @Test fun logDroppedEntriesStillCountForMaxSeq() {
        val p = parseLog(
            """{"boot":3,"uptime_s":200,"last_seq":9,"oldest_seq":1,"anchors":[],"entries":[[8,2,50,1.0,0],[9,2,60,null,0]]}"""
        )
        assertTrue(p.points.isEmpty())
        assertEquals(9L, p.maxSeqInPage)
    }

    @Test fun logNullValueSkippedButCounted() {
        val p = parseLog(
            """{"boot":3,"uptime_s":200,"last_seq":8,"oldest_seq":1,"anchors":[[3,1800000000,100]],"entries":[[8,3,160,null,0]]}"""
        )
        assertTrue(p.points.isEmpty())
        assertEquals(8L, p.maxSeqInPage)
    }

    @Test fun logEmptyPage() {
        val p = parseLog("""{"boot":3,"uptime_s":200,"last_seq":0,"oldest_seq":0,"anchors":[],"entries":[]}""")
        assertEquals(0L, p.maxSeqInPage)
    }

    @Test fun logSplitsCalEvents() {
        val p = parseLog(
            """{"boot":3,"uptime_s":200,"last_seq":8,"oldest_seq":1,"anchors":[[3,1800000000,100]],"entries":[[7,3,160,2.5,0],[8,3,170,4.2,1]]}"""
        )
        assertEquals(1, p.points.size)
        assertEquals(listOf(CalEvent(1800000070000L, 4.2)), p.calEvents)
    }

    @Test fun parsesCalRejectedTooLow() {
        val c = parseCal("""{"state":"rejected","reason":"too_low","volts":1.2,"seconds_stable":4,"cal_v":4.0}""")
        assertEquals(CalPhase.REJECTED, c.phase)
        assertEquals(CalReason.TOO_LOW, c.reason)
        assertEquals(1.2, c.volts!!, 1e-9)
        assertEquals(4, c.secondsStable)
        assertEquals(4.0, c.calVolts!!, 1e-9)
    }

    @Test fun calNullsAndUnknowns() {
        val c = parseCal("""{"state":"idle","reason":null,"volts":null,"seconds_stable":0,"cal_v":4.0}""")
        assertEquals(CalPhase.IDLE, c.phase)
        assertNull(c.reason)
        assertNull(c.volts)
        val u = parseCal("""{"state":"future","reason":"weird","volts":null,"seconds_stable":0,"cal_v":4.0}""")
        assertEquals(CalPhase.IDLE, u.phase)
        assertNull(u.reason)
    }
}
