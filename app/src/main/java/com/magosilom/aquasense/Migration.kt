package com.magosilom.aquasense

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Pure: parses the v2 SharedPreferences "readings" JSON array into Points (ts -> tsMs, turb -> ntu).
 * Items that are not objects or lack ts/turb are skipped; malformed JSON yields an empty list.
 */
fun v2ToPoints(readingsJson: String): List<Point> {
    val a = try { JSONArray(readingsJson) } catch (e: Exception) { return emptyList() }
    return (0 until a.length()).mapNotNull { i ->
        runCatching {
            val o = a.getJSONObject(i)
            Point(o.getLong("ts"), o.getDouble("turb"))
        }.getOrNull()
    }
}

/**
 * One-time v2 -> v3 migration (guarded by meta key `migrated_v2`). Imports every v2 reading (all
 * sources merged into the single station) as a Point, then removes the v2 prefs keys `readings`,
 * `alerts` and `sources` (`readings` is kept, with a warning, if it is non-blank but yields 0 points). v2 alerts are intentionally NOT migrated: v3 alerts come from the new
 * alert engine. Other v2 keys (station_location, espUrl, turbMax) are left for later cleanup.
 */
fun migrateIfNeeded(ctx: Context, db: HistoryDb) {
    if (db.meta("migrated_v2") != null) return
    val prefs = ctx.getSharedPreferences("aquasense", Context.MODE_PRIVATE)
    val raw = prefs.getString("readings", null)
    val points = raw?.let { v2ToPoints(it) } ?: emptyList()
    db.addPoints(points)
    db.setMeta("migrated_v2", "1")
    val edit = prefs.edit().remove("alerts").remove("sources")
    if (!raw.isNullOrBlank() && points.isEmpty()) {
        // Unparseable v2 history: keep the raw string rather than destroy it.
        Log.w("AquaSense", "v2 readings present but parsed to 0 points; keeping the 'readings' key")
    } else {
        edit.remove("readings")
    }
    edit.apply()
}
