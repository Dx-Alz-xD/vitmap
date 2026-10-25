package com.vitmap.app.feature.chess

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vitmap.core.chess.MoveQuality
import com.vitmap.core.chess.Piece
import com.vitmap.core.chess.Square
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class BoardArrow(val from: Int, val to: Int, val color: Color)

private data class Sprite(val id: Int, val piece: Int, val square: Int)

private data class DragInfo(val spriteId: Int, val from: Int, val pointer: Offset)

/** Keeps sprite identities stable across positions so pieces slide instead of teleporting. */
private class SpriteTracker {
    var sprites: List<Sprite> = emptyList()
    private var nextId = 1

    fun update(board: IntArray): Pair<List<Sprite>, List<Sprite>> {
        val alive = ArrayList<Sprite>()
        val bySquare = sprites.associateBy { it.square }
        val unmatched = ArrayList<Int>()
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == Piece.NONE) continue
            val old = bySquare[sq]
            if (old != null && old.piece == p) alive += old else unmatched += sq
        }
        val used = alive.mapTo(HashSet()) { it.id }
        val orphans = sprites.filter { it.id !in used }.toMutableList()
        fun dist(a: Int, b: Int) = abs(Square.file(a) - Square.file(b)) + abs(Square.rank(a) - Square.rank(b))
        for (sq in unmatched.toList()) {
            val p = board[sq]
            val cand = orphans.filter { it.piece == p }.minByOrNull { dist(it.square, sq) } ?: continue
            alive += cand.copy(square = sq)
            orphans.remove(cand)
            unmatched.remove(sq)
        }
        // Promotions: a pawn vanished and a new piece of the same colour appeared nearby.
        for (sq in unmatched.toList()) {
            val p = board[sq]
            val cand = orphans.filter { Piece.type(it.piece) == Piece.PAWN && Piece.color(it.piece) == Piece.color(p) && dist(it.square, sq) <= 2 }
                .minByOrNull { dist(it.square, sq) } ?: continue
            alive += cand.copy(piece = p, square = sq)
            orphans.remove(cand)
            unmatched.remove(sq)
        }
        for (sq in unmatched) alive += Sprite(nextId++, board[sq], sq)
        val firstFrame = sprites.isEmpty()
        sprites = alive
        return alive to if (firstFrame) emptyList() else orphans
    }
}

private fun cellOf(sq: Int, flipped: Boolean): Pair<Int, Int> =
    if (!flipped) Square.file(sq) to 7 - Square.rank(sq) else 7 - Square.file(sq) to Square.rank(sq)

private fun squareOfCell(col: Int, row: Int, flipped: Boolean): Int =
    if (!flipped) Square.of(col, 7 - row) else Square.of(7 - col, row)

@Composable
fun ChessBoard(
    board: IntArray,
    flipped: Boolean,
    theme: BoardTheme,
    modifier: Modifier = Modifier,
    lastMoveFrom: Int = -1,
    lastMoveTo: Int = -1,
    selected: Int = -1,
    targets: Set<Int> = emptySet(),
    checkSquare: Int = -1,
    arrows: List<BoardArrow> = emptyList(),
    badge: Pair<Int, MoveQuality>? = null,
    interactive: Boolean = true,
    showCoordinates: Boolean = true,
    animate: Boolean = true,
    onTap: (Int) -> Unit = {},
    onDragStart: (Int) -> Boolean = { false },
    onDrop: (Int, Int) -> Unit = { _, _ -> },
) {
    val tracker = remember { SpriteTracker() }
    val (alive, removed) = remember(board) { tracker.update(board) }
    val spritesState = rememberUpdatedState(alive)
    val tapState = rememberUpdatedState(onTap)
    val dragStartState = rememberUpdatedState(onDragStart)
    val dropState = rememberUpdatedState(onDrop)
    var drag by remember { mutableStateOf<DragInfo?>(null) }
    val dropOffsets = remember { HashMap<Int, Offset>() }
    var dropToken by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))) {
        val boardPx = constraints.maxWidth.toFloat()
        val sqPx = boardPx / 8f
        val sqDp: Dp = with(LocalDensity.current) { sqPx.toDp() }

        fun topLeft(sq: Int): Offset {
            val (c, r) = cellOf(sq, flipped)
            return Offset(c * sqPx, r * sqPx)
        }

        fun squareAt(p: Offset): Int? {
            val c = (p.x / sqPx).toInt()
            val r = (p.y / sqPx).toInt()
            if (c !in 0..7 || r !in 0..7 || p.x < 0 || p.y < 0) return null
            return squareOfCell(c, r, flipped)
        }

        // Squares, highlights and coordinates
        Canvas(Modifier.fillMaxSize()) {
            for (r in 0..7) for (c in 0..7) {
                val sq = squareOfCell(c, r, flipped)
                val light = Square.isLight(sq)
                drawRect(if (light) theme.light else theme.dark, Offset(c * sqPx, r * sqPx), Size(sqPx + 0.5f, sqPx + 0.5f))
                if (sq == lastMoveFrom || sq == lastMoveTo) {
                    drawRect(theme.highlight, Offset(c * sqPx, r * sqPx), Size(sqPx + 0.5f, sqPx + 0.5f))
                }
                if (sq == selected) {
                    drawRect(Color(0x6614B8A6), Offset(c * sqPx, r * sqPx), Size(sqPx + 0.5f, sqPx + 0.5f))
                }
                if (sq == checkSquare) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0xEEFF1744), Color(0x88FF1744), Color.Transparent),
                            center = Offset(c * sqPx + sqPx / 2, r * sqPx + sqPx / 2),
                            radius = sqPx * 0.75f,
                        ),
                        Offset(c * sqPx, r * sqPx), Size(sqPx, sqPx),
                    )
                }
            }
            if (showCoordinates) {
                val style = TextStyle(fontSize = (sqPx * 0.2f / density).sp, fontWeight = FontWeight.Bold)
                for (i in 0..7) {
                    // rank numbers on the left column, file letters on the bottom row
                    val rankSq = squareOfCell(0, i, flipped)
                    val rankColor = if (Square.isLight(rankSq)) theme.dark else theme.light
                    drawText(textMeasurer, "${Square.rank(rankSq) + 1}", Offset(sqPx * 0.06f, i * sqPx + sqPx * 0.03f), style.copy(color = rankColor))
                    val fileSq = squareOfCell(i, 7, flipped)
                    val fileColor = if (Square.isLight(fileSq)) theme.dark else theme.light
                    val label = "${'a' + Square.file(fileSq)}"
                    val m = textMeasurer.measure(label, style)
                    drawText(textMeasurer, label, Offset((i + 1) * sqPx - m.size.width - sqPx * 0.06f, 8 * sqPx - m.size.height - sqPx * 0.02f), style.copy(color = fileColor))
                }
            }
        }

        // Legal move hints
        TargetDots(targets, board, sqPx, flipped)

        // Pieces
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(interactive, flipped, sqPx) {
                    if (!interactive) return@pointerInput
                    detectTapGestures { p -> squareAt(p)?.let { tapState.value(it) } }
                }
                .pointerInput(interactive, flipped, sqPx) {
                    if (!interactive) return@pointerInput
                    detectDragGestures(
                        onDragStart = { p ->
                            val sq = squareAt(p) ?: return@detectDragGestures
                            val sprite = spritesState.value.firstOrNull { it.square == sq } ?: return@detectDragGestures
                            if (dragStartState.value(sq)) drag = DragInfo(sprite.id, sq, p)
                        },
                        onDrag = { change, amount ->
                            val d = drag ?: return@detectDragGestures
                            change.consume()
                            drag = d.copy(pointer = d.pointer + amount)
                        },
                        onDragEnd = {
                            val d = drag ?: return@detectDragGestures
                            val to = squareAt(d.pointer) ?: d.from
                            dropOffsets[d.spriteId] = d.pointer - Offset(sqPx / 2, sqPx * 0.8f)
                            drag = null
                            dropToken++
                            dropState.value(d.from, to)
                        },
                        onDragCancel = {
                            val d = drag ?: return@detectDragGestures
                            dropOffsets[d.spriteId] = d.pointer - Offset(sqPx / 2, sqPx * 0.8f)
                            drag = null
                            dropToken++
                            dropState.value(d.from, d.from)
                        },
                    )
                },
        ) {
            for (s in removed) {
                key("dead-${s.id}") { FadingPiece(s.piece, topLeft(s.square), sqDp) }
            }
            for (s in alive) {
                key(s.id) {
                    val d = drag
                    val dragging = d != null && d.spriteId == s.id
                    PieceSprite(
                        piece = s.piece,
                        target = topLeft(s.square),
                        size = sqDp,
                        dragTopLeft = if (dragging) d!!.pointer - Offset(sqPx / 2, sqPx * 0.8f) else null,
                        dropToken = dropToken,
                        animate = animate,
                        takeDrop = { dropOffsets.remove(s.id) },
                    )
                }
            }
        }

        if (arrows.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (a in arrows) drawArrow(topLeft(a.from) + Offset(sqPx / 2, sqPx / 2), topLeft(a.to) + Offset(sqPx / 2, sqPx / 2), sqPx, a.color)
            }
        }

        if (badge != null) {
            val (sq, q) = badge
            val tl = topLeft(sq)
            key(sq, q) { QualityBadge(q, Modifier.graphicsLayer { translationX = tl.x + sqPx * 0.62f; translationY = tl.y - sqPx * 0.12f }.zIndex(5f), size = sqDp * 0.46f) }
        }
    }
}

@Composable
private fun TargetDots(targets: Set<Int>, board: IntArray, sqPx: Float, flipped: Boolean) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(targets) {
        appear.snapTo(0f)
        if (targets.isNotEmpty()) appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 700f))
    }
    if (targets.isEmpty()) return
    Canvas(Modifier.fillMaxSize().zIndex(1f)) {
        val k = appear.value
        for (sq in targets) {
            val (c, r) = cellOf(sq, flipped)
            val center = Offset(c * sqPx + sqPx / 2, r * sqPx + sqPx / 2)
            if (board[sq] != Piece.NONE) {
                drawCircle(Color(0x55000000), radius = sqPx * 0.46f * k, center = center, style = Stroke(width = sqPx * 0.09f))
            } else {
                drawCircle(Color(0x44000000), radius = sqPx * 0.16f * k, center = center)
            }
        }
    }
}

@Composable
private fun PieceSprite(
    piece: Int,
    target: Offset,
    size: Dp,
    dragTopLeft: Offset?,
    dropToken: Int,
    animate: Boolean,
    takeDrop: () -> Offset?,
) {
    val anim = remember { Animatable(target, Offset.VectorConverter) }
    val appear = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
    LaunchedEffect(target, dropToken) {
        takeDrop()?.let { anim.snapTo(it) }
        if (animate) anim.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 480f)) else anim.snapTo(target)
    }
    val dragging = dragTopLeft != null
    val lift by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1.22f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "lift")
    Image(
        painter = painterResource(pieceDrawable(piece)),
        contentDescription = null,
        modifier = Modifier
            .zIndex(if (dragging) 10f else if (anim.isRunning) 4f else 2f)
            .size(size)
            .graphicsLayer {
                val p = dragTopLeft ?: anim.value
                translationX = p.x
                translationY = p.y
                val s = lift * appear.value
                scaleX = s
                scaleY = s
                shadowElevation = if (dragging) 18f else 0f
            },
    )
}

@Composable
private fun FadingPiece(piece: Int, topLeft: Offset, size: Dp) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(140)
        alpha.animateTo(0f, tween(220))
    }
    Image(
        painter = painterResource(pieceDrawable(piece)),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .zIndex(1f)
            .graphicsLayer {
                translationX = topLeft.x
                translationY = topLeft.y
                this.alpha = alpha.value
                val s = 0.7f + 0.3f * alpha.value
                scaleX = s
                scaleY = s
            },
    )
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, sq: Float, color: Color) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val head = sq * 0.42f
    val shaftEnd = Offset(to.x - cos(angle) * head * 0.8f, to.y - sin(angle) * head * 0.8f)
    drawLine(color, from, shaftEnd, strokeWidth = sq * 0.17f, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - cos(angle - 0.5f) * head, to.y - sin(angle - 0.5f) * head)
        lineTo(to.x - cos(angle + 0.5f) * head, to.y - sin(angle + 0.5f) * head)
        close()
    }
    drawPath(path, color)
}

@Composable
fun QualityBadge(q: MoveQuality, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f)) }
    Box(
        modifier
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .size(size)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(q.color),
        contentAlignment = Alignment.Center,
    ) {
        val icon = q.icon
        if (icon != null) {
            Icon(icon, contentDescription = q.label, tint = Color.White, modifier = Modifier.size(size * 0.62f))
        } else {
            Text(
                q.glyph,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() },
                maxLines = 1,
            )
        }
    }
}

/** Squares from/to of the move that produced the displayed position. */
fun lastMoveSquares(move: Int?): Pair<Int, Int> =
    if (move == null || move == 0) -1 to -1 else (move and 63) to ((move shr 6) and 63)
