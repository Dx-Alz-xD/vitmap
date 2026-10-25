package com.vitmap.app.feature.chess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitmap.app.ui.theme.MonoStyle
import com.vitmap.core.chess.MoveClassifier
import com.vitmap.core.chess.MoveQuality
import com.vitmap.core.chess.Piece
import com.vitmap.core.chess.Ply
import com.vitmap.core.chess.Search
import kotlin.math.abs

fun scoreText(scoreWhite: Int): String {
    if (Search.isMate(scoreWhite)) {
        val n = abs(Search.mateIn(abs(scoreWhite)))
        return if (scoreWhite > 0) "M$n" else "-M$n"
    }
    val v = scoreWhite / 100.0
    return (if (v > 0) "+" else "") + "%.2f".format(v)
}

@Composable
fun EvalBar(scoreWhite: Int?, flipped: Boolean, modifier: Modifier = Modifier) {
    val target = scoreWhite?.let { MoveClassifier.winProbability(it).toFloat() } ?: 0.5f
    val fraction by animateFloatAsState(target, spring(dampingRatio = 0.8f, stiffness = 120f), label = "eval")
    val whiteLeads = (scoreWhite ?: 0) >= 0
    Box(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF26232E)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.02f, 0.98f))
                .align(if (flipped) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Brush.horizontalGradient(listOf(Color(0xFFF4F1FF), Color.White))),
        )
        if (scoreWhite != null) {
            val text = scoreText(scoreWhite)
            val onWhiteSide = whiteLeads
            val align = when {
                onWhiteSide && !flipped -> Alignment.CenterStart
                onWhiteSide && flipped -> Alignment.CenterEnd
                !onWhiteSide && !flipped -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }
            Text(
                text,
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize, fontWeight = FontWeight.Bold),
                color = if (onWhiteSide) Color(0xFF26232E) else Color.White,
                modifier = Modifier.align(align).padding(horizontal = 10.dp),
            )
        }
    }
}

/** Pieces each side has lost, derived from the board. */
fun capturedPieces(board: IntArray): Pair<List<Int>, List<Int>> {
    val start = mapOf(Piece.PAWN to 8, Piece.KNIGHT to 2, Piece.BISHOP to 2, Piece.ROOK to 2, Piece.QUEEN to 1)
    fun missing(color: Int): List<Int> {
        val counts = HashMap<Int, Int>()
        for (p in board) if (p != Piece.NONE && Piece.color(p) == color) counts[Piece.type(p)] = (counts[Piece.type(p)] ?: 0) + 1
        val out = ArrayList<Int>()
        for (t in listOf(Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT, Piece.PAWN)) {
            repeat(((start[t] ?: 0) - (counts[t] ?: 0)).coerceAtLeast(0)) { out += Piece.make(color, t) }
        }
        return out
    }
    return missing(Piece.WHITE) to missing(Piece.BLACK)
}

fun formatClock(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return if (ms < 10_000) "%d:%02d.%d".format(m, s, (ms % 1000) / 100) else "%d:%02d".format(m, s)
}

@Composable
fun PlayerBar(
    name: String,
    subtitle: String?,
    isBot: Boolean,
    color: Int,
    captured: List<Int>,
    materialLead: Int,
    clockMs: Long?,
    active: Boolean,
    thinking: Boolean,
    modifier: Modifier = Modifier,
) {
    val ring by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, label = "ring")
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .border(2.dp, ring, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(if (color == Piece.WHITE) Color(0xFFF1EEF8) else Color(0xFF2B2833)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                if (isBot) Icons.Rounded.SmartToy else Icons.Rounded.Person,
                null,
                tint = if (color == Piece.WHITE) Color(0xFF2B2833) else Color(0xFFF1EEF8),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (thinking) {
                    Spacer(Modifier.width(8.dp))
                    ThinkingDots()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Show what this player has captured (the opponent's missing pieces).
                Box(Modifier.height(18.dp)) {
                    captured.forEachIndexed { i, p ->
                        Image(painterResource(pieceDrawable(p)), null, Modifier.size(18.dp).offset(x = (i * 10).dp))
                    }
                }
                Spacer(Modifier.width((captured.size * 10 + 10).dp))
                if (materialLead > 0) Text("+$materialLead", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle != null && captured.isEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (clockMs != null) {
            val low = clockMs < 20_000
            val pulse = rememberInfiniteTransition(label = "clock")
            val a by pulse.animateFloat(1f, if (low && active) 0.55f else 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "pulse")
            val bg by animateColorAsState(
                when {
                    active && low -> Color(0xFFD32F2F)
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                label = "clockBg",
            )
            Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.graphicsLayer { alpha = a }) {
                Text(
                    formatClock(clockMs),
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize, fontWeight = FontWeight.Bold),
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
fun ThinkingDots(color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0..2) {
            val s by t.animateFloat(
                0.4f, 1f,
                infiniteRepeatable(tween(420, delayMillis = i * 140), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(6.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = s }.clip(CircleShape).background(color))
        }
    }
}

@Composable
fun MoveList(plies: List<Ply>, qualities: Map<Int, MoveQuality>, viewIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(viewIndex, plies.size) {
        if (plies.isNotEmpty()) state.animateScrollToItem((viewIndex - 2).coerceAtLeast(0))
    }
    if (plies.isEmpty()) {
        Text(
            "Make a move to begin",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 10.dp, horizontal = 4.dp),
        )
        return
    }
    LazyRow(modifier, state = state, horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        itemsIndexed(plies, key = { i, p -> "$i-${p.uci}" }) { i, ply ->
            val current = viewIndex == i + 1
            val bg by animateColorAsState(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, label = "mv")
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(i + 1) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ply.color == Piece.WHITE || i == 0) {
                    Text(
                        if (ply.color == Piece.WHITE) "${ply.moveNumber}." else "${ply.moveNumber}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(ply.san, style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize))
                val q = qualities[i]
                AnimatedVisibility(q != null, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = fadeOut()) {
                    if (q != null) Row { Spacer(Modifier.width(4.dp)); QualityBadge(q, size = 16.dp) }
                }
            }
        }
    }
}

@Composable
fun EngineLines(analysis: AnalysisView?, startMoveNumber: Int, whiteToMove: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Engine", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (analysis != null) {
                    Text(
                        "depth ${analysis.depth} · ${analysis.nps / 1000} kN/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else ThinkingDots()
            }
            analysis?.lines?.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val positive = line.scoreWhite >= 0
                    Surface(
                        color = if (positive) Color(0xFFF1EEF8) else Color(0xFF2B2833),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            scoreText(line.scoreWhite),
                            style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize, fontWeight = FontWeight.Bold),
                            color = if (positive) Color(0xFF2B2833) else Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).width(48.dp),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatLine(line.san, startMoveNumber, whiteToMove),
                        style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatLine(san: List<String>, moveNumber: Int, whiteToMove: Boolean): String {
    val sb = StringBuilder()
    var n = moveNumber
    var white = whiteToMove
    san.forEachIndexed { i, m ->
        if (white) sb.append(n).append(". ") else if (i == 0) sb.append(n).append("… ")
        sb.append(m).append(' ')
        if (!white) n++
        white = !white
    }
    return sb.toString().trim()
}

/** Evaluation over the game as a filled area chart (white advantage above the middle). */
@Composable
fun EvalGraph(evals: Map<Int, Int>, size: Int, current: Int, modifier: Modifier = Modifier, onSelect: ((Int) -> Unit)? = null) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(900)) }
    val line = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF2B2833))
            .pointerInput(size, onSelect != null) {
                if (onSelect == null) return@pointerInput
                detectTapGestures { p ->
                    val i = ((p.x / this.size.width) * maxOf(1, size)).roundToInt().coerceIn(0, size)
                    onSelect(i)
                }
            },
    ) {
        val n = maxOf(1, size)
        fun x(i: Int) = i / n.toFloat() * this.size.width
        fun y(score: Int) = (1f - MoveClassifier.winProbability(score).toFloat()) * this.size.height
        val pts = (0..size).mapNotNull { i -> evals[i]?.let { Offset(x(i), y(it)) } }
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, this.size.height / 2), Offset(this.size.width, this.size.height / 2))
        if (pts.size >= 2) {
            val visible = (pts.size * progress.value).toInt().coerceAtLeast(2).coerceAtMost(pts.size)
            val area = Path().apply {
                moveTo(pts[0].x, this@Canvas.size.height)
                for (k in 0 until visible) lineTo(pts[k].x, pts[k].y)
                lineTo(pts[visible - 1].x, this@Canvas.size.height)
                close()
            }
            drawPath(area, Color(0xFFF1EEF8))
            val stroke = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (k in 1 until visible) lineTo(pts[k].x, pts[k].y)
            }
            drawPath(stroke, line, style = Stroke(width = 2.dp.toPx()))
        }
        if (current in 0..size) {
            drawLine(line.copy(alpha = 0.9f), Offset(x(current), 0f), Offset(x(current), this.size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
fun AccuracyCircle(accuracy: Double, label: String, color: Int, modifier: Modifier = Modifier) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(accuracy) { anim.animateTo(accuracy.toFloat(), tween(1100)) }
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Color.Gray.copy(alpha = 0.25f), -90f, 360f, false, style = Stroke(10.dp.toPx()))
                drawArc(accent, -90f, 360f * anim.value / 100f, false, style = Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Text("%.1f".format(anim.value), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (color == Piece.WHITE) Color(0xFFF1EEF8) else Color(0xFF2B2833)).border(1.dp, Color.Gray, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
