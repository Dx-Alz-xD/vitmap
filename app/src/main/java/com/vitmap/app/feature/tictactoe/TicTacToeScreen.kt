package com.vitmap.app.feature.tictactoe

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.AnimatedNumber
import com.vitmap.app.ui.components.ConfettiOverlay
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.theme.Accents
import com.vitmap.app.ui.theme.LocalDarkTheme
import com.vitmap.core.tictactoe.Board
import com.vitmap.core.tictactoe.Difficulty
import com.vitmap.core.tictactoe.Player
import com.vitmap.core.tictactoe.TicTacToeAi
import com.vitmap.core.tictactoe.Win
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val XColor = Color(0xFFF43F5E)
private val OColor = Color(0xFF06B6D4)

data class TttSettings(
    val size: Int = 3,
    val winLength: Int = 3,
    val vsBot: Boolean = true,
    val difficulty: Difficulty = Difficulty.HARD,
    val humanPlays: Int = Player.X,
)

data class TttState(
    val settings: TttSettings = TttSettings(),
    val board: Board = Board(3, 3),
    val history: List<Int> = emptyList(),
    val starter: Int = Player.X,
    val toMove: Int = Player.X,
    val win: Win? = null,
    val draw: Boolean = false,
    val xWins: Int = 0,
    val oWins: Int = 0,
    val draws: Int = 0,
    val thinking: Boolean = false,
    val round: Int = 1,
) {
    val over: Boolean get() = win != null || draw
}

class TicTacToeViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TttState())
    val state: StateFlow<TttState> = _state.asStateFlow()
    private val ai = TicTacToeAi()
    private var botJob: Job? = null

    init {
        maybeBot()
    }

    fun play(index: Int) {
        val s = _state.value
        if (s.over || s.thinking || s.board[index] != Player.NONE) return
        if (s.settings.vsBot && s.toMove != s.settings.humanPlays) return
        place(index)
    }

    private fun place(index: Int) {
        val s = _state.value
        val board = s.board.play(index, s.toMove)
        val win = board.winAt(index)
        val draw = win == null && board.isFull
        _state.update {
            it.copy(
                board = board,
                history = it.history + index,
                toMove = Player.other(it.toMove),
                win = win,
                draw = draw,
                xWins = it.xWins + if (win?.player == Player.X) 1 else 0,
                oWins = it.oWins + if (win?.player == Player.O) 1 else 0,
                draws = it.draws + if (draw) 1 else 0,
            )
        }
        maybeBot()
    }

    private fun maybeBot() {
        val s = _state.value
        if (!s.settings.vsBot || s.over || s.toMove == s.settings.humanPlays) return
        botJob?.cancel()
        botJob = viewModelScope.launch {
            _state.update { it.copy(thinking = true) }
            val started = System.currentTimeMillis()
            val move = withContext(Dispatchers.Default) { ai.chooseMove(s.board, s.toMove, s.settings.difficulty) }
            val wait = 420 - (System.currentTimeMillis() - started)
            if (wait > 0) delay(wait)
            _state.update { it.copy(thinking = false) }
            if (_state.value.board === s.board) place(move)
        }
    }

    fun undo() {
        botJob?.cancel()
        val s = _state.value
        if (s.history.isEmpty()) return
        // Against the bot keep taking back until it's the human's move again.
        fun moverAfter(h: List<Int>) = if (h.size % 2 == 0) s.starter else Player.other(s.starter)
        var hist = s.history.dropLast(1)
        if (s.settings.vsBot) {
            while (hist.isNotEmpty() && moverAfter(hist) != s.settings.humanPlays) hist = hist.dropLast(1)
        }
        var b = Board(s.settings.size, s.settings.winLength)
        var p = s.starter
        for (i in hist) { b = b.play(i, p); p = Player.other(p) }
        _state.update {
            it.copy(
                board = b, history = hist, toMove = p, win = null, draw = false, thinking = false,
                xWins = it.xWins - if (it.win?.player == Player.X) 1 else 0,
                oWins = it.oWins - if (it.win?.player == Player.O) 1 else 0,
                draws = it.draws - if (it.draw) 1 else 0,
            )
        }
        maybeBot()
    }

    fun nextRound() {
        botJob?.cancel()
        _state.update {
            val starter = Player.other(it.starter)
            it.copy(
                board = Board(it.settings.size, it.settings.winLength),
                history = emptyList(), starter = starter, toMove = starter, win = null, draw = false, thinking = false,
                round = it.round + 1,
            )
        }
        maybeBot()
    }

    fun apply(settings: TttSettings) {
        botJob?.cancel()
        _state.value = TttState(settings = settings, board = Board(settings.size, settings.winLength))
        maybeBot()
    }

    fun resetScores() {
        _state.update { it.copy(xWins = 0, oWins = 0, draws = 0) }
    }
}

@Composable
fun TicTacToeScreen(onBack: () -> Unit) {
    val vm: TicTacToeViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var showSettings by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }

    LaunchedEffect(s.history.size) { if (s.history.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    LaunchedEffect(s.win) {
        val w = s.win ?: return@LaunchedEffect
        if (!s.settings.vsBot || w.player == s.settings.humanPlays) confetti++
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val st = s.settings
    ToolScaffold(
        title = "Tic-Tac-Toe",
        subtitle = "${st.size}×${st.size} · ${st.winLength} in a row · " + if (st.vsBot) "vs Bot (${st.difficulty.title})" else "2 players",
        accent = Accents.TicTacToe,
        onBack = onBack,
        actions = {
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Tune, "Game settings") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Scoreboard(s)
                Spacer(Modifier.height(16.dp))
                key(s.round, st) { TttBoard(s, onTap = vm::play) }
                Spacer(Modifier.height(16.dp))
                StatusLine(s)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = vm::undo, enabled = s.history.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Undo")
                    }
                    Button(onClick = vm::nextRound) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (s.over) "Next round" else "Restart")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            ConfettiOverlay(confetti, Modifier.fillMaxSize())
        }
    }

    if (showSettings) {
        SettingsSheet(st, onDismiss = { showSettings = false }, onApply = { vm.apply(it); showSettings = false }, onResetScores = vm::resetScores)
    }
}

@Composable
private fun Scoreboard(s: TttState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val xName = if (s.settings.vsBot) (if (s.settings.humanPlays == Player.X) "You" else "Bot") else "Player X"
        val oName = if (s.settings.vsBot) (if (s.settings.humanPlays == Player.O) "You" else "Bot") else "Player O"
        ScoreCard("X", xName, s.xWins, XColor, active = !s.over && s.toMove == Player.X, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedNumber(s.draws, style = MaterialTheme.typography.titleLarge)
            Text("Draws", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ScoreCard("O", oName, s.oWins, OColor, active = !s.over && s.toMove == Player.O, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ScoreCard(mark: String, name: String, score: Int, color: Color, active: Boolean, modifier: Modifier) {
    val border by animateColorAsState(if (active) color else Color.Transparent, label = "border")
    val lift by animateFloatAsState(if (active) 1.04f else 1f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "lift")
    Row(
        modifier
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .clip(RoundedCornerShape(22.dp))
            .background(color.copy(alpha = 0.12f))
            .border(2.dp, border, RoundedCornerShape(22.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
            Text(mark, style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            AnimatedNumber(score, style = MaterialTheme.typography.headlineSmall, color = color)
        }
    }
}

@Composable
private fun StatusLine(s: TttState) {
    val text = when {
        s.win != null -> {
            val who = if (s.settings.vsBot) (if (s.win.player == s.settings.humanPlays) "You win!" else "The bot wins") else "${Player.symbol(s.win.player)} wins!"
            who
        }
        s.draw -> "It's a draw"
        s.thinking -> "Bot is thinking…"
        s.settings.vsBot -> if (s.toMove == s.settings.humanPlays) "Your turn" else "Bot's turn"
        else -> "${Player.symbol(s.toMove)}'s turn"
    }
    AnimatedContent(text, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 1.1f)) }, label = "status") { t ->
        Text(t, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun TttBoard(s: TttState, onTap: (Int) -> Unit) {
    val n = s.board.size
    val dark = LocalDarkTheme.current
    val gridColor = if (dark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f)
    val gridAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { gridAnim.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 3f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (n >= 7) Modifier.transformable(transform) else Modifier),
    ) {
        val sizePx = constraints.maxWidth.toFloat()
        val cell = sizePx / n
        val cellDp: Dp = with(LocalDensity.current) { cell.toDp() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(n, s.board) {
                    detectTapGestures { p ->
                        val c = (p.x / cell).toInt()
                        val r = (p.y / cell).toInt()
                        if (c in 0 until n && r in 0 until n) onTap(r * n + c)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val k = gridAnim.value
                val stroke = (if (n <= 4) 5.dp else if (n <= 8) 3.dp else 1.5.dp).toPx()
                for (i in 1 until n) {
                    val p = i * cell
                    val len = size.width * k
                    drawLine(gridColor, Offset(p, (size.height - len) / 2), Offset(p, (size.height + len) / 2), stroke, StrokeCap.Round)
                    drawLine(gridColor, Offset((size.width - len) / 2, p), Offset((size.width + len) / 2, p), stroke, StrokeCap.Round)
                }
            }
            val winCells = s.win?.cells?.toSet() ?: emptySet()
            for (i in 0 until n * n) {
                val v = s.board[i]
                if (v == Player.NONE) continue
                key(i) {
                    Mark(
                        player = v,
                        winning = i in winCells,
                        size = cellDp,
                        modifier = Modifier.graphicsLayer {
                            translationX = (i % n) * cell
                            translationY = (i / n) * cell
                        },
                        thickness = if (n <= 4) 10.dp else if (n <= 8) 6.dp else 3.dp,
                    )
                }
            }
            s.win?.let { w -> WinLine(w, n, cell) }
        }
    }
}

@Composable
private fun Mark(player: Int, winning: Boolean, size: Dp, thickness: Dp, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(1f, if (winning) 1.12f else 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "glow")
    val color = if (player == Player.X) XColor else OColor
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { scaleX = glow; scaleY = glow },
    ) {
        val pad = this.size.width * 0.24f
        val w = thickness.toPx()
        val t = progress.value
        if (player == Player.X) {
            val a = (t * 2f).coerceAtMost(1f)
            val b = (t * 2f - 1f).coerceIn(0f, 1f)
            val s = this.size.width
            drawLine(color, Offset(pad, pad), Offset(pad + (s - 2 * pad) * a, pad + (s - 2 * pad) * a), w, StrokeCap.Round)
            if (b > 0f) drawLine(color, Offset(s - pad, pad), Offset(s - pad - (s - 2 * pad) * b, pad + (s - 2 * pad) * b), w, StrokeCap.Round)
        } else {
            drawArc(
                color, -90f, 360f * t, false,
                topLeft = Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(this.size.width - 2 * pad, this.size.height - 2 * pad),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun WinLine(win: Win, n: Int, cell: Float) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(win) {
        delay(250)
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    val color = if (win.player == Player.X) XColor else OColor
    Canvas(Modifier.fillMaxSize()) {
        val first = win.cells.first()
        val last = win.cells.last()
        val a = Offset((first % n + 0.5f) * cell, (first / n + 0.5f) * cell)
        val b = Offset((last % n + 0.5f) * cell, (last / n + 0.5f) * cell)
        val dir = b - a
        val ext = 0.32f * cell / maxOf(1f, dir.getDistance()) // overshoot past the end cells a little
        val start = a - dir * ext
        val end = start + (b + dir * ext - start) * progress.value
        drawLine(color.copy(alpha = 0.25f), start, end, cell * 0.32f, StrokeCap.Round)
        drawLine(Color.White, start, end, cell * 0.08f, StrokeCap.Round)
    }
}

@Composable
private fun SettingsSheet(current: TttSettings, onDismiss: () -> Unit, onApply: (TttSettings) -> Unit, onResetScores: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var size by remember { mutableFloatStateOf(current.size.toFloat()) }
    var win by remember { mutableFloatStateOf(current.winLength.toFloat()) }
    var vsBot by remember { mutableStateOf(current.vsBot) }
    var difficulty by remember { mutableStateOf(current.difficulty) }
    var human by remember { mutableIntStateOf(current.humanPlays) }
    val n = size.roundToInt()
    val maxWin = minOf(n, 7)
    val winLen = win.coerceAtMost(maxWin.toFloat())

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Game setup", style = MaterialTheme.typography.headlineSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!vsBot, { vsBot = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("2 players") }
                SegmentedButton(vsBot, { vsBot = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("vs Bot") }
            }
            if (vsBot) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Difficulty.entries.forEachIndexed { i, d ->
                        SegmentedButton(difficulty == d, { difficulty = d }, SegmentedButtonDefaults.itemShape(i, Difficulty.entries.size)) { Text(d.title) }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(human == Player.X, { human = Player.X }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Play as X") }
                    SegmentedButton(human == Player.O, { human = Player.O }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Play as O") }
                }
            }
            Text("Board: $n × $n", style = MaterialTheme.typography.titleSmall)
            Slider(size, { size = it }, valueRange = 3f..15f, steps = 11)
            Text("Win with ${winLen.roundToInt()} in a row", style = MaterialTheme.typography.titleSmall)
            Slider(winLen, { win = it }, valueRange = 3f..maxOf(4, maxWin).toFloat(), steps = maxOf(0, maxWin - 4), enabled = maxWin > 3)
            BoardPreview(n, Modifier.align(Alignment.CenterHorizontally))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onResetScores, modifier = Modifier.weight(1f)) { Text("Reset scores") }
                Button(
                    onClick = { onApply(TttSettings(n, winLen.roundToInt().coerceIn(3, maxWin), vsBot, difficulty, human)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Start") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BoardPreview(n: Int, modifier: Modifier) {
    val anim by animateFloatAsState(n.toFloat(), spring(dampingRatio = 0.7f, stiffness = 300f), label = "preview")
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier.size(120.dp)) {
        val count = anim
        val cell = size.width / count
        var i = 1
        while (i < count) {
            drawLine(color, Offset(i * cell, 0f), Offset(i * cell, size.height), 2f)
            drawLine(color, Offset(0f, i * cell), Offset(size.width, i * cell), 2f)
            i++
        }
    }
}
