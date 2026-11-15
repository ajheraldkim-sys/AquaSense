package com.magosilom.aquasense

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private val ScreenPad = Modifier.padding(horizontal = 20.dp)

@Composable
private fun ScreenTitle(text: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))

private fun turbText(v: Double) = if (v < 100) f1(v) else v.roundToInt().toString()

// ======================================================================= NOW

@Composable
fun NowScreen(vm: AppViewModel, openHistory: () -> Unit) {
    val sources by vm.sources.collectAsState()
    val readings by vm.readings.collectAsState()
    val limits by vm.limits.collectAsState()
    val location by vm.location.collectAsState()
    val sel by vm.selected.collectAsState()
    val live by vm.live.collectAsState()
    val liveSample by vm.liveSample.collectAsState()
    val link by vm.link.collectAsState()
    val lastContact by vm.lastContact.collectAsState()
    val measuring by vm.measuring.collectAsState()
    val progress by vm.progress.collectAsState()
    val alerts by vm.alerts.collectAsState()

    var info by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val src = sources.firstOrNull { it.id == sel }
    val history = remember(readings, sel) { readings.filter { it.sourceId == sel } }
    val latest = history.firstOrNull()
    // While Live is on, show the smoothed live value; otherwise the last saved reading.
    val shown: Reading? = liveSample?.let { Reading(sourceId = sel, ts = now, turbidity = it.turbidity) } ?: latest
    val a = shown?.let { assess(it, limits) }
    val openAlerts = alerts.count { !it.resolved }

    Column(Modifier.verticalScroll(rememberScrollState()).then(ScreenPad).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ---- header: which source + connection
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SourcePicker(sources, src) { vm.selected.value = it }
                Caption(location)
            }
            LinkPill(link, lastContact, now)
        }

        // ---- hero
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusOrb(a?.level, pulsing = live && link == Link.ONLINE)
            Text(a?.level?.label ?: "No reading yet", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                color = if (a == null) MaterialTheme.colorScheme.onSurface else levelColor(a.level))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(a?.headline ?: "Tap Measure to check the water", textAlignCenter = true)
                IconButton({ info = true }, Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Info, "What does this mean?", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (shown != null) Caption(
                if (liveSample != null) "Live · smoothed over a few seconds" else "Measured ${friendlyTime(shown.ts)}",
                color = Grey
            )
        }

        // ---- turbidity
        TurbidityBar(shown?.turbidity, limits.turbMax, a?.level)

        // ---- actions
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ vm.measure() }, Modifier.weight(1f).height(56.dp), enabled = !measuring && !live,
                shape = RoundedCornerShape(18.dp)) {
                if (measuring) {
                    CircularProgressIndicator({ progress }, Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp, trackColor = Color.Transparent)
                    Spacer(Modifier.width(10.dp)); Text("Measuring…", fontSize = 16.sp)
                } else {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(if (live) "Live is on" else "Measure", fontSize = 16.sp)
                }
            }
            Surface(modifier = Modifier.height(56.dp), shape = RoundedCornerShape(18.dp),
                color = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                onClick = { vm.setLive(!live) }) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live", fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp))
                    Switch(live, { vm.setLive(it) })
                }
            }
        }
        if (live) Caption("Live updates every 2 s and saves a reading every 30 s while the app is open.",
            Modifier.fillMaxWidth().padding(horizontal = 8.dp), color = Grey)

        // ---- what's wrong + what to do (one line each)
        a?.issue?.let { IssueRow(it) }

        if (openAlerts > 0) {
            Surface(onClick = openHistory, shape = MaterialTheme.shapes.medium, color = Amber.copy(alpha = 0.15f)) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = Amber); Spacer(Modifier.width(12.dp))
                    Text("$openAlerts open alert${if (openAlerts > 1) "s" else ""}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }

        // ---- trend
        Panel(Modifier.clickable(onClick = openHistory)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Turbidity trend", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Caption("last ${minOf(history.size, 20)}")
            }
            Spacer(Modifier.height(12.dp))
            TrendChart(history.take(20).reversed().map { it.turbidity }, null, limits.turbMax, height = 96.dp)
        }
    }
    if (info) AboutSheet { info = false }
}

@Composable
private fun Caption(text: String, textAlignCenter: Boolean) =
    Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (textAlignCenter) TextAlign.Center else TextAlign.Start)

@Composable
private fun SourcePicker(sources: List<Source>, current: Source?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable(enabled = sources.size > 1) { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(current?.name ?: "—", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (sources.size > 1) Icon(Icons.Default.ArrowDropDown, "Change source")
        }
        DropdownMenu(open, { open = false }) {
            sources.forEach { s ->
                DropdownMenuItem({ Text(s.name) }, { onPick(s.id); open = false },
                    trailingIcon = { if (s.id == current?.id) Icon(Icons.Default.Check, null) })
            }
        }
    }
}

@Composable
private fun LinkPill(link: Link, last: Long?, now: Long) {
    val (c, t) = when (link) {
        Link.ONLINE -> Blue to "Connected"
        Link.CONNECTING -> Amber to "Connecting…"
        Link.OFFLINE -> Red to "Offline"
        Link.IDLE -> Grey to (last?.let { "Seen ${ago(it, now)}" } ?: "Not connected")
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(c, 8.dp); Spacer(Modifier.width(6.dp)); Text(t, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun IssueRow(i: Issue) {
    val c = Red
    Surface(shape = MaterialTheme.shapes.medium, color = c.copy(alpha = 0.12f)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = c)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(i.short, fontWeight = FontWeight.SemiBold)
                Caption(i.tip)
            }
        }
    }
}

// =================================================================== HISTORY

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val sources by vm.sources.collectAsState()
    val readings by vm.readings.collectAsState()
    val limits by vm.limits.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val sel by vm.selected.collectAsState()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }

    val rs = remember(readings, sel) { readings.filter { it.sourceId == sel } }
    val open = alerts.filter { !it.resolved }
    val series = rs.take(50).reversed().map { it.turbidity }

    LazyColumn(ScreenPad, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenTitle("History") {
                IconButton({
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "AquaSense report – ${vm.location.value}")
                        putExtra(Intent.EXTRA_TEXT, buildReport(vm))
                    }
                    ctx.startActivity(Intent.createChooser(i, "Share report"))
                }) { Icon(Icons.Default.Share, "Share report") }
                IconButton({ confirm = true }, enabled = rs.isNotEmpty()) { Icon(Icons.Default.Delete, "Clear history") }
            }
        }
        if (sources.size > 1) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { s -> FilterChip(s.id == sel, { vm.selected.value = s.id }, { Text(s.name) }) }
            }
        }

        // ---- open alerts
        if (open.isNotEmpty()) item {
            Surface(shape = MaterialTheme.shapes.medium, color = Amber.copy(alpha = 0.12f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Notifications, null, tint = Amber); Spacer(Modifier.width(10.dp))
                        Text("Open alerts", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton({ vm.resolveAll() }) { Text("Resolve all") }
                    }
                    open.take(5).forEach { al ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(al.message, fontSize = 14.sp)
                                Caption("${sources.firstOrNull { it.id == al.sourceId }?.name ?: "Removed source"} · ${friendlyTime(al.ts)}")
                            }
                            IconButton({ vm.resolveAlert(al.id) }) { Icon(Icons.Default.Check, "Mark resolved") }
                        }
                    }
                    if (open.size > 5) Caption("+${open.size - 5} more")
                }
            }
        }

        // ---- chart
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Turbidity", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Caption("NTU · last ${series.size}")
                }
                Spacer(Modifier.height(12.dp))
                TrendChart(series, null, limits.turbMax, height = 160.dp)
                if (series.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Stat("Low", turbText(series.min()), Modifier.weight(1f))
                        Stat("Average", turbText(series.average()), Modifier.weight(1f))
                        Stat("High", turbText(series.max()), Modifier.weight(1f))
                    }
                }
            }
        }

        // ---- list
        if (rs.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Caption("No readings yet. Go to Now and tap Measure.")
            }
        }
        items(rs, key = { it.id }) { r ->
            val a = assess(r, limits)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(levelColor(a.level), 12.dp); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(friendlyTime(r.ts), fontWeight = FontWeight.Medium)
                    Caption(a.level.label)
                }
                Text("${turbText(r.turbidity)} NTU", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Clear history?") },
        text = { Text("Deletes all readings for ${sources.firstOrNull { it.id == sel }?.name ?: "this source"}.") },
        confirmButton = { TextButton({ vm.clearHistory(sel); confirm = false }) { Text("Delete", color = Red) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Caption(label)
    }
}

fun buildReport(vm: AppViewModel): String {
    val sb = StringBuilder("AquaSense Water Quality Report\n${vm.location.value}\nGenerated: ${fmtTime(System.currentTimeMillis())}\n")
    val l = vm.limits.value
    sb.append("Limit: turbidity max ${l.turbMax} NTU\n\n")
    vm.sources.value.forEach { s ->
        val rs = vm.readings.value.filter { it.sourceId == s.id }
        sb.append("${s.name} (${s.type}, ${s.location})\n")
        if (rs.isEmpty()) { sb.append("  No readings.\n\n"); return@forEach }
        val over = rs.count { assess(it, l).level == Level.UNSAFE }
        sb.append("  Readings: ${rs.size} (${fmtTime(rs.last().ts)} to ${fmtTime(rs.first().ts)})\n")
        sb.append("  Above the limit: $over\n")
        sb.append("  Turbidity avg ${f2(rs.map { it.turbidity }.average())} NTU " +
            "(min ${f2(rs.minOf { it.turbidity })}, max ${f2(rs.maxOf { it.turbidity })})\n\n")
    }
    sb.append("Open alerts: ${vm.alerts.value.count { !it.resolved }}\n\n$DISCLAIMER")
    return sb.toString()
}

// ================================================================== SETTINGS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val limits by vm.limits.collectAsState()
    val esp by vm.esp.collectAsState()
    val location by vm.location.collectAsState()
    val sources by vm.sources.collectAsState()
    val testStatus by vm.testStatus.collectAsState()
    val testingEsp by vm.testingEsp.collectAsState()

    var url by remember(esp) { mutableStateOf(esp.url) }
    var loc by remember(location) { mutableStateOf(location) }
    // The slider state must stay the same instance: M3 sliders keep their first callbacks, so
    // re-creating state would make the slider later save a stale value. Sync from storage instead.
    var turb by remember { mutableFloatStateOf(limits.turbMax.toFloat()) }
    LaunchedEffect(limits) { turb = limits.turbMax.toFloat() }
    var editing by remember { mutableStateOf<Source?>(null) }
    var deleting by remember { mutableStateOf<Source?>(null) }
    var about by remember { mutableStateOf(false) }

    fun r1(f: Float) = (f * 10).roundToInt() / 10.0
    fun commitTurb() = vm.saveLimits(Limits(turbMax = r1(turb)))

    Column(Modifier.verticalScroll(rememberScrollState()).then(ScreenPad).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Settings")

        // ---- sensor
        SectionLabel("Sensor")
        Panel {
            OutlinedTextField(url, { url = it }, label = { Text("ESP32 address") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ vm.testEspConnection(url) }, Modifier.weight(1f), enabled = !testingEsp) {
                    if (testingEsp) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test")
                }
                Button({
                    val u = url.trim()
                    if (u.startsWith("http://") || u.startsWith("https://")) { vm.saveEsp(EspConfig(u)); vm.message.value = "Address saved" }
                    else vm.message.value = "Address must start with http://"
                }, Modifier.weight(1f), enabled = url.trim() != esp.url) { Text("Save") }
            }
            testStatus?.let { (ok, text) ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (ok) Blue else Red); Spacer(Modifier.width(10.dp)); Caption(text)
                }
            }
        }

        // ---- sources
        SectionLabel("Water sources")
        Panel(padding = 8.dp) {
            sources.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Medium)
                        Caption(listOf(s.type, s.location).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                    if (sources.size > 1) IconButton({ deleting = s }) { Icon(Icons.Default.Delete, "Delete", tint = Grey) }
                }
            }
            TextButton({ editing = Source(name = "", location = "", type = "") }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add source")
            }
        }

        // ---- limits
        SectionLabel("Limit")
        Panel {
            LimitRow("Turbidity", "up to ${f1(turb.toDouble())} NTU")
            Slider(turb, { turb = it }, valueRange = 1f..25f, steps = 47, onValueChangeFinished = ::commitTurb)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Default 5 NTU. Lower is stricter.", Modifier.weight(1f))
                TextButton({ vm.saveLimits(Limits()) }, enabled = limits != Limits()) { Text("Reset") }
            }
        }

        // ---- station
        SectionLabel("Station")
        Panel {
            OutlinedTextField(loc, { loc = it }, label = { Text("Station / barangay name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (loc.trim() != location && loc.isNotBlank())
                        IconButton({ vm.saveLocation(loc); vm.message.value = "Saved" }) { Icon(Icons.Default.Check, "Save") }
                })
        }

        // ---- about
        Panel(Modifier.clickable { about = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("About the readings", fontWeight = FontWeight.Medium)
                    Caption("Colours, limits and why sensors can't prove water is safe")
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }

    editing?.let { s ->
        var name by remember(s) { mutableStateOf(s.name) }
        var sloc by remember(s) { mutableStateOf(s.location) }
        var type by remember(s) { mutableStateOf(s.type) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (sources.any { it.id == s.id }) "Edit source" else "New source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(type, { type = it }, label = { Text("Type (spring, well, tap…)") }, singleLine = true)
                    OutlinedTextField(sloc, { sloc = it }, label = { Text("Location / purok") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton({ vm.saveSource(s.copy(name = name.trim(), location = sloc.trim(), type = type.trim())); editing = null },
                    enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("Its readings and alerts are deleted too.") },
            confirmButton = { TextButton({ vm.deleteSource(s.id); deleting = null }) { Text("Delete", color = Red) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }
        )
    }
    if (about) AboutSheet { about = false }
}

@Composable
private fun LimitRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}
