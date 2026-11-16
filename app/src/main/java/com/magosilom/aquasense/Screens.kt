package com.magosilom.aquasense

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private const val KEY_JOIN_HINT_DONE = "join_wifi_hint_done"
/** The 24 h chart never draws more points than this (one per ~7 minutes of a full day). */
private const val CHART_MAX_POINTS = 200

/** "5" for whole numbers, else one decimal: "Cloudiness 8.7 (limit is 5)". */
fun plainNumber(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e6) v.toLong().toString() else f1(v)

@Composable
private fun stateText(s: WaterState): String = stringResource(
    when (s) {
        WaterState.CLEAR -> R.string.state_clear
        WaterState.GETTING_CLOUDY -> R.string.state_getting_cloudy
        WaterState.CLOUDY -> R.string.state_cloudy
    }
)

/** Short state word for "Last seen 10:42: Clear". */
@Composable
private fun stateWord(s: WaterState): String = stringResource(
    when (s) {
        WaterState.CLEAR -> R.string.state_clear
        WaterState.GETTING_CLOUDY -> R.string.state_getting_cloudy
        WaterState.CLOUDY -> R.string.word_cloudy
    }
)

/** "just now", "5 seconds ago", "3 minutes ago", "2 hours ago" in the app language. */
@Composable
private fun agoText(ts: Long, now: Long): String {
    val res = LocalContext.current.resources
    val s = ((now - ts) / 1000).coerceAtLeast(0).toInt()
    return when {
        s < 2 -> stringResource(R.string.time_just_now)
        s < 60 -> res.getQuantityString(R.plurals.time_seconds_ago, s, s)
        s < 3600 -> (s / 60).let { res.getQuantityString(R.plurals.time_minutes_ago, it, it) }
        else -> (s / 3600).let { res.getQuantityString(R.plurals.time_hours_ago, it, it) }
    }
}

/** "10:42" (or "3:42 PM"), with the day in front when it is not today. */
@Composable
private fun clockText(ts: Long): String {
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val skeleton = (if (sameDay) "" else "dMMM ") + (if (DateFormat.is24HourFormat(ctx)) "Hm" else "hm")
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ts))
}

/** Wall clock that ticks every second, for the "updated … ago" line. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    return now
}

// ------------------------------------------------------------------- Now

/**
 * Residents' main screen: "can I drink this water right now?" in everyday words.
 * Only reads the view-model flows; no buttons that change the sensor.
 */
@Composable
fun NowScreen(vm: AppViewModel) {
    val screen by vm.screen.collectAsState()
    val station by vm.station.collectAsState()
    val history by vm.history24h.collectAsState()
    val needsCleaning by vm.needsCleaning.collectAsState()
    val wifiSupported by vm.wifiSupported.collectAsState()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    // Chart limit line: the live limit, or the last one seen while not connected.
    var lastLimit by remember { mutableDoubleStateOf(DEFAULT_LIMIT) }
    val limit = (screen as? Screen.Live)?.limit ?: lastLimit
    SideEffect { lastLimit = limit }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                station.ifBlank { stringResource(R.string.app_name) },
                Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            IconButton({ showAbout = true }) {
                Icon(Icons.Outlined.Info, stringResource(R.string.about_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!wifiSupported) JoinWifiHint(screen)

        when (val s = screen) {
            is Screen.Live -> LiveStatus(s)
            Screen.Connecting -> GreyStatus("…", pulsing = true, title = stringResource(R.string.connecting), sub = null)
            // Wrench, not "!": must not look like the amber "Getting cloudy" mark.
            Screen.SensorProblem -> GreyStatus("", pulsing = false, icon = Icons.Default.Build,
                title = stringResource(R.string.sensor_problem), sub = stringResource(R.string.tell_caretaker))
            is Screen.NotConnected -> GreyStatus("?", pulsing = false,
                title = stringResource(R.string.not_connected), sub = stringResource(R.string.go_near)) {
                val seen = s.lastSeen
                val st = s.lastState
                if (seen != null && st != null) Caption(stringResource(R.string.last_seen, clockText(seen.tsMs), stateWord(st)))
            }
        }

        if (needsCleaning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(Amber); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.needs_cleaning), color = amberText(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        DayChart(history, limit)
    }

    if (showAbout) AboutSheet { showAbout = false }
}

@Composable
private fun clarityTitle(c: ClarityLevel): String = stringResource(
    when (c) {
        ClarityLevel.EXCELLENT -> R.string.clarity_excellent
        ClarityLevel.GOOD -> R.string.clarity_good
        ClarityLevel.FAIR -> R.string.clarity_fair
        ClarityLevel.POOR -> R.string.clarity_poor
        ClarityLevel.VERY_POOR -> R.string.clarity_very_poor
    }
)

@Composable
private fun clarityDesc(c: ClarityLevel): String = stringResource(
    when (c) {
        ClarityLevel.EXCELLENT -> R.string.clarity_desc_excellent
        ClarityLevel.GOOD -> R.string.clarity_desc_good
        ClarityLevel.FAIR -> R.string.clarity_desc_fair
        ClarityLevel.POOR -> R.string.clarity_desc_poor
        ClarityLevel.VERY_POOR -> R.string.clarity_desc_very_poor
    }
)

@Composable
private fun LiveStatus(s: Screen.Live) {
    val clarity = clarityLevel(s.ntu)
    StatusOrb(s.state, pulsing = true, clarity = clarity)
    Caption(stringResource(R.string.clarity_header))
    Text(
        clarityTitle(clarity),
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        textAlign = TextAlign.Center,
        color = clarityColor(clarity)
    )
    Text(
        clarityDesc(clarity),
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    TurbidityBar(
        s.ntu, s.limit, s.state,
        stringResource(R.string.clarity_excellent),
        stringResource(R.string.clarity_very_poor),
        Modifier.fillMaxWidth()
    )
    LiveLine(s.updatedAtMs)
    if (s.ntu > s.limit) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = Red.copy(alpha = 0.14f)) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.pnsdw_warning_title),
                    fontWeight = FontWeight.Bold,
                    color = Red,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pnsdw_warning_body, f1(s.ntu), plainNumber(s.limit)),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Small "Live · updated …" line with a softly pulsing dot. Only this line ticks every second. */
@Composable
private fun LiveLine(updatedAtMs: Long) {
    val now = rememberNow()
    val text = stringResource(R.string.live_updated, agoText(updatedAtMs, now))
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.alpha(a)) { Dot(Blue, 8.dp) }
        Spacer(Modifier.width(6.dp))
        Caption(text)
    }
}

@Composable
private fun GreyStatus(
    mark: String, pulsing: Boolean, title: String, sub: String?,
    icon: ImageVector? = null, extra: @Composable () -> Unit = {},
) {
    StatusOrb(null, pulsing = pulsing, greyMark = mark, greyIcon = icon)
    Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp, textAlign = TextAlign.Center)
    if (sub != null) Text(sub, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    extra()
}

@Composable
private fun DayChart(history: List<Point>, limit: Double) {
    val values = remember(history) {
        val step = (history.size + CHART_MAX_POINTS - 1) / CHART_MAX_POINTS
        if (step <= 1) history.map { it.ntu } else history.chunked(step).map { c -> c.maxOf { it.ntu } }
    }
    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.last_24h), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        TrendChart(values, null, limit, stringResource(R.string.chart_empty), height = 100.dp)
        if (values.size >= 2) {
            val firstTs = history.firstOrNull()?.tsMs
            val lastTs = history.lastOrNull()?.tsMs
            val startLabel = if (firstTs != null && lastTs != null && lastTs - firstTs < 23 * 3_600_000L) {
                clockText(firstTs)
            } else {
                stringResource(R.string.chart_start)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Caption(startLabel); Spacer(Modifier.weight(1f)); Caption(stringResource(R.string.chart_now))
            }
        }
    }
}

/**
 * Android 9 and older cannot join the station Wi-Fi by itself: tell the resident once how to do it.
 * Hidden for good after "Got it" or the first live reading.
 */
@Composable
private fun JoinWifiHint(screen: Screen) {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { ctx.getSharedPreferences("aquasense", Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prefs.getBoolean(KEY_JOIN_HINT_DONE, false)) }
    fun finish() { done = true; prefs.edit().putBoolean(KEY_JOIN_HINT_DONE, true).apply() }
    LaunchedEffect(screen is Screen.Live) { if (screen is Screen.Live && !done) finish() }
    if (done || screen !is Screen.NotConnected) return
    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.join_wifi, AQUASENSE_SSID), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Caption(stringResource(R.string.join_wifi_how, AQUASENSE_PASS))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({
                try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
            }) { Text(stringResource(R.string.open_wifi_settings)) }
            TextButton(::finish) { Text(stringResource(R.string.got_it)) }
        }
    }
}

// ------------------------------------------------------------- formatting

private const val CAL_REMINDER_DAYS = 30

private fun localeOf(ctx: Context): Locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()

private fun fmt(ctx: Context, skeleton: String, ts: Long): String {
    val locale = localeOf(ctx)
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ts))
}

/** "14:00" or "2:00 PM", following the phone's 24-hour setting. */
private fun timeOfDay(ctx: Context, ts: Long): String =
    fmt(ctx, if (DateFormat.is24HourFormat(ctx)) "Hm" else "hm", ts)

private fun localDate(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Today", "Yesterday", else "Sat 14 Nov". */
private fun dayLabel(ctx: Context, dayStartMs: Long): String =
    when (ChronoUnit.DAYS.between(localDate(dayStartMs), LocalDate.now())) {
        0L -> ctx.getString(R.string.day_today)
        1L -> ctx.getString(R.string.day_yesterday)
        else -> fmt(ctx, "EEEdMMM", dayStartMs)
    }

/** "2 h", "45 min", "1 h 30 min". */
private fun durationText(ctx: Context, minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> ctx.getString(R.string.dur_minutes, m)
        m == 0 -> ctx.getString(R.string.dur_hours, h)
        else -> ctx.getString(R.string.dur_hours_minutes, h, m)
    }
}

/** "Clear all day" / "Got a bit cloudy" / "Cloudy for 2 h (14:00–16:00)". No units. */
fun daySummaryText(ctx: Context, d: DaySummary): String = when (d.worst) {
    WaterState.CLEAR -> ctx.getString(R.string.day_clear)
    WaterState.GETTING_CLOUDY -> ctx.getString(R.string.day_getting_cloudy)
    WaterState.CLOUDY -> {
        val first = d.firstCloudyMs ?: d.dayStartMs
        // Each point is one minute: the cloudy time ends at the end of the last cloudy minute.
        val last = (d.lastCloudyMs ?: first) + 60_000L
        ctx.getString(
            R.string.day_cloudy, durationText(ctx, d.cloudyMinutes.coerceAtLeast(1)),
            timeOfDay(ctx, first), timeOfDay(ctx, last)
        )
    }
}

/** Clock time with the day in front when not today; a range end drops the day when it matches the start. */
private fun stamp(ctx: Context, ts: Long, sameDayAs: Long? = null): String {
    val d = localDate(ts)
    val dayless = d == LocalDate.now() || (sameDayAs != null && d == localDate(sameDayAs))
    return if (dayless) timeOfDay(ctx, ts) else fmt(ctx, "dMMM", ts) + " " + timeOfDay(ctx, ts)
}

private fun alertText(ctx: Context, a: AlertRow): String {
    val closed = a.closedMs
    return if (closed == null) ctx.getString(R.string.alert_open, stamp(ctx, a.openedMs))
    else ctx.getString(R.string.alert_closed, stamp(ctx, a.openedMs), stamp(ctx, closed, sameDayAs = a.openedMs))
}

/**
 * Plain-text report for the health office (may use NTU): station, period, daily summaries, cloudy
 * alerts, last calibration and the not-a-certificate disclaimer. Never says "safe".
 */
fun buildReport(
    ctx: Context, station: String, limit: Double, days: List<DaySummary>, alerts: List<AlertRow>,
    lastCalMs: Long?, nowMs: Long,
): String {
    fun date(ts: Long) = fmt(ctx, "dMMMyyyy", ts)
    fun dateTime(ts: Long) = date(ts) + " " + timeOfDay(ctx, ts)
    val periodStart = days.minOfOrNull { it.dayStartMs } ?: alerts.minOfOrNull { it.openedMs } ?: nowMs
    val inPeriod = alerts.filter { it.openedMs >= periodStart }.sortedBy { it.openedMs }
    return buildString {
        appendLine(ctx.getString(R.string.report_title))
        appendLine(ctx.getString(R.string.report_made, dateTime(nowMs)))
        appendLine(ctx.getString(R.string.report_station, station))
        appendLine(ctx.getString(R.string.report_period, date(periodStart), date(nowMs)))
        appendLine(ctx.getString(R.string.report_limit, plainNumber(limit)))
        appendLine()
        appendLine(ctx.getString(R.string.report_days))
        if (days.isEmpty()) appendLine("- " + ctx.getString(R.string.report_none))
        for (d in days.sortedBy { it.dayStartMs }) {
            appendLine(
                "- " + ctx.getString(
                    R.string.report_day, fmt(ctx, "EEEdMMM", d.dayStartMs), daySummaryText(ctx, d), f1(d.avgNtu)
                )
            )
        }
        appendLine()
        appendLine(ctx.getString(R.string.report_alerts))
        if (inPeriod.isEmpty()) appendLine("- " + ctx.getString(R.string.report_none))
        for (a in inPeriod) {
            val closed = a.closedMs
            val line = if (closed == null) ctx.getString(R.string.report_alert_open, dateTime(a.openedMs), f1(a.peakNtu))
            else ctx.getString(R.string.report_alert_closed, dateTime(a.openedMs), dateTime(closed), f1(a.peakNtu))
            appendLine("- $line")
        }
        appendLine()
        appendLine(
            if (lastCalMs == null) ctx.getString(R.string.report_never_calibrated)
            else ctx.getString(R.string.report_calibrated, date(lastCalMs))
        )
        appendLine()
        append(ctx.getString(R.string.report_disclaimer))
    }
}

// --------------------------------------------------------------- History

private sealed interface HistoryEvent {
    val ts: Long
    data class Alert(val a: AlertRow) : HistoryEvent { override val ts: Long get() = a.openedMs }
    data class Cal(val c: CalEvent) : HistoryEvent { override val ts: Long get() = c.tsMs }
}

@Composable
private fun ScreenTitle(text: String) =
    Text(text, Modifier.fillMaxWidth(), fontSize = 20.sp, fontWeight = FontWeight.Bold)

@Composable
private fun SectionTitle(text: String) = Text(text, fontWeight = FontWeight.SemiBold)

/** Last 24 hours, one row per day, alerts and calibrations, and the Share report button. */
@Composable
fun HistoryScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val history by vm.history24h.collectAsState()
    val days by vm.days.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val calEvents by vm.calEvents.collectAsState()
    val limit by vm.currentLimit.collectAsState()
    val station by vm.station.collectAsState()
    val lastCalMs by vm.lastCalMs.collectAsState()
    val events = remember(alerts, calEvents) {
        (alerts.map { HistoryEvent.Alert(it) } + calEvents.map { HistoryEvent.Cal(it) }).sortedByDescending { it.ts }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle(stringResource(R.string.tab_history))
        if (history.isEmpty() && days.isEmpty() && events.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.history_empty), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Caption(stringResource(R.string.history_empty_hint))
            }
            return@Column
        }

        DayChart(history, limit)

        if (days.isNotEmpty()) Panel(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.history_days))
            days.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DayRow(d)
            }
        }

        if (events.isNotEmpty()) Panel(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.history_events))
            events.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (e) {
                    is HistoryEvent.Alert -> EventRow(Red, alertText(ctx, e.a), null)
                    is HistoryEvent.Cal -> EventRow(Blue, stringResource(R.string.cal_event), stamp(ctx, e.c.tsMs))
                }
            }
        }

        val shareTitle = stringResource(R.string.share_report)
        OutlinedButton(
            onClick = {
                val text = buildReport(ctx, station, limit, days, alerts, lastCalMs, System.currentTimeMillis())
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.report_title))
                    .putExtra(Intent.EXTRA_TEXT, text)
                try { ctx.startActivity(Intent.createChooser(send, shareTitle)) } catch (_: Exception) {}
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(shareTitle)
        }
    }
}

@Composable
private fun DayRow(d: DaySummary) {
    val ctx = LocalContext.current
    val dayClarity = if (d.worst == WaterState.CLOUDY) ClarityLevel.FAIR else clarityLevel(d.avgNtu)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(clarityColor(dayClarity), 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dayLabel(ctx, d.dayStartMs), fontWeight = FontWeight.SemiBold)
            Text("${clarityTitle(clarityLevel(d.avgNtu))} · ${daySummaryText(ctx, d)}", fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Caption("${f1(d.avgNtu)} NTU")
    }
}

@Composable
private fun EventRow(color: Color, title: String, sub: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (sub != null) Caption(sub)
        }
    }
}

// -------------------------------------------------------------- Settings

private val languages = listOf(
    AppLanguage.SYSTEM to R.string.lang_system,
    AppLanguage.EN to R.string.lang_en,
    AppLanguage.FIL to R.string.lang_fil,
    AppLanguage.CEB to R.string.lang_ceb,
)

/** The ESP32 keeps at most 40 characters of the station name. */
private const val STATION_MAX = 40

/** Language, station name, Sensor care and the About-the-colours sheet. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val language by vm.language.collectAsState()
    val station by vm.station.collectAsState()
    val reachable by vm.sensorReachable.collectAsState()
    val stationFailed by vm.stationFailed.collectAsState()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenTitle(stringResource(R.string.tab_settings))

        Panel(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.settings_language))
            Column(Modifier.selectableGroup()) {
                languages.forEach { (lang, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(language == lang, role = Role.RadioButton) { vm.setLanguage(lang) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(language == lang, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            var name by rememberSaveable(station) { mutableStateOf(station) }
            val trimmed = name.trim()
            OutlinedTextField(
                name, { if (it.length <= STATION_MAX) name = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_station)) }, singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Caption(stringResource(if (reachable) R.string.station_hint else R.string.station_needs_wifi))
            if (stationFailed) Caption(stringResource(R.string.station_failed), color = Red)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button({ vm.setStation(trimmed) }, enabled = reachable && trimmed.isNotEmpty() && trimmed != station) {
                    Text(stringResource(R.string.station_save))
                }
            }
        }

        SensorCare(vm, reachable)

        ConnectionsPanel(vm)

        Panel(Modifier.fillMaxWidth().clickable { showAbout = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.about_title), Modifier.weight(1f))
            }
        }
    }
    if (showAbout) AboutSheet { showAbout = false }
}

/** Last calibrated, 30-day reminder, sensor health line, and the calibration flow. */
@Composable
private fun SensorCare(vm: AppViewModel, reachable: Boolean) {
    val ctx = LocalContext.current
    val session by vm.calSession.collectAsState()
    val lastCalMs by vm.lastCalMs.collectAsState()
    val needsCleaning by vm.needsCleaning.collectAsState()
    val screen by vm.screen.collectAsState()
    val transport by vm.transport.collectAsState()
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.sensor_care))
        if (transport == Transport.BLE && screen is Screen.Live) {
            Caption(stringResource(R.string.via_bluetooth))
        }
        Spacer(Modifier.height(8.dp))
        // One calendar-day count drives both the label and the reminder.
        val ago = lastCalMs?.let { ChronoUnit.DAYS.between(localDate(it), LocalDate.now()).toInt().coerceAtLeast(0) }
        Text(
            when (ago) {
                null -> stringResource(R.string.cal_never)
                0 -> stringResource(R.string.cal_today)
                else -> ctx.resources.getQuantityString(R.plurals.cal_days_ago, ago, ago)
            }
        )
        if (ago == null || ago >= CAL_REMINDER_DAYS) {
            Text(stringResource(R.string.cal_reminder), color = amberText(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        val health: Pair<Color, String>? = when {
            screen == Screen.SensorProblem -> Red to stringResource(R.string.sensor_problem)
            needsCleaning -> Amber to stringResource(R.string.needs_cleaning)
            screen is Screen.Live -> Blue to stringResource(R.string.sensor_ok)
            else -> null
        }
        if (health != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(health.first)
                Spacer(Modifier.width(8.dp))
                Text(
                    health.second, fontSize = 14.sp,
                    color = if (health.first == Amber) amberText() else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        CalibrationSteps(
            session = session, reachable = reachable,
            onStart = vm::startCalibration,
            onClose = vm::stopCalibration,
        )
    }
}

/** Interactive Wi-Fi & Bluetooth scanning, pairing, and connecting panel. */
@Composable
private fun ConnectionsPanel(vm: AppViewModel) {
    val wifiScanning by vm.joiner.scanning.collectAsState()
    val wifiNetworks by vm.joiner.networks.collectAsState()
    val connectedSsid by vm.joiner.connectedSsid.collectAsState()

    val btScanning by vm.ble.scanningDevices.collectAsState()
    val btDevices by vm.ble.devices.collectAsState()
    val connectedBt by vm.ble.connectedDevice.collectAsState()

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) pendingAction?.invoke()
        pendingAction = null
    }
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (vm.ble.isBluetoothEnabled()) vm.ble.scanDevices()
    }

    var wifiPromptTarget by remember { mutableStateOf<ScannedWifi?>(null) }

    Panel(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.conn_title))
        Spacer(Modifier.height(8.dp))

        // ---- Wi-Fi Scan & Connect
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    pendingAction = { vm.joiner.scan() }
                    permLauncher.launch(blePermissionsForRuntime())
                },
                modifier = Modifier.weight(1f),
                enabled = !wifiScanning
            ) {
                Text(stringResource(if (wifiScanning) R.string.conn_scanning else R.string.conn_scan_wifi))
            }
            OutlinedButton(
                onClick = {
                    if (btScanning) {
                        vm.ble.stopDeviceScan()
                    } else {
                        pendingAction = {
                            if (!vm.ble.isBluetoothEnabled()) {
                                try {
                                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                } catch (_: Exception) {}
                            } else {
                                vm.ble.scanDevices()
                            }
                        }
                        permLauncher.launch(blePermissionsForRuntime())
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(if (btScanning) R.string.conn_stop_scan else R.string.conn_scan_bt))
            }
        }

        if (wifiNetworks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            wifiNetworks.take(8).forEach { net ->
                val isConn = connectedSsid == net.ssid
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(if (isConn) Blue else if (net.isAquaSense) Amber else Grey, 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(net.ssid, fontWeight = if (net.isAquaSense) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                        Caption("${net.rssi} dBm" + if (isConn) " · " + stringResource(R.string.conn_connected) else "")
                    }
                    if (isConn) {
                        TextButton({ vm.joiner.stop() }) { Text(stringResource(R.string.conn_disconnect), fontSize = 12.sp) }
                    } else {
                        Button(
                            onClick = {
                                if (net.secured) wifiPromptTarget = net
                                else vm.joiner.connectTo(net.ssid, null)
                            }
                        ) {
                            Text(stringResource(R.string.conn_connect), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (btDevices.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))
            btDevices.take(8).forEach { dev ->
                val isConn = connectedBt?.address == dev.address
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Dot(if (isConn) Blue else if (dev.isPaired) Amber else Grey, 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dev.name, fontWeight = if (dev.isAquaSense) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                        val bondTxt = when {
                            isConn -> stringResource(R.string.conn_connected)
                            dev.isPairing -> stringResource(R.string.conn_pairing)
                            dev.isPaired -> stringResource(R.string.conn_paired)
                            else -> dev.address
                        }
                        Caption(bondTxt)
                    }
                    if (!dev.isPaired) {
                        TextButton(
                            onClick = { vm.ble.pairDevice(dev.address) },
                            enabled = !dev.isPairing
                        ) {
                            Text(stringResource(if (dev.isPairing) R.string.conn_pairing else R.string.conn_pair), fontSize = 12.sp)
                        }
                    } else if (!isConn) {
                        TextButton(onClick = { vm.ble.unpairDevice(dev.address) }) {
                            Text(stringResource(R.string.conn_unpair), fontSize = 12.sp, color = Grey)
                        }
                    }
                    if (isConn) {
                        OutlinedButton(onClick = { vm.ble.stop() }) {
                            Text(stringResource(R.string.conn_disconnect), fontSize = 12.sp)
                        }
                    } else {
                        Button(onClick = { vm.ble.connectToDevice(dev.address) }) {
                            Text(stringResource(R.string.conn_connect), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    wifiPromptTarget?.let { target ->
        var pass by remember(target) {
            mutableStateOf(if (target.ssid == AQUASENSE_SSID) AQUASENSE_PASS else "")
        }
        AlertDialog(
            onDismissRequest = { wifiPromptTarget = null },
            title = { Text(target.ssid) },
            text = {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.conn_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                Button({
                    val s = target.ssid
                    wifiPromptTarget = null
                    vm.joiner.connectTo(s, pass)
                }) { Text(stringResource(R.string.conn_connect)) }
            },
            dismissButton = {
                TextButton({ wifiPromptTarget = null }) { Text(stringResource(R.string.conn_cancel)) }
            }
        )
    }
}
