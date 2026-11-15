package com.magosilom.aquasense

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** Samples per "Measure" press, and spacing (ESP32 refreshes its values every ~1 s). */
private const val MEASURE_SAMPLES = 5
private const val SAMPLE_GAP_MS = 1200L

/** Live mode: poll often for display, but only store a smoothed reading every [LIVE_SAVE_MS]. */
private const val LIVE_POLL_MS = 2000L
private const val LIVE_SAVE_MS = 30_000L
private const val LIVE_WINDOW = 5

private const val MAX_READINGS = 1000

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    val sources = MutableStateFlow(store.sources())
    val readings = MutableStateFlow(store.readings())
    val alerts = MutableStateFlow(store.alerts())
    val limits = MutableStateFlow(store.limits())
    val esp = MutableStateFlow(store.esp())
    val location = MutableStateFlow(store.location())
    val selected = MutableStateFlow(sources.value.first().id)

    val link = MutableStateFlow(Link.IDLE)
    val lastContact = MutableStateFlow<Long?>(null)
    /** Smoothed live value (median of the last few samples), shown while Live is on. */
    val liveSample = MutableStateFlow<Sample?>(null)
    val live = MutableStateFlow(false)
    val measuring = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val message = MutableStateFlow<String?>(null)
    val testStatus = MutableStateFlow<Pair<Boolean, String>?>(null)
    val testingEsp = MutableStateFlow(false)

    private var liveJob: Job? = null
    private var foreground = true

    init { store.saveSources(sources.value) }

    private fun sensor(url: String = esp.value.url) = Esp32Sensor(url, getApplication())

    /** One raw sample, or null on failure. Rejects stale data from a hung ESP32. */
    private suspend fun sampleOnce(s: Esp32Sensor): Sample? = runCatching {
        val x = s.read()
        if ((x.ageMs ?: 0L) > MAX_SAMPLE_AGE_MS) throw IOException("stale")
        x
    }.onSuccess {
        link.value = Link.ONLINE; lastContact.value = System.currentTimeMillis()
    }.onFailure {
        link.value = Link.OFFLINE
    }.getOrNull()

    // ------------------------------------------------------------ measuring
    /** Takes several samples and stores their median: one noisy spike can't raise an alert. */
    fun measure() {
        if (measuring.value || live.value) return
        val src = currentSource() ?: return
        viewModelScope.launch {
            measuring.value = true; progress.value = 0f
            link.value = Link.CONNECTING
            val s = sensor()
            val got = mutableListOf<Sample>()
            var fails = 0
            for (i in 0 until MEASURE_SAMPLES) {
                sampleOnce(s)?.let { got += it } ?: fails++
                progress.value = (i + 1f) / MEASURE_SAMPLES
                if (fails >= 2 && got.isEmpty()) break
                if (i < MEASURE_SAMPLES - 1) delay(SAMPLE_GAP_MS)
            }
            if (got.size >= 3) {
                save(combine(got), got.size, src, announce = true)
            } else {
                message.value = "Can't reach the sensor. Connect to the AquaSense-ESP32 Wi-Fi."
            }
            measuring.value = false; progress.value = 0f
        }
    }

    fun setLive(on: Boolean) {
        live.value = on
        restartLive()
    }

    /** Called from the UI lifecycle: live polling pauses while the app is in the background. */
    fun setForeground(fg: Boolean) {
        foreground = fg
        restartLive()
    }

    private fun restartLive() {
        liveJob?.cancel(); liveJob = null
        if (!live.value || !foreground) { liveSample.value = null; return }
        liveJob = viewModelScope.launch {
            val window = ArrayDeque<Sample>()
            var lastSave = System.currentTimeMillis()
            link.value = Link.CONNECTING
            val s = sensor()
            while (isActive) {
                val x = sampleOnce(s)
                if (x != null) {
                    window.addLast(x); if (window.size > LIVE_WINDOW) window.removeFirst()
                    liveSample.value = combine(window.toList())
                    val now = System.currentTimeMillis()
                    if (window.size >= 3 && now - lastSave >= LIVE_SAVE_MS) {
                        currentSource()?.let { save(combine(window.toList()), window.size, it, announce = false) }
                        lastSave = now
                    }
                } else {
                    window.clear(); liveSample.value = null
                }
                delay(LIVE_POLL_MS)
            }
        }
    }

    private fun currentSource() = sources.value.firstOrNull { it.id == selected.value }

    private fun save(x: Sample, n: Int, src: Source, announce: Boolean) {
        val r = Reading(sourceId = src.id, ts = System.currentTimeMillis(), turbidity = x.turbidity, samples = n)
        readings.value = (listOf(r) + readings.value).take(MAX_READINGS)
        store.saveReadings(readings.value)

        val a = assess(r, limits.value)
        val current = alerts.value.toMutableList()
        a.issue?.let { issue ->
            val exists = current.any { !it.resolved && it.sourceId == src.id && it.key == issue.key }
            if (!exists) {
                current.add(0, AlertItem(sourceId = src.id, key = issue.key, message = issue.text, ts = r.ts))
                alerts.value = current; store.saveAlerts(current)
                Notifier.alert(getApplication(), "${src.id}:${issue.key}", "${src.name}: ${issue.short}",
                    "${issue.text} Avoid drinking until tested.")
            }
        }
        if (announce) message.value = "Saved · ${a.level.label}"
    }

    fun clearHistory(sourceId: String?) {
        readings.value = if (sourceId == null) emptyList() else readings.value.filter { it.sourceId != sourceId }
        store.saveReadings(readings.value)
    }

    // -------------------------------------------------------------- alerts
    fun resolveAlert(id: String) {
        alerts.value = alerts.value.map { if (it.id == id) it.copy(resolved = true) else it }
        store.saveAlerts(alerts.value)
    }
    fun resolveAll() {
        alerts.value = alerts.value.map { it.copy(resolved = true) }
        store.saveAlerts(alerts.value)
    }

    // ------------------------------------------------------------- sources
    fun saveSource(s: Source) {
        val l = sources.value
        sources.value = if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s
        store.saveSources(sources.value)
    }

    fun deleteSource(id: String) {
        if (sources.value.size <= 1) { message.value = "Keep at least one water source."; return }
        sources.value = sources.value.filter { it.id != id }
        readings.value = readings.value.filter { it.sourceId != id }
        alerts.value = alerts.value.filter { it.sourceId != id }
        store.saveSources(sources.value); store.saveReadings(readings.value); store.saveAlerts(alerts.value)
        if (selected.value == id) selected.value = sources.value.first().id
    }

    // ------------------------------------------------------------ settings
    fun saveLimits(l: Limits) { limits.value = l; store.saveLimits(l) }
    fun saveEsp(c: EspConfig) { esp.value = c; store.saveEsp(c); restartLive() }

    fun saveLocation(loc: String) {
        val t = loc.trim()
        if (t.isEmpty()) return
        location.value = t
        store.saveLocation(t)
    }

    fun testEspConnection(testUrl: String) {
        viewModelScope.launch {
            testingEsp.value = true
            testStatus.value = null
            val start = System.currentTimeMillis()
            runCatching { sensor(testUrl.trim()).read() }
                .onSuccess { x ->
                    val ms = System.currentTimeMillis() - start
                    val stale = (x.ageMs ?: 0L) > MAX_SAMPLE_AGE_MS
                    testStatus.value = !stale to (if (stale) "Connected, but sensor data is stale. Restart the ESP32."
                        else "Connected in $ms ms · raw sample ${f1(x.turbidity)} NTU (use Measure for an accurate reading)")
                }
                .onFailure {
                    testStatus.value = false to if (it.message == "No turbidity value")
                        "Connected, but the turbidity sensor sends no data. Check its wiring."
                    else "Not reachable. Join the AquaSense-ESP32 Wi-Fi and check the address."
                }
            testingEsp.value = false
        }
    }
}
