package com.magosilom.aquasense

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

data class AlertRow(val openedMs: Long, val closedMs: Long?, val peakNtu: Double)

/** On-device history (>= 30 days of minute points). Minute-keyed points dedup live and log data (first write wins). */
class HistoryDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE points(minute INTEGER PRIMARY KEY, ts INTEGER, ntu REAL)")
        db.execSQL("CREATE TABLE cal_events(ts INTEGER PRIMARY KEY, volts REAL)")
        db.execSQL("CREATE TABLE alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, opened INTEGER, closed INTEGER NULL, peak REAL)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun addPoints(p: List<Point>) {
        if (p.isEmpty()) return
        val db = writableDatabase
        val st = db.compileStatement("INSERT OR IGNORE INTO points(minute, ts, ntu) VALUES(?,?,?)")
        db.beginTransaction()
        try {
            for (x in p) {
                st.clearBindings()
                st.bindLong(1, x.tsMs / 60000L)
                st.bindLong(2, x.tsMs)
                st.bindDouble(3, x.ntu)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            st.close()
        }
    }

    fun points(sinceMs: Long): List<Point> =
        readableDatabase.rawQuery("SELECT ts, ntu FROM points WHERE ts >= ? ORDER BY ts", arrayOf(sinceMs.toString())).use { c ->
            val out = ArrayList<Point>(c.count)
            while (c.moveToNext()) out.add(Point(c.getLong(0), c.getDouble(1)))
            out
        }

    /** Minimum ntu per local day over the last [days] days (oldest to newest); days with no points are skipped. */
    fun dailyMinimums(days: Int, zone: ZoneId): List<Double> {
        val today = LocalDate.now(zone)
        val startMs = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val mins = TreeMap<LocalDate, Double>()
        readableDatabase.rawQuery("SELECT ts, ntu FROM points WHERE ts >= ?", arrayOf(startMs.toString())).use { c ->
            while (c.moveToNext()) {
                val d = Instant.ofEpochMilli(c.getLong(0)).atZone(zone).toLocalDate()
                val v = c.getDouble(1)
                val cur = mins[d]
                if (cur == null || v < cur) mins[d] = v
            }
        }
        return mins.values.toList()
    }

    fun addCalEvents(e: List<CalEvent>) {
        if (e.isEmpty()) return
        val db = writableDatabase
        val st = db.compileStatement("INSERT OR IGNORE INTO cal_events(ts, volts) VALUES(?,?)")
        db.beginTransaction()
        try {
            for (x in e) {
                st.clearBindings()
                st.bindLong(1, x.tsMs)
                st.bindDouble(2, x.volts)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            st.close()
        }
    }

    fun lastCalEvent(): CalEvent? =
        readableDatabase.rawQuery("SELECT ts, volts FROM cal_events ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) CalEvent(c.getLong(0), c.getDouble(1)) else null
        }

    /** Calibration events at or after [sinceMs], newest first. */
    fun calEvents(sinceMs: Long): List<CalEvent> =
        readableDatabase.rawQuery("SELECT ts, volts FROM cal_events WHERE ts >= ? ORDER BY ts DESC", arrayOf(sinceMs.toString())).use { c ->
            val out = ArrayList<CalEvent>()
            while (c.moveToNext()) out.add(CalEvent(c.getLong(0), c.getDouble(1)))
            out
        }

    fun openAlert(atMs: Long, ntu: Double): Long {
        val cv = ContentValues().apply {
            put("opened", atMs); putNull("closed"); put("peak", ntu)
        }
        return writableDatabase.insert("alerts", null, cv)
    }

    /** Closes every open alert (there should be at most one; stray rows must not stay open forever). */
    fun closeAlert(atMs: Long) {
        writableDatabase.execSQL("UPDATE alerts SET closed = ? WHERE closed IS NULL", arrayOf<Any>(atMs))
    }

    /** Raises the peak NTU of any currently open alert if [ntu] is higher. */
    fun updateOpenAlertPeak(ntu: Double) {
        writableDatabase.execSQL(
            "UPDATE alerts SET peak = ? WHERE closed IS NULL AND peak < ?",
            arrayOf<Any>(ntu, ntu)
        )
    }

    /** Opening time and peak of the oldest still-open alert, or null. */
    fun openAlertSince(): Long? =
        readableDatabase.rawQuery("SELECT MIN(opened) FROM alerts WHERE closed IS NULL", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun openAlertPeak(): Double =
        readableDatabase.rawQuery("SELECT MAX(peak) FROM alerts WHERE closed IS NULL", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0
        }

    /** Timestamp of the newest stored point, or null. */
    fun lastPointTs(): Long? =
        readableDatabase.rawQuery("SELECT MAX(ts) FROM points", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** Alerts opened at or after [sinceMs], newest first. */
    fun alerts(sinceMs: Long): List<AlertRow> =
        readableDatabase.rawQuery(
            "SELECT opened, closed, peak FROM alerts WHERE opened >= ? ORDER BY opened DESC, id DESC",
            arrayOf(sinceMs.toString())
        ).use { c ->
            val out = ArrayList<AlertRow>()
            while (c.moveToNext()) out.add(AlertRow(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getDouble(2)))
            out
        }

    fun meta(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setMeta(k: String, v: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES(?,?)", arrayOf<Any>(k, v))
    }

    /** Deletes points, calibration events and closed alerts older than [olderThanMs]. */
    fun prune(olderThanMs: Long) {
        val db = writableDatabase
        val a = arrayOf(olderThanMs.toString())
        db.beginTransaction()
        try {
            db.delete("points", "ts < ?", a)
            db.delete("cal_events", "ts < ?", a)
            db.delete("alerts", "closed IS NOT NULL AND closed < ?", a)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
