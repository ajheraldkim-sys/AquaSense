package com.magosilom.aquasense

import org.junit.Assert.*
import org.junit.Test

class AlertEngineTest {
    @Test fun notifiesAfter30s() {
        val e = AlertEngine()
        assertNull(e.onLive(8.0, 5.0, 0))
        assertNull(e.onLive(9.0, 5.0, 29_999))
        assertEquals(AlertEvent.Opened(30_000, 9.0, true), e.onLive(7.0, 5.0, 30_000))
        assertTrue(e.isOpen)
        assertNull(e.onLive(7.0, 5.0, 31_000))
    }

    @Test fun briefSplashNoAlert() {
        val e = AlertEngine()
        assertNull(e.onLive(8.0, 5.0, 0))
        assertNull(e.onLive(8.0, 5.0, 10_000))
        assertNull(e.onLive(1.0, 5.0, 11_000))
        assertNull(e.onLive(8.0, 5.0, 12_000))
        assertNull(e.onLive(8.0, 5.0, 41_999))
        assertFalse(e.isOpen)
    }

    @Test fun equalToLimitIsNotAbove() {
        val e = AlertEngine()
        assertNull(e.onLive(5.0, 5.0, 0))
        assertNull(e.onLive(5.0, 5.0, 60_000))
        assertFalse(e.isOpen)
    }

    private fun open(e: AlertEngine) { e.onLive(8.0, 5.0, 0); e.onLive(8.0, 5.0, 30_000) }

    @Test fun closesAfter10MinClear() {
        val e = AlertEngine(); open(e)
        assertNull(e.onLive(1.0, 5.0, 40_000))
        assertNull(e.onLive(1.0, 5.0, 639_999))
        assertEquals(AlertEvent.Closed(640_000), e.onLive(1.0, 5.0, 640_000))
        assertFalse(e.isOpen)
    }

    @Test fun aboveSampleResetsClearRun() {
        val e = AlertEngine(); open(e)
        assertNull(e.onLive(1.0, 5.0, 40_000))
        assertNull(e.onLive(9.0, 5.0, 300_000))
        assertNull(e.onLive(1.0, 5.0, 301_000))
        assertNull(e.onLive(1.0, 5.0, 640_000))
        assertTrue(e.isOpen)
        assertTrue(e.onLive(1.0, 5.0, 901_000) is AlertEvent.Closed)
    }

    @Test fun nullSampleDoesNotCloseOrOpen() {
        val e = AlertEngine()
        assertNull(e.onLive(8.0, 5.0, 0))
        assertNull(e.onLive(null, 5.0, 20_000))
        assertNull(e.onLive(null, 5.0, 100_000))
        assertFalse(e.isOpen)
        assertTrue(e.onLive(8.0, 5.0, 100_001) is AlertEvent.Opened)
        val f = AlertEngine(); open(f)
        assertNull(f.onLive(1.0, 5.0, 40_000))
        assertNull(f.onLive(null, 5.0, 1_000_000))
        assertTrue(f.isOpen)
    }

    @Test fun openedReportsPeak() {
        val e = AlertEngine()
        e.onLive(6.0, 5.0, 0); e.onLive(12.0, 5.0, 10_000); e.onLive(7.0, 5.0, 20_000)
        assertEquals(AlertEvent.Opened(30_000, 12.0, true), e.onLive(6.0, 5.0, 30_000))
    }

    @Test fun oldImportedNoNotify() {
        val e = AlertEngine()
        val now = 10_000_000L
        assertEquals(AlertEvent.Opened(now - 7_200_000, 8.0, false), e.onImported(Point(now - 7_200_000, 8.0), 5.0, now))
        assertTrue(e.isOpen)
    }

    @Test fun recentImportedNotifies() {
        val e = AlertEngine()
        val now = 10_000_000L
        assertEquals(AlertEvent.Opened(now - 3_600_000, 8.0, true), e.onImported(Point(now - 3_600_000, 8.0), 5.0, now))
    }

    @Test fun importedCloseNeedsTenMinutesOfClear() {
        val e = AlertEngine()
        val now = 100_000_000L
        e.onImported(Point(0, 8.0), 5.0, now)
        for (m in 1..9) assertNull(e.onImported(Point(m * 60_000L, 1.0), 5.0, now))
        assertEquals(AlertEvent.Closed(660_000), e.onImported(Point(660_000, 1.0), 5.0, now))
    }

    @Test fun importAfterLiveIgnored() {
        val e = AlertEngine()
        val now = 100_000_000L
        e.onLive(8.0, 5.0, now); e.onLive(8.0, 5.0, now + 30_000)
        for (m in 0..12) assertNull(e.onImported(Point(now - 900_000 + m * 60_000L, 1.0), 5.0, now))
        assertNull(e.onLive(1.0, 5.0, now + 40_000))
        assertNull(e.onLive(8.0, 5.0, now + 50_000))
        assertNull(e.onLive(8.0, 5.0, now + 100_000))
        assertTrue(e.isOpen)
    }

    @Test fun importedClearDoesNotResetLiveRun() {
        val e = AlertEngine()
        val now = 100_000_000L
        e.onLive(8.0, 5.0, now)
        assertNull(e.onImported(Point(now - 60_000, 1.0), 5.0, now))
        assertEquals(AlertEvent.Opened(now + 30_000, 8.0, true), e.onLive(8.0, 5.0, now + 30_000))
    }

    @Test fun importedJustOver1hNoNotify() {
        val e = AlertEngine()
        val now = 10_000_000L
        assertEquals(AlertEvent.Opened(now - 3_600_001, 8.0, false), e.onImported(Point(now - 3_600_001, 8.0), 5.0, now))
    }

    @Test fun reopenAfterClose() {
        val e = AlertEngine()
        e.onLive(8.0, 5.0, 0); e.onLive(8.0, 5.0, 30_000)
        e.onLive(1.0, 5.0, 40_000)
        assertTrue(e.onLive(1.0, 5.0, 640_000) is AlertEvent.Closed)
        assertNull(e.onLive(8.0, 5.0, 650_000))
        assertNull(e.onLive(8.0, 5.0, 679_999))
        assertTrue(e.onLive(8.0, 5.0, 680_000) is AlertEvent.Opened)
    }

    @Test fun restoredOpenAlertDoesNotReNotifyAndClosesAfterClear() {
        val e = AlertEngine()
        e.restore(openedMs = 1_000, lastTs = 60_000)
        assertTrue(e.isOpen)
        assertNull(e.onLive(9.0, 5.0, 100_000))
        assertNull(e.onLive(9.0, 5.0, 200_000))
        assertNull(e.onImported(Point(30_000, 9.0), 5.0, 200_000)) // older than lastT: ignored
        assertNull(e.onLive(1.0, 5.0, 300_000))
        assertNull(e.onLive(1.0, 5.0, 899_999))
        assertEquals(AlertEvent.Closed(900_000), e.onLive(1.0, 5.0, 900_000))
        assertFalse(e.isOpen)
    }

    @Test fun restoredOpenAlertImportedCloudyDoesNotReopen() {
        val e = AlertEngine()
        e.restore(openedMs = 1_000, lastTs = 1_000)
        assertNull(e.onImported(Point(61_000, 9.0), 5.0, 61_000))
        assertTrue(e.isOpen)
    }
}
