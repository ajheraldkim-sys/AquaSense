package com.magosilom.aquasense

import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_LIMIT = 5.0

data class LiveReading(
    val turbidity: Double?,
    val volts: Double?,
    val ageMs: Long?,
    val uptimeS: Long?,
    val limit: Double,
    val boot: Int?,
    val calVolts: Double?,
    val calEpochS: Long?,
    val station: String?,
) {
    val isV3: Boolean get() = boot != null
}

private fun JSONObject.dblOrNull(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeUnless { it.isNaN() }

private fun JSONObject.longOrNull(k: String): Long? =
    if (!has(k) || isNull(k)) null else getLong(k)

private fun JSONObject.strOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null else getString(k)

fun parseReadings(json: String): LiveReading {
    val o = JSONObject(json)
    return LiveReading(
        turbidity = o.dblOrNull("turbidity"),
        volts = o.dblOrNull("turbidity_v"),
        ageMs = o.longOrNull("age_ms"),
        uptimeS = o.longOrNull("uptime_s"),
        limit = o.dblOrNull("limit") ?: DEFAULT_LIMIT,
        boot = o.longOrNull("boot")?.toInt(),
        calVolts = o.dblOrNull("cal_v"),
        calEpochS = o.longOrNull("cal_epoch"),
        station = o.strOrNull("station"),
    )
}

data class Point(val tsMs: Long, val ntu: Double)
data class CalEvent(val tsMs: Long, val volts: Double)

data class LogPage(
    val boot: Int,
    val uptimeS: Long,
    val lastSeq: Long,
    val oldestSeq: Long,
    val points: List<Point>,
    val calEvents: List<CalEvent>,
    val maxSeqInPage: Long,
)

fun parseLog(json: String): LogPage {
    val o = JSONObject(json)
    // boot -> (epochS, uptimeS)
    val anchors = HashMap<Int, Pair<Long, Long>>()
    val aa = o.optJSONArray("anchors") ?: JSONArray()
    for (i in 0 until aa.length()) {
        val a = aa.getJSONArray(i)
        anchors[a.getInt(0)] = Pair(a.getLong(1), a.getLong(2))
    }
    val currentBoot = o.getInt("boot")
    val points = ArrayList<Point>()
    val cals = ArrayList<CalEvent>()
    var maxSeq = 0L
    val ee = o.optJSONArray("entries") ?: JSONArray()
    for (i in 0 until ee.length()) {
        val e = ee.getJSONArray(i)
        val seq = e.getLong(0)
        val entryBoot = e.getInt(1)
        val anchor = anchors[entryBoot]
        // Entries from an earlier un-anchored boot can never be anchored later, so advance past them.
        // Entries from the current boot when the current boot is not anchored yet must NOT advance maxSeq.
        if (anchor != null || entryBoot != currentBoot || e.isNull(3)) {
            if (seq > maxSeq) maxSeq = seq
        }
        if (e.isNull(3) || anchor == null) continue
        val ts = anchor.first * 1000L + (e.getLong(2) - anchor.second) * 1000L
        val value = e.getDouble(3)
        if (e.optInt(4, 0) == 1) cals.add(CalEvent(ts, value)) else points.add(Point(ts, value))
    }
    return LogPage(
        boot = currentBoot,
        uptimeS = o.getLong("uptime_s"),
        lastSeq = o.getLong("last_seq"),
        oldestSeq = o.getLong("oldest_seq"),
        points = points,
        calEvents = cals,
        maxSeqInPage = maxSeq,
    )
}

enum class CalPhase { IDLE, SETTLING, READY, DONE, REJECTED, EXPIRED }
enum class CalReason { UNSTABLE, OUT_OF_RANGE, TOO_LOW }

data class CalStatus(
    val phase: CalPhase,
    val reason: CalReason?,
    val volts: Double?,
    val secondsStable: Int,
    val calVolts: Double?,
)

fun parseCal(json: String): CalStatus {
    val o = JSONObject(json)
    val phase = when (o.strOrNull("state")) {
        "settling" -> CalPhase.SETTLING
        "ready" -> CalPhase.READY
        "done" -> CalPhase.DONE
        "rejected" -> CalPhase.REJECTED
        "expired" -> CalPhase.EXPIRED
        else -> CalPhase.IDLE
    }
    val reason = when (o.strOrNull("reason")) {
        "unstable" -> CalReason.UNSTABLE
        "out_of_range" -> CalReason.OUT_OF_RANGE
        "too_low" -> CalReason.TOO_LOW
        else -> null
    }
    return CalStatus(
        phase = phase,
        reason = reason,
        volts = o.dblOrNull("volts"),
        secondsStable = o.optInt("seconds_stable", 0),
        calVolts = o.dblOrNull("cal_v"),
    )
}
