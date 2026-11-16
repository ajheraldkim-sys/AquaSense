package com.magosilom.aquasense

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
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
/** Hard cap on pages per sync run (200,000 entries, far above the 7-day log): never page forever. */
private const val MAX_PAGES_PER_SYNC = 2000
/** Consecutive failed sync runs (time or log) before giving up until the next refresh. */
private const val MAX_SYNC_FAILURES = 3
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

    /** In-app language (Settings chooses it); applied at once by [LocalizedApp]. */
    private val languagePrefs = LanguagePrefs(app)
    val language: StateFlow<AppLanguage> = languagePrefs.language
    fun setLanguage(lang: AppLanguage) = languagePrefs.set(lang)

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
            // An alert still open from an earlier process: resume it instead of re-notifying.
            try {
                db.openAlertSince()?.let { opened -> engine.restore(opened, db.lastPointTs() ?: opened) }
            } catch (e: Exception) {
                Log.w(TAG, "alert restore failed", e)
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
            _screen.value = Screen.Connecting
            pollJob = viewModelScope.launch { pollLoop() }
        } else {
            pollJob?.cancel(); pollJob = null
            // Never show an hours-old Live screen on resume.
            _screen.value = Screen.Connecting
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

    // Loop state. All of it lives on the Main dispatcher (viewModelScope); only DB I/O hops to IO.
    // Reset at the start of every pollLoop (each foreground session is a new connection attempt).
    private var timeDue = false
    private var logDue = false
    /** /time and /log usable; false for v2 firmware or after a 404 from either. */
    private var syncSupported = true
    private var lastLogMs = 0L
    private var syncFailures = 0
    private var syncJob: Job? = null

    /** R14: only engine.onLive waits, and only while imported history must reach the engine first. */
    private val holdLive: Boolean get() = logDue && syncSupported

    private suspend fun pollLoop() = coroutineScope {
        dbReady.join()
        _screen.value = Screen.Connecting
        val startedMs = System.currentTimeMillis()
        var failures = 0
        var reading: LiveReading? = null
        var receivedAt: Long? = null
        var prevBoot: Int? = null
        var wasDisconnected = false
        var minute = -1L
        val minuteVals = ArrayList<Double>()
        timeDue = false; logDue = false; syncSupported = true; lastLogMs = 0L; syncFailures = 0; syncJob = null

        while (isActive) {
            val r = try { esp.readings() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val now = System.currentTimeMillis() // this reading's own fetch time
            if (r == null) {
                failures++
            } else {
                failures = 0
                // R3: on each (re)connect, sync before live samples reach the alert engine.
                if (wasDisconnected) prevBoot = null
                val d = onReading(prevBoot, r, syncSupported)
                prevBoot = r.boot
                if (d.sendTime) timeDue = true
                if (d.importLog) logDue = true
                if (r.isV3 && syncSupported && now - lastLogMs >= LOG_REFRESH_MS) logDue = true
                limit = r.limit
                r.station?.takeIf { it.isNotBlank() }?.let { updateStation(it) }

                // Sync runs beside the poll loop so /readings keeps its 1 s rhythm (R14).
                if (r.isV3 && syncSupported && (timeDue || logDue) && syncJob?.isActive != true) {
                    val lim = r.limit
                    syncJob = launch { syncOnce(lim) }
                }

                reading = r
                receivedAt = now
                val ntu = r.turbidity
                val fresh = ntu != null && (r.ageMs ?: 0L) <= READING_STALE_MS
                if (fresh && ntu != null) {
                    lastSeen = Point(now, ntu)
                    if (!holdLive) handle(engine.onLive(ntu, r.limit, now))
                    // v2 firmware (no /log): keep one median point per minute ourselves.
                    if (!r.isV3 || !syncSupported) {
                        val m = now / 60_000L
                        if (m != minute) {
                            if (minuteVals.isNotEmpty() && minute >= 0) savePoint(Point(minute * 60_000L, medianOf(minuteVals)))
                            minuteVals.clear(); minute = m
                        }
                        minuteVals.add(ntu)
                    }
                }
            }
            wasDisconnected = shouldShowNotConnected(failures, receivedAt, now, startedMs)
            _screen.value = displayScreen(reading, receivedAt, lastSeen, failures, startedMs, now)
            delay(if (failures == 0) POLL_MS else backoffMs(failures))
        }
    }

    /** One sync run: POST /time first; the log is imported only once the ESP32 clock is set (R14). */
    private suspend fun syncOnce(limit: Double) {
        try {
            if (timeDue) {
                esp.postTime(System.currentTimeMillis() / 1000)
                timeDue = false
            }
            if (logDue) {
                importLog(limit)
                logDue = false
                lastLogMs = System.currentTimeMillis()
            }
            syncFailures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unsupported) {
            // Treat like v2 firmware: stop retrying /time and /log, record live minutes ourselves.
            syncSupported = false; timeDue = false; logDue = false
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            // Don't hold live alerts hostage to a failing sync: give up until the next refresh.
            // (timeDue stays set, so the next run still sets the clock before importing.)
            if (++syncFailures >= MAX_SYNC_FAILURES) {
                logDue = false; lastLogMs = System.currentTimeMillis(); syncFailures = 0
            }
        }
    }

    /** Pages /log from the persisted cursor until [nextCursor] says done (or the hard page cap). */
    private suspend fun importLog(limit: Double) {
        var since = withContext(Dispatchers.IO) { db.meta(META_LAST_SEQ)?.toLongOrNull() ?: 0L }
        var jumped = false
        try {
            repeat(MAX_PAGES_PER_SYNC) {
                val page = esp.log(since, LOG_PAGE_MAX)
                val now = System.currentTimeMillis()
                val pts = page.points.sortedBy { it.tsMs }
                if (pts.isNotEmpty() || page.calEvents.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        db.addPoints(pts)
                        db.addCalEvents(page.calEvents)
                    }
                }
                for (p in pts) handle(engine.onImported(p, limit, now))
                when (val step = nextCursor(page, since, jumped)) {
                    is CursorStep.Done -> {
                        if (step.since != since) saveCursor(step.since)
                        return
                    }
                    is CursorStep.Next -> {
                        since = step.since
                        jumped = step.jumped
                        saveCursor(since)
                    }
                }
            }
            Log.w(TAG, "log import hit the page cap; continuing at the next refresh")
        } finally {
            withContext(NonCancellable) { refreshHistory() }
        }
    }

    private suspend fun saveCursor(seq: Long) =
        withContext(Dispatchers.IO) { db.setMeta(META_LAST_SEQ, seq.toString()) }

    private suspend fun savePoint(p: Point) {
        withContext(Dispatchers.IO) { db.addPoints(listOf(p)) }
        refreshHistory()
    }

    /**
     * Notifies (only on a fresh Opened) before persisting; NonCancellable so going to the background
     * mid-event can't drop the notification or leave the engine and the DB out of step.
     */
    private suspend fun handle(e: AlertEvent?) {
        if (e == null) return
        withContext(NonCancellable) {
            when (e) {
                is AlertEvent.Opened -> {
                    if (e.notify) Notifier.alert(getApplication(), R.string.notif_cloudy_title, R.string.notif_cloudy_text)
                    withContext(Dispatchers.IO) { db.openAlert(e.atMs, e.ntu) }
                }
                is AlertEvent.Closed -> withContext(Dispatchers.IO) { db.closeAlert(e.atMs) }
            }
            refreshHistory()
        }
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
