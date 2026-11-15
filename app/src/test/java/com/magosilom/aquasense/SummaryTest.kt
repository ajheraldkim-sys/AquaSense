package com.magosilom.aquasense

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SummaryTest {
    private val z = ZoneId.of("UTC")
    private fun ms(d: Int, h: Int, m: Int) =
        LocalDateTime.of(2026, 11, d, h, m).atZone(z).toInstant().toEpochMilli()

    @Test fun oneDayAllClear() {
        val pts = (0 until 60).map { Point(ms(10, 8, 0) + it * 60_000L, 1.0) }
        val s = summarizeDays(pts, 5.0, z)
        assertEquals(1, s.size)
        assertEquals(WaterState.CLEAR, s[0].worst)
        assertEquals(0, s[0].cloudyMinutes)
        assertEquals(1.0, s[0].avgNtu, 1e-9)
        assertNull(s[0].firstCloudyMs)
        assertEquals(ms(10, 0, 0), s[0].dayStartMs)
    }

    @Test fun cloudySpan() {
        val pts = (0 until 24 * 60).map {
            val t = ms(10, 0, 0) + it * 60_000L
            val cloudy = t >= ms(10, 14, 0) && t <= ms(10, 15, 59)
            Point(t, if (cloudy) 9.0 else 1.0)
        }
        val s = summarizeDays(pts, 5.0, z).single()
        assertEquals(120, s.cloudyMinutes)
        assertEquals(WaterState.CLOUDY, s.worst)
        assertEquals(ms(10, 14, 0), s.firstCloudyMs)
        assertEquals(ms(10, 15, 59), s.lastCloudyMs)
    }

    @Test fun newestFirstAndZoneSplit() {
        val pts = listOf(Point(ms(9, 23, 0), 4.5), Point(ms(10, 1, 0), 2.0))
        val s = summarizeDays(pts, 5.0, z)
        assertEquals(listOf(ms(10, 0, 0), ms(9, 0, 0)), s.map { it.dayStartMs })
        assertEquals(WaterState.GETTING_CLOUDY, s[1].worst)
        val manila = summarizeDays(pts, 5.0, ZoneId.of("Asia/Manila"))
        assertEquals(1, manila.size) // 07:00 and 09:00 Manila on the 10th
        assertTrue(summarizeDays(emptyList(), 5.0, z).isEmpty())
    }
}
