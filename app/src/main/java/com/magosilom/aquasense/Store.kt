package com.magosilom.aquasense

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Simple on-device persistence (SharedPreferences + JSON) so data survives app restarts. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("aquasense", Context.MODE_PRIVATE)

    init {
        // Turbidity-only since v2: drop the old pH / temperature limits if an earlier version saved them.
        if (p.contains("phMin")) p.edit().remove("phMin").remove("phMax").remove("tempMin").remove("tempMax").apply()
    }

    /** Parses item by item, so one bad record never wipes the whole list. */
    private fun <T> list(key: String, parse: (JSONObject) -> T): List<T>? {
        val s = p.getString(key, null) ?: return null
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { runCatching { parse(a.getJSONObject(it)) }.getOrNull() }
        } catch (e: Exception) { null }
    }

    private fun <T> save(key: String, items: List<T>, enc: (T) -> JSONObject) {
        val a = JSONArray(); items.forEach { a.put(enc(it)) }
        p.edit().putString(key, a.toString()).apply()
    }

    fun sources(): List<Source> = list("sources") {
        Source(it.getString("id"), it.getString("name"), it.getString("location"), it.getString("type"))
    }?.takeIf { it.isNotEmpty() } ?: listOf(
        Source(name = "Main tap", location = "Water Station", type = "Tap")
    )

    fun saveSources(l: List<Source>) = save("sources", l) {
        JSONObject().put("id", it.id).put("name", it.name).put("location", it.location).put("type", it.type)
    }

    fun location(): String = p.getString("station_location", null) ?: "Barangay Magosilom Water Station"
    fun saveLocation(loc: String) = p.edit().putString("station_location", loc).apply()

    fun readings(): List<Reading> = list("readings") {
        Reading(it.getString("id"), it.getString("sourceId"), it.getLong("ts"),
            it.getDouble("turb"), it.optInt("samples", 1), it.optString("origin", ORIGIN_ESP))
    } ?: emptyList()

    fun saveReadings(l: List<Reading>) = save("readings", l) {
        JSONObject().put("id", it.id).put("sourceId", it.sourceId).put("ts", it.ts)
            .put("turb", it.turbidity).put("samples", it.samples).put("origin", it.origin)
    }

    fun alerts(): List<AlertItem> = list("alerts") {
        AlertItem(it.getString("id"), it.getString("sourceId"), it.getString("key"),
            it.getString("message"), it.getLong("ts"), it.getBoolean("resolved"))
    } ?: emptyList()

    fun saveAlerts(l: List<AlertItem>) = save("alerts", l) {
        JSONObject().put("id", it.id).put("sourceId", it.sourceId).put("key", it.key)
            .put("message", it.message).put("ts", it.ts).put("resolved", it.resolved)
    }

    fun limits(): Limits = Limits(p.getFloat("turbMax", 5f).toDouble())

    fun saveLimits(l: Limits) = p.edit().putFloat("turbMax", l.turbMax.toFloat()).apply()

    fun esp(): EspConfig = EspConfig(p.getString("espUrl", null) ?: EspConfig().url)
    fun saveEsp(c: EspConfig) = p.edit().putString("espUrl", c.url).apply()
}
