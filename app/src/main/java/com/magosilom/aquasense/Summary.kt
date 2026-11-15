package com.magosilom.aquasense

import java.time.Instant
import java.time.ZoneId

data class DaySummary(
    val dayStartMs: Long,
    val worst: WaterState,
    val avgNtu: Double,
    val cloudyMinutes: Int,
    val firstCloudyMs: Long?,
    val lastCloudyMs: Long?,
)

/** Each point represents one minute. Newest day first. */
fun summarizeDays(points: List<Point>, limit: Double, zone: ZoneId): List<DaySummary> =
    points.groupBy { Instant.ofEpochMilli(it.tsMs).atZone(zone).toLocalDate() }
        .entries.sortedByDescending { it.key }
        .map { (day, ps) ->
            val cloudy = ps.filter { it.ntu > limit }
            DaySummary(
                dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                worst = ps.maxOf { waterState(it.ntu, limit) },
                avgNtu = ps.sumOf { it.ntu } / ps.size,
                cloudyMinutes = cloudy.size,
                firstCloudyMs = cloudy.minOfOrNull { it.tsMs },
                lastCloudyMs = cloudy.maxOfOrNull { it.tsMs },
            )
        }
