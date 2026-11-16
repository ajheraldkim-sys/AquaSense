package com.magosilom.aquasense

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

private const val TAG = "AquaSense"
private const val POLL_MS = 1000L
private const val CAL_POLL_MS = 1000L
private const val LOG_PAGE_MAX = 100
/** Pages per sync cycle (12,000 entries) so a first sync can't starve live polling. */
private const val MAX_PAGES_PER_CYCLE = 120
/** v3: re-page the minute log this often while connected so History keeps filling in. */
private const val LOG_REFRESH_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val KEEP_MS = 35 * DAY_MS
private const val META_LAST_SEQ = "last_seq"

/**
 * Automatic live controller. While in the foreground: joins the ESP32 Wi-Fi, polls /readings every
 * second, keeps the ESP32 clock set, imports its minute log, runs the alert engine and notifies.
 * The UI only reads the StateFlows below and calls [setForeground], [startCalibration],
 * [stopCalibration] and [setStation].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("aquasense", Context.MODE_PRIVATE)
    private val store = Store(app)
    private val db = HistoryDb(app)
    private val joiner = WifiJoiner(app)
    private val engine = AlertEngine()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** R6: debug builds can point at an emulator-reachable fake ESP32 and skip the Wi-Fi join. */
    private val debugBase: String? =
        if (BuildConfig.DEBUG) prefs.getString("debug_esp_base", null)?.trim()?.takeIf { it.isNotEmpty() } else null
    private val esp = EspClient(debugBase ?: ESP_BASE) { if (debugBase != null) null else joiner.network.value }

    private val _screen = MutableStateFlow<Screen>(Screen.Connecting)
    val screen: StateFlow<Screen> = _screen
    private val _history24h = MutableStateFlow<List<Point>>(emptyList())
    val history24h: StateFlow<List<Point>> = _history24h
    private val _days = MutableStateFlow<List<DaySummary>>(emptyList())
    val days: StateFlow<List<DaySummary>> = _days
    private val _alerts = MutableStateFlow<List<AlertRow>>(emptyList())
    val alerts: StateFlow<List<AlertRow>> = _alerts
    private val _needsCleaning = MutableStateFlow(false)
    val needsCleaning: StateFlow<Boolean> = _needsCleaning
    private val _station = MutableStateFlow(store.location())
    val station: StateFlow<String> = _station
    private val _cal = MutableStateFlow<CalStatus?>(null)
    val cal: StateFlow<CalStatus?> = _cal
    private val _lastCal = MutableStateFlow<CalEvent?>(null)
    val lastCal: StateFlow<CalEvent?> = _lastCal
    private val _wifiSupported = MutableStateFlow(joiner.supported)
    val wifiSupported: StateFlow<Boolean> = _wifiSupported

    /** Latest limit seen from the ESP32 (used for summaries and imported points). */
    @Volatile private var limit = DEFAULT_LIMIT
    private var lastSeen: Point? = null

    private val dbReady: Job = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            try {
                migrateIfNeeded(app, db)
                db.prune(System.currentTimeMillis() - KEEP_MS)
            } catch (e: Exception) {
                Log.w(TAG, "history init failed", e)
            }
        }
        refreshHistory()
        lastSeen = _history24h.value.lastOrNull()
    }

    private var pollJob: Job? = null
    private var calJob: Job? = null

    // ---------------------------------------------------------------- actions

    /** Called from the activity lifecycle: everything network-related runs only in the foreground. */
    fun setForeground(fg: Boolean) {
        if (fg) {
            if (pollJob?.isActive == true) return
            if (debugBase == null) joiner.start()
            pollJob = viewModelScope.launch { pollLoop() }
        } else {
            pollJob?.cancel(); pollJob = null
            calJob?.cancel(); calJob = null
            joiner.stop()
        }
    }

    /** Starts calibration on the ESP32 and polls /cal every second until it ends or [stopCalibration]. */
    fun startCalibration() {
        calJob?.cancel()
        calJob = viewModelScope.launch {
            _cal.value = null
            try { esp.calStart() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "cal start failed", e)
            }
            while (isActive) {
                val s = try { esp.cal() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                if (s != null) {
                    _cal.value = s
                    if (s.phase == CalPhase.DONE) {
                        s.calVolts?.let { _lastCal.value = CalEvent(System.currentTimeMillis(), it) }
                        logDue = true // the ESP32 logs the calibration; pull it into history
                    }
                    if (s.phase == CalPhase.DONE || s.phase == CalPhase.REJECTED || s.phase == CalPhase.EXPIRED) break
                }
                delay(CAL_POLL_MS)
            }
        }
    }

    /** Calibration UI closed: stop polling /cal. */
    fun stopCalibration() {
        calJob?.cancel(); calJob = null
        _cal.value = null
    }

    fun setStation(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch {
            try {
                esp.setStation(n)
                updateStation(n)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "setStation failed", e)
            }
        }
    }

    private fun updateStation(n: String) {
        if (_station.value == n) return
        _station.value = n
        store.saveLocation(n)
    }

    // ------------------------------------------------------------- live loop

    // Loop state (only touched from the poll coroutine on Main, plus the logDue flag).
    @Volatile private var logDue = false

    private suspend fun pollLoop() {
        dbReady.join()
        val startedMs = System.currentTimeMillis()
        var failures = 0
        var reading: LiveReading? = null
        var receivedAt: Long? = null
        var prevBoot: Int? = null
        var logSupported = true
        var timeDue = false
        var lastLogMs = 0L
        var wasDisconnected = false
        var minute = -1L
        val minuteVals = ArrayList<Double>()

        var importFailures = 0

        while (currentCoroutineContext().isActive) {
            val r = try { esp.readings() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val now = System.currentTimeMillis()
            if (r == null) {
                failures++
            } else {
                failures = 0
                // R3: on each (re)connect, sync before live samples reach the alert engine.
                if (wasDisconnected) prevBoot = null
                val d = onReading(prevBoot, r, logSupported)
                prevBoot = r.boot
                if (d.sendTime) timeDue = true
                if (d.importLog) logDue = true
                if (r.isV3 && logSupported && now - lastLogMs >= LOG_REFRESH_MS) logDue = true
                limit = r.limit
                r.station?.takeIf { it.isNotBlank() }?.let { updateStation(it) }

                if (timeDue && r.isV3) {
                    try { esp.postTime(now / 1000); timeDue = false }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "postTime failed", e) }
                }
                if (logDue && r.isV3 && logSupported) {
                    try {
                        if (importLog(r.limit)) { logDue = false; lastLogMs = now }
                        importFailures = 0
                    } catch (e: CancellationException) { throw e } catch (e: Unsupported) {
                        logSupported = false; logDue = false
                    } catch (e: Exception) {
                        Log.w(TAG, "log import failed", e)
                        // Don't hold live alerts hostage to a failing /log: retry at the next refresh.
                        if (++importFailures >= 3) { logDue = false; lastLogMs = now; importFailures = 0 }
                    }
                }

                reading = r
                receivedAt = now
                val ntu = r.turbidity
                val fresh = ntu != null && (r.ageMs ?: 0L) <= READING_STALE_MS
                if (fresh && ntu != null) {
                    lastSeen = Point(now, ntu)
                    // Imported points must precede live samples: hold live alerts until the import finished.
                    if (!logDue) handle(engine.onLive(ntu, r.limit, now))
                    // v2 firmware (no /log): keep one median point per minute ourselves.
                    if (!r.isV3 || !logSupported) {
                        val m = now / 60_000L
                        if (m != minute) {
                            if (minuteVals.isNotEmpty() && minute >= 0) savePoint(Point(minute * 60_000L, medianOf(minuteVals)))
                            minuteVals.clear(); minute = m
                        }
                        minuteVals.add(ntu)
                    }
                }
            }
            val after = System.currentTimeMillis()
            wasDisconnected = shouldShowNotConnected(failures, receivedAt, after, startedMs)
            _screen.value = displayScreen(reading, receivedAt, lastSeen, failures, startedMs, after)
            delay(if (failures == 0) POLL_MS else backoffMs(failures))
        }
    }

    /**
     * Pages /log from the persisted cursor. Returns true when caught up, false when the page cap
     * was hit (continue next cycle). Never loops forever on an empty page.
     */
    private suspend fun importLog(limit: Double): Boolean {
        var since = withContext(Dispatchers.IO) { db.meta(META_LAST_SEQ)?.toLongOrNull() ?: 0L }
        var jumped = false
        try {
            repeat(MAX_PAGES_PER_CYCLE) {
                val page = esp.log(since, LOG_PAGE_MAX)
                if (page.maxSeqInPage == 0L) {
                    // entries:[] — if entries before oldest_seq were dropped, skip past the gap once.
                    if (page.lastSeq > since && !jumped && page.oldestSeq - 1 > since) {
                        jumped = true
                        since = page.oldestSeq - 1
                        saveCursor(since)
                        return@repeat
                    }
                    return true
                }
                val now = System.currentTimeMillis()
                val pts = page.points.sortedBy { it.tsMs }
                withContext(Dispatchers.IO) {
                    db.addPoints(pts)
                    db.addCalEvents(page.calEvents)
                }
                for (p in pts) handle(engine.onImported(p, limit, now))
                if (page.maxSeqInPage <= since) return true // no progress: stop this cycle
                since = page.maxSeqInPage
                saveCursor(since)
                if (since >= page.lastSeq) return true
            }
            return false
        } finally {
            refreshHistory()
        }
    }

    private suspend fun saveCursor(seq: Long) =
        withContext(Dispatchers.IO) { db.setMeta(META_LAST_SEQ, seq.toString()) }

    private suspend fun savePoint(p: Point) {
        withContext(Dispatchers.IO) { db.addPoints(listOf(p)) }
        refreshHistory()
    }

    /** Persists alert events; notifies only on a fresh Opened. */
    private suspend fun handle(e: AlertEvent?) {
        when (e) {
            null -> return
            is AlertEvent.Opened -> {
                withContext(Dispatchers.IO) { db.openAlert(e.atMs, e.ntu) }
                if (e.notify) Notifier.alert(getApplication(), R.string.notif_cloudy_title, R.string.notif_cloudy_text)
            }
            is AlertEvent.Closed -> withContext(Dispatchers.IO) { db.closeAlert(e.atMs) }
        }
        refreshHistory()
    }

    private suspend fun refreshHistory() {
        val now = System.currentTimeMillis()
        val z = zone
        val lim = limit
        try {
            val snap = withContext(Dispatchers.IO) {
                val recent = db.points(now - DAY_MS)
                val week = db.points(now - 7 * DAY_MS)
                Snapshot(
                    history24h = recent,
                    days = summarizeDays(week, lim, z),
                    alerts = db.alerts(now - 30 * DAY_MS),
                    needsCleaning = needsCleaning(db.dailyMinimums(7, z)),
                    lastCal = db.lastCalEvent(),
                )
            }
            _history24h.value = snap.history24h
            _days.value = snap.days
            _alerts.value = snap.alerts
            _needsCleaning.value = snap.needsCleaning
            val dbCal = snap.lastCal
            val memCal = _lastCal.value
            if (dbCal != null && (memCal == null || dbCal.tsMs >= memCal.tsMs)) _lastCal.value = dbCal
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "history refresh failed", e)
        }
    }

    private class Snapshot(
        val history24h: List<Point>,
        val days: List<DaySummary>,
        val alerts: List<AlertRow>,
        val needsCleaning: Boolean,
        val lastCal: CalEvent?,
    )

    override fun onCleared() {
        joiner.stop()
        db.close()
    }
}
