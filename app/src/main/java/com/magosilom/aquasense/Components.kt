package com.magosilom.aquasense

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Status colour; null (no reading) is grey. */
fun stateColor(s: WaterState?) = when (s) {
    WaterState.CLOUDY -> Red; WaterState.GETTING_CLOUDY -> Amber; WaterState.CLEAR -> Blue; null -> Grey
}
fun stateIcon(s: WaterState?): ImageVector? = when (s) {
    WaterState.CLEAR -> Icons.Default.Check; WaterState.GETTING_CLOUDY -> Icons.Default.Warning
    WaterState.CLOUDY -> Icons.Default.Close; null -> null
}

/** 5-level Water Clarity Classification colour & icon. */
fun clarityColor(c: ClarityLevel?) = when (c) {
    ClarityLevel.EXCELLENT -> Green
    ClarityLevel.GOOD -> Blue
    ClarityLevel.FAIR -> Yellow
    ClarityLevel.POOR -> Orange
    ClarityLevel.VERY_POOR -> Red
    null -> Grey
}

fun clarityIcon(c: ClarityLevel?): ImageVector? = when (c) {
    ClarityLevel.EXCELLENT, ClarityLevel.GOOD -> Icons.Default.Check
    ClarityLevel.FAIR, ClarityLevel.POOR -> Icons.Default.Warning
    ClarityLevel.VERY_POOR -> Icons.Default.Close
    null -> null
}

/** Plain rounded surface used everywhere instead of heavy cards. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, modifier, fontSize = 13.sp, color = color)

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).background(color, CircleShape))

// ----------------------------------------------------------------- status orb

/**
 * The hero: a big soft circle in the status colour with a symbol in the middle.
 * Pulses gently while live data is flowing.
 */
@Composable
fun StatusOrb(
    state: WaterState?,
    pulsing: Boolean,
    size: Dp = 168.dp,
    greyMark: String = "?",
    greyIcon: ImageVector? = null,
    clarity: ClarityLevel? = null,
) {
    val targetColor = if (clarity != null) clarityColor(clarity) else stateColor(state)
    val color by animateColorAsState(targetColor, tween(600), label = "orb")
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    // Decorative: the big state text right below says the same thing, so TalkBack reads it once
    // (and never reads the glyph as "question mark" / "exclamation mark").
    Box(Modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2
            if (pulsing) drawCircle(color.copy(alpha = 0.18f * (1 - pulse)), radius = r * (0.78f + 0.22f * pulse))
            drawCircle(color.copy(alpha = 0.12f), radius = r * 0.78f)
            drawCircle(color.copy(alpha = 0.22f), radius = r * 0.62f)
            drawCircle(color, radius = r * 0.46f)
        }
        val icon = (if (clarity != null) clarityIcon(clarity) else stateIcon(state)) ?: greyIcon
        if (icon != null) Icon(icon, null, Modifier.size(size * 0.26f), tint = Color.White)
        else Text(greyMark, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

// --------------------------------------------------------------- turbidity bar

/**
 * 5-level Water Clarity Classification scale bar (NTU):
 *  - Zone 1 (0–1 NTU): Green (Excellent)
 *  - Zone 2 (>1–5 NTU): Blue (Good — PNSDW 2017 max limit at 5 NTU)
 *  - Zone 3 (>5–20 NTU): Yellow (Fair — exceeds limit)
 *  - Zone 4 (>20–50 NTU): Orange (Poor — exceeds limit)
 *  - Zone 5 (>50 NTU): Red (Very Poor — exceeds limit)
 */
@Composable
fun TurbidityBar(
    value: Double?, limit: Double, state: WaterState?, clearLabel: String, cloudyLabel: String,
    modifier: Modifier = Modifier,
) {
    val clarity = value?.let { clarityLevel(it) }
    val frac by animateFloatAsState(
        if (value == null) 0f else clarityScaleFraction(value), tween(700), label = "t"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = if (clarity != null) clarityColor(clarity) else stateColor(state)
    val numStr = value?.let { if (it < 100) f1(it) else it.toInt().toString() } ?: "—"
    Panel(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(R.string.ntu_reading_fmt, numStr),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 30.sp
            )
            Spacer(Modifier.weight(1f))
            Caption(stringResource(R.string.pnsdw_limit_label, plainNumber(limit)))
        }
        Spacer(Modifier.height(14.dp))
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val h = 12.dp.toPx(); val y = (size.height - h) / 2
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            val seg = size.width / 5f
            val bar = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, y, size.width, y + h, r)) }
            clipPath(bar) {
                drawRect(track, Offset(0f, y), Size(size.width, h))
                drawRect(Green.copy(alpha = 0.65f), Offset(0f * seg, y), Size(seg, h))
                drawRect(Blue.copy(alpha = 0.65f), Offset(1f * seg, y), Size(seg, h))
                drawRect(Yellow.copy(alpha = 0.75f), Offset(2f * seg, y), Size(seg, h))
                drawRect(Orange.copy(alpha = 0.75f), Offset(3f * seg, y), Size(seg, h))
                drawRect(Red.copy(alpha = 0.70f), Offset(4f * seg, y), Size(seg, h))
            }
            // Mark the 5 NTU PNSDW limit boundary (at 40% width, between Good and Fair)
            val xLimit = 2f * seg
            drawLine(
                color = Color.White,
                start = Offset(xLimit, y - 2.dp.toPx()),
                end = Offset(xLimit, y + h + 2.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
            if (value != null) {
                val cx = (frac * size.width).coerceIn(size.height / 2, size.width - size.height / 2)
                drawCircle(Color.White, size.height / 2, Offset(cx, size.height / 2))
                drawCircle(marker, size.height / 2 - 4.dp.toPx(), Offset(cx, size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("0", color = Grey)
            Caption("1", color = Grey)
            Caption("5 NTU", color = Blue)
            Caption("20", color = Grey)
            Caption("50+", color = Grey)
        }
    }
}

// ---------------------------------------------------------------- trend chart

/**
 * Line chart with the accepted band shaded. [values] are oldest → newest.
 * Points outside the band are drawn in red.
 */
@Composable
fun TrendChart(
    values: List<Double>, lo: Double?, hi: Double?, emptyText: String,
    modifier: Modifier = Modifier, height: Dp = 120.dp,
) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (values.size < 2) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Caption(emptyText)
        }
        return
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val all = values + listOfNotNull(lo, hi)
        var mn = all.min(); var mx = all.max()
        if (mx - mn < 1e-6) { mn -= 1; mx += 1 }
        val pad = (mx - mn) * 0.12
        mn -= pad; mx += pad
        fun y(v: Double) = (size.height * (1 - (v - mn) / (mx - mn))).toFloat()
        val bandTop = y(hi ?: mx); val bandBot = y(lo ?: mn)
        drawRect(Blue.copy(alpha = 0.10f), Offset(0f, bandTop), Size(size.width, bandBot - bandTop))
        listOfNotNull(lo, hi).forEach { drawLine(grid, Offset(0f, y(it)), Offset(size.width, y(it)), 2f) }

        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, y(v)) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        val fill = Path().apply { addPath(path); lineTo(pts.last().x, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, line.copy(alpha = 0.08f))
        drawPath(path, line, style = Stroke(5f, cap = StrokeCap.Round))
        values.forEachIndexed { i, v ->
            val out = (lo != null && v < lo) || (hi != null && v > hi)
            if (out) drawCircle(Red, 7f, pts[i])
        }
        val lastOut = (lo != null && values.last() < lo) || (hi != null && values.last() > hi)
        drawCircle(Color.White, 10f, pts.last()); drawCircle(if (lastOut) Red else line, 7f, pts.last())
    }
}

/** Short "i"-sheet: 5-level Water Clarity Classification + PNSDW 2017 & WHO standards + disclaimer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.clarity_header), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            LegendRow(Green, stringResource(R.string.clarity_excellent), stringResource(R.string.clarity_desc_excellent))
            LegendRow(Blue, stringResource(R.string.clarity_good), stringResource(R.string.clarity_desc_good))
            LegendRow(Yellow, stringResource(R.string.clarity_fair), stringResource(R.string.clarity_desc_fair))
            LegendRow(Orange, stringResource(R.string.clarity_poor), stringResource(R.string.clarity_desc_poor))
            LegendRow(Red, stringResource(R.string.clarity_very_poor), stringResource(R.string.clarity_desc_very_poor))
            LegendRow(Grey, stringResource(R.string.about_grey_title), stringResource(R.string.about_grey))
            HorizontalDivider()
            Text(
                stringResource(R.string.pnsdw_standard_note),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun LegendRow(c: Color, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(c, 16.dp); Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Caption(text)
        }
    }
}

// ----------------------------------------------------------- calibration steps

/** Ring that fills (0..1) as the sensor stays steady, or drains during the button countdown. */
@Composable
fun ProgressRing(
    fraction: Float, description: String, color: Color = Blue, size: Dp = 112.dp,
    center: @Composable () -> Unit = {},
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(800), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier.size(size).clearAndSetSemantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = 12.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(w))
            if (f > 0f) drawArc(color, -90f, 360f * f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        center()
    }
}


private const val READY_WINDOW_S = 60

/**
 * The calibration flow, driven only by [session] (see [calSessionStep]):
 * step 1 + Start → SETTLING (steady ring) → READY (press the button, 60 s countdown from the
 * session's first READY) → DONE / REJECTED (plain reason) / EXPIRED. A failed start shows
 * "That didn't work" with Start again. [reachable] false disables Start and, mid-session, says
 * the sensor's Wi-Fi is needed. No volts or technical words are shown. The ESP32 has no cancel,
 * so the button only closes the flow ("Close").
 */
@Composable
fun CalibrationSteps(
    session: CalUiSession, reachable: Boolean,
    onStart: () -> Unit, onClose: () -> Unit,
) {
    val cal = session.status
    val phase = cal?.phase
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (session.active && !session.terminal && !reachable) {
            Text(stringResource(R.string.cal_needs_wifi), color = amberText(), fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
        }
        when {
            !session.active -> {
                if (session.startFailed) {
                    Text(stringResource(R.string.cal_failed), Modifier.fillMaxWidth(), color = Red,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.cal_step1), Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                if (!reachable) Caption(stringResource(R.string.cal_needs_wifi), Modifier.fillMaxWidth())
                Button(onStart, Modifier.fillMaxWidth(), enabled = reachable) { Text(stringResource(R.string.cal_start)) }
            }
            phase == null || phase == CalPhase.IDLE -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.cal_starting), textAlign = TextAlign.Center)
                OutlinedButton(onClose) { Text(stringResource(R.string.cal_close)) }
            }
            phase == CalPhase.SETTLING -> {
                val s = cal.secondsStable.coerceIn(0, 60)
                val sec10 = ((s * 10f) / 60f + 0.5f).toInt().coerceIn(0, 10)
                ProgressRing(s / 60f, stringResource(R.string.cal_progress, s)) {
                    Text("${sec10}s", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.cal_settling), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                OutlinedButton(onClose) { Text(stringResource(R.string.cal_close)) }
            }
            phase == CalPhase.READY -> {
                // The ESP32 only accepts the button within 60 s of "ready"; the session keeps the
                // first READY time, so leaving and returning to the tab does not restart the count.
                val readyAt = session.readyAtMs ?: System.currentTimeMillis()
                fun leftNow() = (READY_WINDOW_S - ((System.currentTimeMillis() - readyAt) / 1000).toInt())
                    .coerceIn(0, READY_WINDOW_S)
                val left by produceState(leftNow(), readyAt) {
                    while (value > 0) {
                        delay(1000)
                        value = leftNow()
                    }
                }
                val leftText = LocalContext.current.resources.getQuantityString(R.plurals.cal_seconds_left, left, left)
                ProgressRing(left / READY_WINDOW_S.toFloat(), leftText, color = Amber) {
                    Text("$left", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.cal_ready), fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Caption(leftText)
                OutlinedButton(onClose) { Text(stringResource(R.string.cal_close)) }
            }
            phase == CalPhase.DONE -> {
                StatusOrb(WaterState.CLEAR, pulsing = false, size = 96.dp)
                Text(stringResource(R.string.cal_done), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Button(onClose) { Text(stringResource(R.string.cal_close)) }
            }
            else -> {
                val msg = when {
                    phase == CalPhase.EXPIRED -> R.string.cal_expired
                    cal.reason == CalReason.TOO_LOW -> R.string.cal_too_low
                    cal.reason == CalReason.OUT_OF_RANGE -> R.string.cal_out_of_range
                    else -> R.string.cal_failed
                }
                Text(stringResource(msg), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClose) { Text(stringResource(R.string.cal_close)) }
                    Button(onStart, enabled = reachable) { Text(stringResource(R.string.cal_try_again)) }
                }
            }
        }
    }
}
