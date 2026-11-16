package com.magosilom.aquasense

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

private const val KEY_JOIN_HINT_DONE = "join_wifi_hint_done"
/** The 24 h chart never draws more points than this (one per ~7 minutes of a full day). */
private const val CHART_MAX_POINTS = 200

fun WaterState.level(): Level = when (this) {
    WaterState.CLEAR -> Level.WITHIN
    WaterState.GETTING_CLOUDY -> Level.CAUTION
    WaterState.CLOUDY -> Level.UNSAFE
}

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
    val now = rememberNow()

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
            is Screen.Live -> LiveStatus(s, now)
            Screen.Connecting -> GreyStatus("…", pulsing = true, stringResource(R.string.connecting), null)
            Screen.SensorProblem -> GreyStatus("!", pulsing = false,
                stringResource(R.string.sensor_problem), stringResource(R.string.tell_caretaker))
            is Screen.NotConnected -> GreyStatus("?", pulsing = false,
                stringResource(R.string.not_connected), stringResource(R.string.go_near)) {
                val seen = s.lastSeen
                val st = s.lastState
                if (seen != null && st != null) Caption(stringResource(R.string.last_seen, clockText(seen.tsMs), stateWord(st)))
            }
        }

        if (needsCleaning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(Amber); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.needs_cleaning), color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        DayChart(history, limit)
    }

    if (showAbout) AboutSheet { showAbout = false }
}

@Composable
private fun LiveStatus(s: Screen.Live, now: Long) {
    val level = s.state.level()
    StatusOrb(level, pulsing = true)
    Text(stateText(s.state), fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp,
        textAlign = TextAlign.Center, color = if (s.state == WaterState.CLOUDY) Red else MaterialTheme.colorScheme.onBackground)
    Text(
        if (s.state == WaterState.GETTING_CLOUDY) stringResource(R.string.sub_check_soon)
        else stringResource(R.string.sub_cloudiness, plainNumber(s.ntu), plainNumber(s.limit)),
        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
    )
    TurbidityBar(s.ntu, s.limit, level, stringResource(R.string.state_clear), stringResource(R.string.word_cloudy),
        Modifier.fillMaxWidth())
    LiveLine(stringResource(R.string.live_updated, agoText(s.updatedAtMs, now)))
    if (s.state == WaterState.CLOUDY) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = Red.copy(alpha = 0.12f)) {
            Text(stringResource(R.string.tip_cloudy), Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
        }
    }
}

/** Small "Live · updated …" line with a softly pulsing dot. */
@Composable
private fun LiveLine(text: String) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.alpha(a)) { Dot(Blue, 8.dp) }
        Spacer(Modifier.width(6.dp))
        Caption(text)
    }
}

@Composable
private fun GreyStatus(mark: String, pulsing: Boolean, title: String, sub: String?, extra: @Composable () -> Unit = {}) {
    StatusOrb(null, pulsing = pulsing, greyMark = mark)
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
        if (values.size >= 2) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Caption(stringResource(R.string.chart_start)); Spacer(Modifier.weight(1f)); Caption(stringResource(R.string.chart_now))
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

// ------------------------------------------------- History / Settings (Task 11 rewrites)

@Composable
fun HistoryScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Updating…") }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { Text("Updating…") }
}
