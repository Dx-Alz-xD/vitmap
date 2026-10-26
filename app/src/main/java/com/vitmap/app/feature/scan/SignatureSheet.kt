package com.vitmap.app.feature.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val SignColors = listOf(0xFF111111, 0xFF0B2A6F, 0xFF1D4ED8, 0xFFB91C1C)

/** Draw a signature (or pick a saved one) to place on the page. */
@Composable
fun SignatureSheet(vm: ScanViewModel, onDismiss: () -> Unit, onPick: (SavedSignature) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs by vm.prefs.flow.collectAsState()
    val saved = prefs?.signatures.orEmpty()
    // Strokes in pad coordinates (0..1 across, 0..padAspect down).
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(SignColors[1]) }
    var keep by remember { mutableStateOf(true) }
    val padAspect = 0.42f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Signature", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            if (saved.isNotEmpty()) {
                Text("Saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(saved, key = { it.id }) { s ->
                        Box(
                            Modifier
                                .size(150.dp, 70.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .clickable { onPick(s) },
                        ) {
                            Canvas(Modifier.fillMaxSize().padding(8.dp)) { drawSignature(s.strokes, Color(s.color), s.aspect) }
                            IconButton(onClick = { vm.deleteSignature(s.id) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, "Delete", Modifier.size(16.dp), tint = Color.Gray)
                            }
                        }
                    }
                }
            }
            Text("Draw a new one", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / padAspect)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val w = size.width.toFloat()
                            val pts = ArrayList<Offset>()
                            pts += Offset(down.position.x / w, down.position.y / w)
                            current = pts.toList()
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                c.consume()
                                val p = Offset(c.position.x / w, c.position.y / w)
                                if ((p - pts.last()).getDistance() > 0.004f) {
                                    pts += p
                                    current = pts.toList()
                                }
                            }
                            strokes += pts.toList()
                            current = emptyList()
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val baseline = size.height * 0.72f
                    drawLine(Color(0xFFCBD5E1), Offset(size.width * 0.08f, baseline), Offset(size.width * 0.92f, baseline), 2f)
                    val w = size.width
                    for (s in strokes + listOf(current)) {
                        if (s.isEmpty()) continue
                        drawPath(smooth(s.map { it * w }), Color(color), style = Stroke(w * 0.008f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                if (strokes.isEmpty() && current.isEmpty()) {
                    Text("Sign here", color = Color(0xFF94A3B8), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in SignColors) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).clickable { color = c })
                }
                Spacer(Modifier.weight(1f))
                Checkbox(keep, { keep = it })
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { strokes.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
                Button(
                    onClick = {
                        val sig = normalize(strokes.toList(), color) ?: return@Button
                        if (keep) vm.saveSignature(sig)
                        onPick(sig)
                    },
                    enabled = strokes.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Use signature") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Crops strokes to their bounds (with a little margin) and normalises them to that box. */
private fun normalize(strokes: List<List<Offset>>, color: Long): SavedSignature? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val margin = 0.01f
    val minX = all.minOf { it.x } - margin
    val minY = all.minOf { it.y } - margin
    val maxX = all.maxOf { it.x } + margin
    val maxY = all.maxOf { it.y } + margin
    val w = (maxX - minX).coerceAtLeast(0.01f)
    val h = (maxY - minY).coerceAtLeast(0.01f)
    val norm = strokes.map { s -> s.flatMap { listOf((it.x - minX) / w, (it.y - minY) / h) } }
    return SavedSignature(strokes = norm, aspect = h / w, color = color)
}

private fun smooth(pts: List<Offset>): Path {
    val p = Path()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) {
        p.lineTo(pts[0].x + 0.5f, pts[0].y)
        return p
    }
    for (i in 1 until pts.size - 1) {
        val mid = (pts[i] + pts[i + 1]) / 2f
        p.quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
    }
    p.lineTo(pts.last().x, pts.last().y)
    return p
}

/** Draws normalised signature strokes into the scope, preserving their aspect ratio. */
fun DrawScope.drawSignature(strokes: List<List<Float>>, color: Color, aspect: Float) {
    val boxAspect = size.height / size.width
    val w: Float
    val h: Float
    if (aspect > boxAspect) {
        h = size.height
        w = h / aspect
    } else {
        w = size.width
        h = w * aspect
    }
    val ox = (size.width - w) / 2
    val oy = (size.height - h) / 2
    for (s in strokes) {
        val pts = (s.indices step 2).map { Offset(ox + s[it] * w, oy + s[it + 1] * h) }
        drawPath(smooth(pts), color, style = Stroke(w * 0.012f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
