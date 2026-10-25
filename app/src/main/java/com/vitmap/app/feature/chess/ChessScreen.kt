package com.vitmap.app.feature.chess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitmap.app.ui.components.ConfettiOverlay
import com.vitmap.app.ui.components.ToolScaffold
import com.vitmap.app.ui.components.copyToClipboard
import com.vitmap.app.ui.components.shareText
import com.vitmap.app.ui.theme.Accents
import com.vitmap.core.chess.BotLevel
import com.vitmap.core.chess.Evaluation
import com.vitmap.core.chess.Move
import com.vitmap.core.chess.Piece
import com.vitmap.core.chess.Position

@Composable
fun ChessScreen(onBack: () -> Unit) {
    val vm: ChessViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    var showNewGame by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showFen by remember { mutableStateOf(false) }
    var showPgn by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ChessEvent.Moved -> haptics.performHapticFeedback(if (e.capture || e.check) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                is ChessEvent.GameOver -> {
                    if (e.outcome.winner != null) confetti++
                }
                is ChessEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    if (showEditor) {
        BoardEditor(
            initialFen = vm.currentFen(),
            theme = BoardThemes[state.boardTheme],
            onClose = { showEditor = false },
            onUse = { fen, mode ->
                val cfg = when (mode) {
                    ChessMode.VS_BOT -> {
                        val side = Position.fromFen(fen).side
                        // The player takes the side to move.
                        if (side == Piece.WHITE) GameConfig(mode, null, state.config.blackBot ?: state.config.whiteBot ?: 1500)
                        else GameConfig(mode, state.config.blackBot ?: state.config.whiteBot ?: 1500, null)
                    }
                    else -> GameConfig(mode, null, null)
                }
                vm.newGame(cfg, fen, flipped = cfg.whiteBot != null)
                showEditor = false
            },
        )
        return
    }

    val pos = remember(state.record, state.viewIndex) { state.position }
    val board = remember(pos) { pos.board.copyOf() }
    val lastMove = if (state.viewIndex > 0) state.record.plies[state.viewIndex - 1].move else null
    val (lmFrom, lmTo) = lastMoveSquares(lastMove)
    val checkSquare = if (pos.inCheck()) pos.kingSquare[pos.side] else -1
    val (whiteLost, blackLost) = remember(board) { capturedPieces(board) }
    val material = remember(board) { Evaluation.materialBalance(pos) }
    val analysis = state.analysis
    // Keep showing the previous evaluation while the engine starts on a new position.
    val lastScore = remember { mutableStateOf<Int?>(null) }
    if (analysis != null) SideEffect { lastScore.value = analysis.scoreWhite }
    val shownScore = analysis?.scoreWhite ?: lastScore.value

    val arrows = buildList {
        if (state.showHints && analysis != null) {
            analysis.lines.getOrNull(1)?.let { add(BoardArrow(Move.from(it.firstMove), Move.to(it.firstMove), androidx.compose.ui.graphics.Color(0x6660A5FA))) }
            if (analysis.bestMove != Move.NONE) add(BoardArrow(Move.from(analysis.bestMove), Move.to(analysis.bestMove), androidx.compose.ui.graphics.Color(0xB34ADE80)))
        }
    }
    val badge = if (state.viewIndex > 0) state.qualities[state.viewIndex - 1]?.let { lmTo to it } else null

    ToolScaffold(
        title = "Chess",
        subtitle = state.opening ?: state.config.subtitle,
        accent = Accents.Chess,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { showNewGame = true }) { Icon(Icons.Rounded.Add, "New game") }
            IconButton(onClick = { showEditor = true }) { Icon(Icons.Rounded.Edit, "Edit position") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Game report") }, leadingIcon = { Icon(Icons.Rounded.Assessment, null) }, onClick = { menu = false; showReport = true })
                    DropdownMenuItem(text = { Text("Flip board") }, leadingIcon = { Icon(Icons.Rounded.SwapVert, null) }, onClick = { menu = false; vm.flip() })
                    DropdownMenuItem(text = { Text("Board theme") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) }, onClick = { menu = false; showTheme = true })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Set FEN") }, leadingIcon = { Icon(Icons.Rounded.TextSnippet, null) }, onClick = { menu = false; showFen = true })
                    DropdownMenuItem(text = { Text("Load PGN") }, leadingIcon = { Icon(Icons.Rounded.TextSnippet, null) }, onClick = { menu = false; showPgn = true })
                    DropdownMenuItem(text = { Text("Copy FEN") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; copyToClipboard(context, vm.currentFen(), "FEN") })
                    DropdownMenuItem(text = { Text("Copy PGN") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; copyToClipboard(context, vm.pgn(), "PGN") })
                    DropdownMenuItem(text = { Text("Share PGN") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; shareText(context, vm.pgn(), "Share game") })
                    if (state.outcome == null && state.config.mode != ChessMode.ANALYSIS && state.config.mode != ChessMode.BOT_VS_BOT) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Offer draw") }, leadingIcon = { Icon(Icons.Rounded.SentimentNeutral, null) }, onClick = { menu = false; vm.offerDraw() })
                        DropdownMenuItem(text = { Text("Resign") }, leadingIcon = { Icon(Icons.Rounded.Flag, null) }, onClick = { menu = false; vm.resign() })
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding(),
            ) {
                AnimatedVisibility(state.liveAnalysis, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    EvalBar(shownScore, state.flipped, Modifier.padding(bottom = 6.dp))
                }
                val topColor = if (state.flipped) Piece.WHITE else Piece.BLACK
                val bottomColor = topColor xor 1
                @Composable
                fun bar(color: Int) {
                    val lostByOpponent = if (color == Piece.WHITE) blackLost else whiteLost
                    val lead = if (color == Piece.WHITE) material else -material
                    val elo = state.config.botElo(color)
                    PlayerBar(
                        name = state.config.playerName(color),
                        subtitle = elo?.let { BotLevel.title(it) },
                        isBot = elo != null,
                        color = color,
                        captured = lostByOpponent,
                        materialLead = lead,
                        clockMs = state.clocks?.of(color),
                        active = state.outcome == null && pos.side == color && state.atLatest,
                        thinking = state.thinking && pos.side == color,
                    )
                }
                bar(topColor)
                Box {
                    ChessBoard(
                        board = board,
                        flipped = state.flipped,
                        theme = BoardThemes[state.boardTheme],
                        lastMoveFrom = lmFrom,
                        lastMoveTo = lmTo,
                        selected = state.selected,
                        targets = state.targets,
                        checkSquare = checkSquare,
                        arrows = arrows,
                        badge = badge,
                        onTap = vm::onSquareTap,
                        onDragStart = vm::onDragStart,
                        onDrop = vm::onDrop,
                    )
                    state.pendingPromotion?.let { PromotionPicker(pos.side) { t -> vm.choosePromotion(t) } }
                }
                bar(bottomColor)

                AnimatedVisibility(
                    state.outcome != null,
                    enter = slideInVertically { it / 2 } + fadeIn() + scaleIn(spring(dampingRatio = 0.6f), initialScale = 0.9f),
                    exit = slideOutVertically { it / 2 } + fadeOut(),
                ) {
                    state.outcome?.let { o ->
                        GameOverCard(o, onReport = { showReport = true }, onRematch = { vm.newGame(state.config, state.record.startFen, state.flipped) }, onAnalyse = {
                            vm.newGame(GameConfig(ChessMode.ANALYSIS, null, null), state.record.startFen, state.flipped)
                        })
                    }
                }

                Spacer(Modifier.height(4.dp))
                MoveList(state.record.plies, state.qualities, state.viewIndex, onSelect = vm::goTo, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Controls(state, vm)

                if (state.config.mode == ChessMode.BOT_VS_BOT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Speed", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(12.dp))
                        Slider(
                            value = 3100f - state.config.botDelayMs,
                            onValueChange = { vm.setBotDelay((3100f - it).toInt()) },
                            valueRange = 100f..3000f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                badge?.let { (_, q) ->
                    Surface(shape = RoundedCornerShape(16.dp), color = q.color.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            QualityBadge(q, size = 26.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("${state.record.plies[state.viewIndex - 1].san} is ${q.label.lowercase().let { if (it[0] in "aeiou") "an $it" else "a $it" }} move", style = MaterialTheme.typography.titleSmall)
                                Text(q.describe, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                AnimatedVisibility(state.liveAnalysis, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    EngineLines(analysis, pos.fullmoveNumber, pos.side == Piece.WHITE, Modifier.padding(vertical = 6.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
            ConfettiOverlay(confetti, Modifier.fillMaxSize())
        }
    }

    if (showNewGame) {
        NewGameSheet(
            current = state.config,
            hasCustomPosition = state.record.startFen != Position.START_FEN || state.viewIndex > 0,
            onDismiss = { showNewGame = false },
            onStart = { cfg, fromCurrent, flip ->
                showNewGame = false
                vm.newGame(cfg, if (fromCurrent) vm.currentFen() else Position.START_FEN, flip)
            },
        )
    }
    if (showFen) {
        TextImportDialog("Set position", "FEN", vm.currentFen(), "Load", singleLine = false, onDismiss = { showFen = false }) { vm.loadFen(it) }
    }
    if (showPgn) {
        TextImportDialog("Load game", "PGN", "", "Load", singleLine = false, onDismiss = { showPgn = false }) { text ->
            val err = vm.loadPgn(text)
            // Partially readable PGNs still load; only report a failure if nothing could be played.
            if (err != null && vm.state.value.record.size == 0) err else null
        }
    }
    if (showTheme) {
        ThemePickerDialog(state.boardTheme, onPick = { vm.setTheme(it) }, onDismiss = { showTheme = false })
    }
    if (showReport) {
        ReportSheet(state, vm.report(state), onSelectPly = vm::goTo, onDismiss = { showReport = false })
    }
}

@Composable
private fun Controls(state: ChessUiState, vm: ChessViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = vm::first, enabled = state.viewIndex > 0) { Icon(Icons.Rounded.SkipPrevious, "First") }
        IconButton(onClick = vm::back, enabled = state.viewIndex > 0) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back") }
        IconButton(onClick = vm::forward, enabled = state.viewIndex < state.record.size) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Forward") }
        IconButton(onClick = vm::last, enabled = state.viewIndex < state.record.size) { Icon(Icons.Rounded.SkipNext, "Last") }
        if (state.config.mode == ChessMode.BOT_VS_BOT) {
            FilledTonalIconToggleButton(checked = !state.paused, onCheckedChange = { vm.togglePause() }) {
                Icon(if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (state.paused) "Play" else "Pause")
            }
        } else {
            IconButton(onClick = vm::undo, enabled = state.record.size > 0) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
        }
        FilledTonalIconToggleButton(checked = state.showHints, onCheckedChange = { vm.toggleHints() }) { Icon(Icons.Rounded.Lightbulb, "Hint") }
        FilledTonalIconToggleButton(checked = state.liveAnalysis, onCheckedChange = { vm.toggleLiveAnalysis() }) { Icon(Icons.Rounded.Insights, "Live analysis") }
    }
}

@Composable
private fun GameOverCard(o: Outcome, onReport: () -> Unit, onRematch: () -> Unit, onAnalyse: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.result, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Text(o.reason, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReport, modifier = Modifier.weight(1f)) { Text("Report") }
                FilledTonalButton(onClick = onRematch, modifier = Modifier.weight(1f)) { Text("Rematch") }
                FilledTonalButton(onClick = onAnalyse, modifier = Modifier.weight(1f)) { Text("Analyse") }
            }
        }
    }
}
