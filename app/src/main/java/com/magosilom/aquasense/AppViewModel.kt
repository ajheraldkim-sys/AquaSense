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
private const val KEY_STATION = "station_location"
private const val DEFAULT_STATION = "Barangay Magosilom Water Station"

/**
 * Automatic live controller. While in the foreground: joins the ESP32 Wi-Fi, polls /readings every
 * second (falling back to BLE live readings when Wi-Fi has not succeeded for 10 s), keeps the ESP32
 * clock set, imports its minute log, runs the alert engine and notifies.
 * Also exposes [joiner] and [ble] for manual Wi-Fi & Bluetooth scanning, pairing, and connecting.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("aquasense", Context.MODE_PRIVATE)
    private val db = HistoryDb(app)
    val joiner = WifiJoiner(app)
    val ble = BleClient(app)
    private val engine = AlertEngine()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** R6: debug builds can point at an emulator-reachable fake ESP32 and skip the Wi-Fi join. */
    private val debugBase: String? =
        if (BuildConfig.DEBUG) prefs.getString("debug_esp_base", null)?.trim()?.takeIf { it.isNotEmpty() } else null
    private val esp = EspClient(debugBase ?: ESP_BASE) { if (debugBase != null) null else joiner.activeNetwork() }

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
    private val _station = MutableStateFlow(prefs.getString(KEY_STATION, null) ?: DEFAULT_STATION)
    val station: StateFlow<String> = _station
    /** The current calibration session (see [calSessionStep]); [CalUiSession.Idle] when none. */
    private val _calSession = MutableStateFlow(CalUiSession.Idle)
    val calSession: StateFlow<CalUiSession> = _calSession
    private val _lastCal = MutableStateFlow<CalEvent?>(null)
    val lastCal: StateFlow<CalEvent?> = _lastCal
    /** ESP32's own calibration time (`cal_epoch`, seconds) from the latest reading. */
    @Volatile private var deviceCalEpochS: Long? = null
    /** Effective last calibration: newer of the recorded event and the ESP32's `cal_epoch`. */
    private val _lastCalMs = MutableStateFlow<Long?>(null)
    val lastCalMs: StateFlow<Long?> = _lastCalMs
    /** True after the last station rename failed (cleared on the next attempt). */
    private val _stationFailed = MutableStateFlow(false)
    val stationFailed: StateFlow<Boolean> = _stationFailed
    private val _wifiSupported = MutableStateFlow(joiner.supported)
    val wifiSupported: StateFlow<Boolean> = _wifiSupported
    /** Calibration events of the last 30 days, newest first (History rows). */
    private val _calEvents = MutableStateFlow<List<CalEvent>>(emptyList())
    val calEvents: StateFlow<List<CalEvent>> = _calEvents
    /** Latest limit reported by the ESP32 (chart limit line, share report). */
    private val _limit = MutableStateFlow(DEFAULT_LIMIT)
    val currentLimit: StateFlow<Double> = _limit
    /** True while connected to v3 firmware (over Wi-Fi or BLE): calibration and renaming the station can work. */
    private val _sensorReachable = MutableStateFlow(false)
    val sensorReachable: StateFlow<Boolean> = _sensorReachable
    /** Active live transport (WIFI or BLE fallback). */
    private val _transport = MutableStateFlow(Transport.WIFI)
    val transport: StateFlow<Transport> = _transport

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
                db.openAlertSince()?.let { opened ->
                    engine.restore(opened, db.lastPointTs() ?: opened, db.openAlertPeak())
                }
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
            ble.refreshPairedDevices()
            _screen.value = Screen.Connecting
            pollJob = viewModelScope.launch { pollLoop() }
        } else {
            pollJob?.cancel(); pollJob = null
            // Never show an hours-old Live screen on resume.
            _screen.value = Screen.Connecting
            calJob?.cancel(); calJob = null
            _calSession.value = CalUiSession.Idle // polling stopped: never show a stale step on resume
            _sensorReachable.value = false
            _transport.value = Transport.WIFI
            ble.stopDeviceScan()
            ble.stop()
            joiner.stop()
        }
    }

    /**
     * Starts calibration on the ESP32 (via Wi-Fi HTTP or BLE GATT) and polls /cal every second
     * until the session ends or [stopCalibration]. A calibration is recorded only on DONE after a
     * READY of this session.
     */
    fun startCalibration() {
        calJob?.cancel()
        _calSession.value = CalUiSession.started()
        calJob = viewModelScope.launch {
            val startedOk = try {
                esp.calStart()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ble.calStart()
            }
            if (!startedOk) {
                Log.w(TAG, "cal start failed on both Wi-Fi and BLE")
                _calSession.value = CalUiSession.startFailed()
                return@launch
            }
            while (isActive) {
                val s = try {
                    esp.cal()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ble.latestCal.value
                }
                val now = System.currentTimeMillis()
                val prev = _calSession.value
                val next = calSessionStep(prev, s, now)
                _calSession.value = next
                if (next.calibrated && !prev.calibrated) {
                    val calEvt = CalEvent(now, s?.calVolts ?: Double.NaN)
                    _lastCal.value = calEvt
                    withContext(Dispatchers.IO) { db.addCalEvents(listOf(calEvt)) }
                    updateLastCalMs()
                    refreshHistory()
                    logDue = true // the ESP32 logs the calibration; pull it into history
                }
                if (next.terminal) break
                delay(CAL_POLL_MS)
            }
        }
    }

    /** Calibration UI closed: stop polling /cal (the ESP32 has no cancel; its session just expires). */
    fun stopCalibration() {
        calJob?.cancel(); calJob = null
        _calSession.value = CalUiSession.Idle
    }

    private fun updateLastCalMs() {
        _lastCalMs.value = lastCalibrationMs(_lastCal.value?.tsMs, deviceCalEpochS)
    }

    fun setStation(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        _stationFailed.value = false
        viewModelScope.launch {
            val ok = try {
                esp.setStation(n)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ble.setStation(n)
            }
            if (ok) {
                updateStation(n)
            } else {
                Log.w(TAG, "setStation failed")
                _stationFailed.value = true
            }
        }
    }

    private fun updateStation(n: String) {
        if (_station.value == n) return
        _station.value = n
        prefs.edit().putString(KEY_STATION, n).apply()
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
        var lastWifiOkMs: Long? = startedMs
        var failures = 0
        var reading: LiveReading? = null
        var receivedAt: Long? = null
        var prevBoot: Int? = null
        var wasDisconnected = false
        var minute = -1L
        val minuteVals = ArrayList<Double>()
        timeDue = false; logDue = false; syncSupported = true; lastLogMs = 0L; syncFailures = 0; syncJob = null

        suspend fun recordLiveMinuteSample(tsMs: Long, ntu: Double) {
            val m = tsMs / 60_000L
            if (m != minute) {
                if (minuteVals.isNotEmpty() && minute >= 0) {
                    savePoint(Point(minute * 60_000L, medianOf(minuteVals)))
                }
                minuteVals.clear()
                minute = m
            }
            minuteVals.add(ntu)
        }

        while (isActive) {
            val r = try { esp.readings() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val now = System.currentTimeMillis() // this reading's own fetch time
            if (r == null) {
                failures++
                if (debugBase == null && failures % 5 == 0) joiner.start()
            } else {
                failures = 0
                lastWifiOkMs = now
                // R3: on each (re)connect, sync before live samples reach the alert engine.
                if (wasDisconnected) prevBoot = null
                val d = onReading(prevBoot, r, syncSupported)
                prevBoot = r.boot
                if (d.sendTime) timeDue = true
                if (d.importLog) logDue = true
                if (r.isV3 && syncSupported && now - lastLogMs >= LOG_REFRESH_MS) logDue = true
                limit = r.limit
                _limit.value = r.limit
                if (r.calEpochS != null && r.calEpochS != deviceCalEpochS) {
                    deviceCalEpochS = r.calEpochS
                    updateLastCalMs()
                }
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
                    if (!holdLive) {
                        val prevPeak = engine.peak
                        handle(engine.onLive(ntu, r.limit, now))
                        if (engine.isOpen && engine.peak > prevPeak) {
                            withContext(Dispatchers.IO) { db.updateOpenAlertPeak(engine.peak) }
                            refreshHistory()
                        }
                    }
                    // v2 firmware (no /log): keep one median point per minute ourselves.
                    if (!r.isV3 || !syncSupported) {
                        recordLiveMinuteSample(now, ntu)
                    }
                }
            }

            val nextTransport = if (ble.manualConnection && ble.connected.value) {
                Transport.BLE
            } else if (ble.connected.value && r == null) {
                Transport.BLE
            } else {
                chooseTransport(_transport.value, lastWifiOkMs, now, ble.isAvailable())
            }
            _transport.value = nextTransport
            if (nextTransport == Transport.BLE) {
                ble.start()
                val blePair = ble.latest.value
                if ((r == null || ble.manualConnection) && blePair != null && now - blePair.second <= READING_STALE_MS) {
                    val br = blePair.first
                    val bTs = blePair.second
                    reading = br
                    receivedAt = bTs
                    limit = br.limit
                    _limit.value = br.limit
                    if (br.calEpochS != null && br.calEpochS != deviceCalEpochS) {
                        deviceCalEpochS = br.calEpochS
                        updateLastCalMs()
                    }
                    br.station?.takeIf { it.isNotBlank() }?.let { updateStation(it) }
                    val ntu = br.turbidity
                    val fresh = ntu != null && (br.ageMs ?: 0L) <= READING_STALE_MS
                    if (fresh && ntu != null) {
                        lastSeen = Point(bTs, ntu)
                        val prevPeak = engine.peak
                        handle(engine.onLive(ntu, br.limit, bTs))
                        if (engine.isOpen && engine.peak > prevPeak) {
                            withContext(Dispatchers.IO) { db.updateOpenAlertPeak(engine.peak) }
                            refreshHistory()
                        }
                        // Record 1-minute median points into HistoryDb while on BLE
                        recordLiveMinuteSample(bTs, ntu)
                    }
                }
            } else {
                ble.stopIfAuto()
            }

            val effectiveFailures = if (nextTransport == Transport.BLE && receivedAt != null && now - receivedAt <= READING_STALE_MS) 0 else failures
            wasDisconnected = shouldShowNotConnected(effectiveFailures, receivedAt, now, startedMs)
            _sensorReachable.value = !wasDisconnected && reading?.isV3 == true &&
                ((failures == 0 && syncSupported) || (nextTransport == Transport.BLE && ble.connected.value))
            _screen.value = displayScreen(reading, receivedAt, lastSeen, effectiveFailures, startedMs, now)
            delay(if (effectiveFailures == 0) POLL_MS else backoffMs(failures))
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
                for (p in pts) {
                    val prevPeak = engine.peak
                    handle(engine.onImported(p, limit, now))
                    if (engine.isOpen && engine.peak > prevPeak) {
                        withContext(Dispatchers.IO) { db.updateOpenAlertPeak(engine.peak) }
                    }
                }
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
                    calEvents = db.calEvents(now - 30 * DAY_MS),
                )
            }
            _history24h.value = snap.history24h
            _days.value = snap.days
            _alerts.value = snap.alerts
            _needsCleaning.value = snap.needsCleaning
            _calEvents.value = snap.calEvents
            val dbCal = snap.lastCal
            val memCal = _lastCal.value
            if (dbCal != null && (memCal == null || dbCal.tsMs >= memCal.tsMs)) _lastCal.value = dbCal
            updateLastCalMs()
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
        val calEvents: List<CalEvent>,
    )

    override fun onCleared() {
        ble.stopDeviceScan()
        ble.stop()
        joiner.stop()
        db.close()
    }
}
