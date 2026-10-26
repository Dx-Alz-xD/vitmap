package com.vitmap.app.feature.photo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vitmap.core.photo.Curve
import com.vitmap.core.photo.CurvePoint
import com.vitmap.core.photo.Curves
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A labelled slider for one adjustment. Dragging edits live; letting go makes one history entry.
 * Double-tap the label to reset.
 */
@Composable
fun AdjustSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    range: ClosedFloatingPointRange<Float> = -100f..100f,
    default: Float = 0f,
    track: Brush? = null,
    format: (Float) -> String = { v -> if (range.start < 0) "%+d".format(v.roundToInt()).replace("+0", "0") else "${v.roundToInt()}" },
) {
    val changed = abs(value - default) > 0.01f
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).pointerInput(default) { detectTapGestures(onDoubleTap = { onStart(); onChange(default); onEnd() }) },
            )
            Text(format(value), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(contentAlignment = Alignment.Center) {
            if (track != null) Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(track))
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = { onStart(); onChange(it) },
                onValueChangeFinished = onEnd,
                valueRange = range,
                colors = if (track != null) SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent) else SliderDefaults.colors(),
            )
        }
    }
}

val hueBrush = Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
val temperatureBrush = Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFFE5E7EB), Color(0xFFF59E0B)))
val tintBrush = Brush.horizontalGradient(listOf(Color(0xFF22C55E), Color(0xFFE5E7EB), Color(0xFFD946EF)))

val palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF9CA3AF, 0xFFEF4444, 0xFFF97316, 0xFFFACC15, 0xFF22C55E, 0xFF14B8A6,
    0xFF06B6D4, 0xFF3B82F6, 0xFF6366F1, 0xFFA855F7, 0xFFEC4899, 0xFFF5DEB3, 0xFF8B4513, 0xFF1E3A8A,
)

/** Colour swatches plus a hue strip for any colour. */
@Composable
fun ColorRow(selected: Long?, onPick: (Long) -> Unit, allowNone: Boolean = false, onNone: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (allowNone) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).border(2.dp, if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onNone),
                contentAlignment = Alignment.Center,
            ) { Text("∅", style = MaterialTheme.typography.labelLarge) }
        }
        for (c in palette) {
            val isSel = selected == c
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(c.toInt()))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
    var hue by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(hueBrush).pointerInput(Unit) {
            detectTapGestures { o ->
                hue = (o.x / size.width * 360).roundToInt().coerceIn(0, 359)
                onPick(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.85f, 1f)).toLong() and 0xFFFFFFFFL)
            }
        }.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                hue = (change.position.x / size.width * 360).roundToInt().coerceIn(0, 359)
                onPick(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.85f, 1f)).toLong() and 0xFFFFFFFFL)
            }
        },
    )
}

/**
 * Tone curve editor: drag points, tap empty space to add one, double-tap a point to remove it.
 * The histogram is drawn behind.
 */
@Composable
fun CurveEditor(points: List<CurvePoint>, color: Color, histogram: IntArray?, onChange: (List<CurvePoint>, Boolean) -> Unit) {
    val current by rememberUpdatedState(points)
    var dragging by remember { mutableIntStateOf(-1) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                fun toPoint(o: Offset) = CurvePoint((o.x / size.width).coerceIn(0f, 1f), (1 - o.y / size.height).coerceIn(0f, 1f))
                fun nearest(o: Offset): Int {
                    val pts = current.sortedBy { it.x }
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for ((i, p) in pts.withIndex()) {
                        val d = hypot(p.x * size.width - o.x, (1 - p.y) * size.height - o.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    return if (bestD < 48f) best else -1
                }
                detectTapGestures(
                    onDoubleTap = { o ->
                        val i = nearest(o)
                        val pts = current.sortedBy { it.x }
                        if (i > 0 && i < pts.lastIndex) onChange(pts.filterIndexed { idx, _ -> idx != i }, true)
                    },
                    onTap = { o ->
                        if (nearest(o) < 0) {
                            val p = toPoint(o)
                            onChange((current + CurvePoint(p.x, Curve.evaluate(current, p.x))).sortedBy { it.x }, true)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val pts = current.sortedBy { it.x }
                        var best = -1
                        var bestD = Float.MAX_VALUE
                        for ((i, p) in pts.withIndex()) {
                            val d = hypot(p.x * size.width - o.x, (1 - p.y) * size.height - o.y)
                            if (d < bestD) { bestD = d; best = i }
                        }
                        dragging = if (bestD < 64f) best else -1
                        if (dragging < 0) {
                            val x = (o.x / size.width).coerceIn(0.01f, 0.99f)
                            val added = (pts + CurvePoint(x, Curve.evaluate(pts, x))).sortedBy { it.x }
                            dragging = added.indexOfFirst { it.x == x }
                            onChange(added, false)
                        }
                    },
                    onDragEnd = { dragging = -1; onChange(current, true) },
                    onDrag = { change, _ ->
                        val pts = current.sortedBy { it.x }.toMutableList()
                        val i = dragging
                        if (i in pts.indices) {
                            val lo = if (i == 0) 0f else pts[i - 1].x + 0.01f
                            val hi = if (i == pts.lastIndex) 1f else pts[i + 1].x - 0.01f
                            val x = if (i == 0 || i == pts.lastIndex) pts[i].x else (change.position.x / size.width).coerceIn(lo, hi)
                            pts[i] = CurvePoint(x, (1 - change.position.y / size.height).coerceIn(0f, 1f))
                            onChange(pts, false)
                        }
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        histogram?.let { hist ->
            val peak = hist.max().coerceAtLeast(1).toFloat()
            val path = Path().apply {
                moveTo(0f, h)
                for (i in hist.indices) lineTo(i / 255f * w, h - (hist[i] / peak).coerceAtMost(1f) * h * 0.9f)
                lineTo(w, h)
                close()
            }
            drawPath(path, muted)
        }
        for (k in 1..3) {
            drawLine(grid, Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1f)
            drawLine(grid, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
        }
        drawLine(grid, Offset(0f, h), Offset(w, 0f), 1f)
        val lut = Curve.lut(points, 128)
        val curve = Path().apply {
            for (i in lut.indices) {
                val x = i / 127f * w
                val y = (1 - lut[i]) * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(curve, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        for (p in points) {
            val c = Offset(p.x * w, (1 - p.y) * h)
            drawCircle(Color.White, 8.dp.toPx(), c)
            drawCircle(color, 6.dp.toPx(), c)
        }
    }
}

fun Curves.withChannel(channel: Int, pts: List<CurvePoint>): Curves = when (channel) {
    1 -> copy(red = pts)
    2 -> copy(green = pts)
    3 -> copy(blue = pts)
    else -> copy(rgb = pts)
}

fun Curves.channel(channel: Int): List<CurvePoint> = when (channel) {
    1 -> red
    2 -> green
    3 -> blue
    else -> rgb
}

@Composable
fun SwatchDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
    )
}
