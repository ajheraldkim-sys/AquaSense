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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun levelColor(l: Level?) = when (l) { Level.UNSAFE -> Red; Level.CAUTION -> Amber; Level.WITHIN -> Blue; null -> Grey }
fun levelIcon(l: Level?): ImageVector? = when (l) {
    Level.WITHIN -> Icons.Default.Check; Level.CAUTION -> Icons.Default.Warning; Level.UNSAFE -> Icons.Default.Close; null -> null
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
fun StatusOrb(level: Level?, pulsing: Boolean, size: Dp = 168.dp, greyMark: String = "?") {
    val color by animateColorAsState(levelColor(level), tween(600), label = "orb")
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2
            if (pulsing) drawCircle(color.copy(alpha = 0.18f * (1 - pulse)), radius = r * (0.78f + 0.22f * pulse))
            drawCircle(color.copy(alpha = 0.12f), radius = r * 0.78f)
            drawCircle(color.copy(alpha = 0.22f), radius = r * 0.62f)
            drawCircle(color, radius = r * 0.46f)
        }
        val icon = levelIcon(level)
        if (icon != null) Icon(icon, null, Modifier.size(size * 0.26f), tint = Color.White)
        else Text(greyMark, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

// --------------------------------------------------------------- turbidity bar

/**
 * The value + a horizontal Clear → Cloudy scale: blue zone below the limit, amber close to it, red above.
 * The marker shows where the current value sits. No units or technical words: [clearLabel] / [cloudyLabel]
 * are the localized end labels.
 */
@Composable
fun TurbidityBar(
    value: Double?, limit: Double, level: Level?, clearLabel: String, cloudyLabel: String,
    modifier: Modifier = Modifier,
) {
    val scaleMax = maxOf(limit * 2, (value ?: 0.0) * 1.15)
    val frac by animateFloatAsState(
        if (value == null) 0f else (value / scaleMax).toFloat().coerceIn(0f, 1f), tween(700), label = "t"
    )
    val track = MaterialTheme.colorScheme.surfaceVariant
    val marker = levelColor(level)
    Panel(modifier) {
        Text(value?.let { if (it < 100) f1(it) else it.toInt().toString() } ?: "—",
            fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, color = marker)
        Spacer(Modifier.height(16.dp))
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val h = 12.dp.toPx(); val y = (size.height - h) / 2
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            fun x(v: Double) = (v / scaleMax).toFloat().coerceIn(0f, 1f) * size.width
            val xNear = x(limit * NEAR_FRACTION); val xLim = x(limit)
            // One rounded clip so the three zones meet with clean edges (no overlapping ends).
            val bar = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, y, size.width, y + h, r)) }
            clipPath(bar) {
                drawRect(track, Offset(0f, y), Size(size.width, h))
                drawRect(Blue.copy(alpha = 0.35f), Offset(0f, y), Size(xNear, h))
                drawRect(Amber.copy(alpha = 0.45f), Offset(xNear, y), Size(xLim - xNear, h))
                drawRect(Red.copy(alpha = 0.35f), Offset(xLim, y), Size(size.width - xLim, h))
            }
            if (value != null) {
                val cx = frac * size.width
                drawCircle(Color.White, size.height / 2, Offset(cx.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2))
                drawCircle(marker, size.height / 2 - 4.dp.toPx(), Offset(cx.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Caption(clearLabel, color = Grey); Spacer(Modifier.weight(1f)); Caption(cloudyLabel, color = Grey)
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

/** Short "i"-sheet: what the colours mean + the limits of a cloudiness sensor. Never says "safe". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.about_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            LegendRow(Blue, stringResource(R.string.state_clear), stringResource(R.string.about_clear))
            LegendRow(Amber, stringResource(R.string.state_getting_cloudy), stringResource(R.string.about_getting_cloudy))
            LegendRow(Red, stringResource(R.string.state_cloudy), stringResource(R.string.about_cloudy))
            LegendRow(Grey, stringResource(R.string.about_grey_title), stringResource(R.string.about_grey))
            HorizontalDivider()
            Text(stringResource(R.string.about_disclaimer), fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
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
