package com.vitmap.app.feature.chess

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.chess.BotLevel
import com.vitmap.core.chess.ChessBot
import com.vitmap.core.chess.GameRecord
import com.vitmap.core.chess.GameStatus
import com.vitmap.core.chess.Move
import com.vitmap.core.chess.MoveClassifier
import com.vitmap.core.chess.MoveQuality
import com.vitmap.core.chess.Openings
import com.vitmap.core.chess.Pgn
import com.vitmap.core.chess.Piece
import com.vitmap.core.chess.Position
import com.vitmap.core.chess.PositionEval
import com.vitmap.core.chess.Search
import com.vitmap.core.chess.SearchInfo
import com.vitmap.core.chess.SearchLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class ChessMode(val title: String) {
    VS_BOT("vs Bot"),
    PVP("Pass & play"),
    BOT_VS_BOT("Bot vs Bot"),
    ANALYSIS("Analysis board"),
}

@Serializable
data class TimeControl(val baseMs: Long, val incMs: Long) {
    val label: String
        get() {
            val min = baseMs / 60000
            val inc = incMs / 1000
            return if (inc > 0) "$min | $inc" else "$min min"
        }

    companion object {
        val presets = listOf(
            TimeControl(60_000, 0), TimeControl(180_000, 2_000), TimeControl(300_000, 0),
            TimeControl(600_000, 0), TimeControl(900_000, 10_000), TimeControl(1_800_000, 0),
        )
    }
}

@Serializable
data class GameConfig(
    val mode: ChessMode = ChessMode.VS_BOT,
    /** Elo of the bot playing white, or null for a human. */
    val whiteBot: Int? = null,
    val blackBot: Int? = 1200,
    val timeControl: TimeControl? = null,
    val botDelayMs: Int = 700,
) {
    fun botElo(color: Int): Int? = if (color == Piece.WHITE) whiteBot else blackBot
    fun isHuman(color: Int): Boolean = botElo(color) == null

    fun playerName(color: Int): String {
        val elo = botElo(color)
        return when {
            elo != null -> "Bot $elo"
            mode == ChessMode.PVP || mode == ChessMode.ANALYSIS -> if (color == Piece.WHITE) "White" else "Black"
            else -> "You"
        }
    }

    val subtitle: String
        get() = when (mode) {
            ChessMode.VS_BOT -> "vs Bot ${whiteBot ?: blackBot} · ${BotLevel.title(whiteBot ?: blackBot ?: 1200)}"
            ChessMode.BOT_VS_BOT -> "Bot ${whiteBot} vs Bot ${blackBot}"
            ChessMode.PVP -> "Pass & play" + (timeControl?.let { " · ${it.label}" } ?: "")
            ChessMode.ANALYSIS -> "Analysis board"
        }
}

data class Clocks(val whiteMs: Long, val blackMs: Long, val tc: TimeControl) {
    fun of(color: Int) = if (color == Piece.WHITE) whiteMs else blackMs
}

data class Outcome(val result: String, val reason: String, val winner: Int?)

data class EngineLineView(val scoreWhite: Int, val san: List<String>, val firstMove: Int)

data class AnalysisView(
    val depth: Int,
    /** Score from white's point of view (centipawns or mate-encoded). */
    val scoreWhite: Int,
    val lines: List<EngineLineView>,
    val bestMove: Int,
    val nps: Long,
)

data class ChessUiState(
    val record: GameRecord = GameRecord.fromFen(),
    val viewIndex: Int = 0,
    val config: GameConfig = GameConfig(),
    val flipped: Boolean = false,
    val selected: Int = -1,
    val targets: Set<Int> = emptySet(),
    val pendingPromotion: Pair<Int, Int>? = null,
    val thinking: Boolean = false,
    val paused: Boolean = false,
    val analysis: AnalysisView? = null,
    /** Quality of ply i (0-based). */
    val qualities: Map<Int, MoveQuality> = emptyMap(),
    /** White-POV evaluation after i plies, for the evaluation graph. */
    val evals: Map<Int, Int> = emptyMap(),
    val opening: String? = null,
    val clocks: Clocks? = null,
    val outcome: Outcome? = null,
    val liveAnalysis: Boolean = true,
    val showHints: Boolean = false,
    val boardTheme: Int = 0,
) {
    val atLatest: Boolean get() = viewIndex == record.size
    val position: Position get() = record.positionAt(viewIndex)
}

sealed interface ChessEvent {
    data class Moved(val capture: Boolean, val check: Boolean) : ChessEvent
    data class GameOver(val outcome: Outcome) : ChessEvent
    data class Message(val text: String) : ChessEvent
}

@Serializable
private data class SavedGame(val startFen: String, val moves: List<String>, val config: GameConfig, val flipped: Boolean)

class ChessViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ChessUiState())
    val state: StateFlow<ChessUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ChessEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChessEvent> = _events

    private val bot = ChessBot(Search(18), Random(System.nanoTime()))
    private val botMutex = Mutex()
    private val analysisEngine = Search(19)
    private val analysisMutex = Mutex()
    private val reviewEngine = Search(18)
    private val evalCache = ConcurrentHashMap<Long, PositionEval>()

    private var botJob: Job? = null
    private var analysisJob: Job? = null
    private var reviewJob: Job? = null
    private var clockJob: Job? = null

    private val store = app.vitmap.store("chess_game", SavedGame.serializer()) { SavedGame(Position.START_FEN, emptyList(), GameConfig(), false) }
    private val settings = app.vitmap.settings

    init {
        viewModelScope.launch(Dispatchers.Default) {
            Openings.preload()
            val saved = store.load()
            val record = runCatching { GameRecord.fromUci(saved.startFen, saved.moves) }.getOrElse { GameRecord.fromFen() }
            _state.update {
                it.copy(
                    record = record,
                    viewIndex = record.size,
                    config = saved.config,
                    flipped = saved.flipped,
                    opening = Openings.nameAlong(record.hashesUpTo(record.size)),
                    outcome = outcomeFor(record),
                    clocks = saved.config.timeControl?.takeIf { saved.config.mode != ChessMode.ANALYSIS }?.let { tc -> Clocks(tc.baseMs, tc.baseMs, tc) },
                    // A restored bot game waits for the player to resume instead of moving instantly.
                    paused = saved.config.mode == ChessMode.BOT_VS_BOT,
                )
            }
            afterPositionChanged()
        }
        viewModelScope.launch {
            settings.int("chess_theme", 0).collect { t -> _state.update { it.copy(boardTheme = t.coerceIn(0, BoardThemes.lastIndex)) } }
        }
    }

    // ------------------------------------------------------------------ input

    fun onSquareTap(sq: Int) {
        val s = _state.value
        if (s.pendingPromotion != null) return
        val pos = s.position
        if (!canMove(s, pos.side)) return
        if (s.selected >= 0 && sq in s.targets) {
            tryMove(s.selected, sq)
            return
        }
        val piece = pos.board[sq]
        if (piece != Piece.NONE && Piece.color(piece) == pos.side && s.selected != sq) {
            val targets = pos.legalMoves().filter { Move.from(it) == sq }.map { Move.to(it) }.toSet()
            _state.update { it.copy(selected = sq, targets = targets) }
        } else {
            _state.update { it.copy(selected = -1, targets = emptySet()) }
        }
    }

    /** Called when a drag starts on [sq]; returns true if that piece may be dragged. */
    fun onDragStart(sq: Int): Boolean {
        val s = _state.value
        if (s.pendingPromotion != null) return false
        val pos = s.position
        if (!canMove(s, pos.side)) return false
        val piece = pos.board[sq]
        if (piece == Piece.NONE || Piece.color(piece) != pos.side) return false
        val targets = pos.legalMoves().filter { Move.from(it) == sq }.map { Move.to(it) }.toSet()
        _state.update { it.copy(selected = sq, targets = targets) }
        return true
    }

    fun onDrop(from: Int, to: Int) {
        val s = _state.value
        if (to in s.targets) tryMove(from, to) else _state.update { it.copy(selected = if (from == to) from else -1, targets = if (from == to) it.targets else emptySet()) }
    }

    private fun canMove(s: ChessUiState, side: Int): Boolean {
        if (s.config.mode == ChessMode.ANALYSIS) return true
        if (s.outcome != null && s.atLatest) return false
        return s.config.isHuman(side)
    }

    private fun tryMove(from: Int, to: Int) {
        val pos = _state.value.position
        val moves = pos.legalMoves().filter { Move.from(it) == from && Move.to(it) == to }
        when {
            moves.isEmpty() -> _state.update { it.copy(selected = -1, targets = emptySet()) }
            moves.size > 1 -> _state.update { it.copy(pendingPromotion = from to to, selected = -1, targets = emptySet()) }
            else -> applyMove(moves[0])
        }
    }

    fun choosePromotion(type: Int?) {
        val p = _state.value.pendingPromotion ?: return
        _state.update { it.copy(pendingPromotion = null) }
        if (type == null) return
        val m = _state.value.position.findMove(p.first, p.second, type)
        if (m != Move.NONE) applyMove(m)
    }

    private fun applyMove(move: Int) {
        val s = _state.value
        val pos = s.position
        val capture = Move.isCapture(move)
        val record = s.record.play(move, s.viewIndex)
        val keep = s.viewIndex
        val after = record.positionAt()
        var clocks = s.clocks
        if (clocks != null && s.config.mode != ChessMode.ANALYSIS) {
            clocks = if (pos.side == Piece.WHITE) clocks.copy(whiteMs = clocks.whiteMs + clocks.tc.incMs) else clocks.copy(blackMs = clocks.blackMs + clocks.tc.incMs)
        }
        val outcome = outcomeFor(record)
        _state.update {
            it.copy(
                record = record,
                viewIndex = record.size,
                selected = -1,
                targets = emptySet(),
                pendingPromotion = null,
                qualities = it.qualities.filterKeys { k -> k < keep },
                evals = it.evals.filterKeys { k -> k <= keep },
                opening = Openings.nameAlong(record.hashesUpTo(record.size)) ?: it.opening.takeIf { keep > 0 },
                outcome = outcome,
                clocks = clocks,
                analysis = null,
            )
        }
        _events.tryEmit(ChessEvent.Moved(capture, after.inCheck()))
        if (outcome != null) _events.tryEmit(ChessEvent.GameOver(outcome))
        persist()
        afterPositionChanged()
    }

    private fun outcomeFor(record: GameRecord): Outcome? {
        val st = record.status()
        if (!st.isOver) return null
        val pos = record.positionAt()
        return when (st) {
            GameStatus.CHECKMATE -> {
                val winner = pos.side xor 1
                Outcome(if (winner == Piece.WHITE) "1-0" else "0-1", "${if (winner == Piece.WHITE) "White" else "Black"} wins by checkmate", winner)
            }
            else -> Outcome("½-½", st.description, null)
        }
    }

    // ------------------------------------------------------------------ navigation & editing

    fun goTo(index: Int) {
        val s = _state.value
        val i = index.coerceIn(0, s.record.size)
        if (i == s.viewIndex) return
        _state.update { it.copy(viewIndex = i, selected = -1, targets = emptySet(), analysis = null, paused = it.paused || (it.config.mode == ChessMode.BOT_VS_BOT && i < it.record.size)) }
        afterPositionChanged()
    }

    fun back() = goTo(_state.value.viewIndex - 1)
    fun forward() = goTo(_state.value.viewIndex + 1)
    fun first() = goTo(0)
    fun last() = goTo(_state.value.record.size)

    fun undo() {
        cancelBot()
        val s = _state.value
        if (s.record.size == 0) return
        var n = s.record.size - 1
        // Against a bot, take back the bot's reply too so it's the human's turn again.
        if (s.config.mode == ChessMode.VS_BOT && n > 0 && !s.config.isHuman(s.record.positionAt(n).side)) n--
        val rec = s.record.truncate(n)
        _state.update {
            it.copy(
                record = rec, viewIndex = rec.size, outcome = null, selected = -1, targets = emptySet(),
                qualities = it.qualities.filterKeys { k -> k < n }, evals = it.evals.filterKeys { k -> k <= n },
                opening = Openings.nameAlong(rec.hashesUpTo(rec.size)), analysis = null,
            )
        }
        persist()
        afterPositionChanged()
    }

    fun flip() {
        _state.update { it.copy(flipped = !it.flipped) }
        persist()
    }

    fun setTheme(index: Int) {
        _state.update { it.copy(boardTheme = index) }
        viewModelScope.launch { settings.putInt("chess_theme", index) }
    }

    fun toggleLiveAnalysis() {
        _state.update { it.copy(liveAnalysis = !it.liveAnalysis, analysis = null) }
        restartAnalysis()
    }

    fun toggleHints() {
        _state.update { it.copy(showHints = !it.showHints) }
        restartAnalysis()
    }

    fun togglePause() {
        _state.update { it.copy(paused = !it.paused) }
        if (!_state.value.paused) {
            if (!_state.value.atLatest) goTo(_state.value.record.size)
            maybeBotMove()
            startClock()
        } else cancelBot()
    }

    fun setBotDelay(ms: Int) {
        _state.update { it.copy(config = it.config.copy(botDelayMs = ms)) }
    }

    fun resign() {
        val s = _state.value
        if (s.outcome != null) return
        val loser = if (s.config.mode == ChessMode.VS_BOT) {
            if (s.config.isHuman(Piece.WHITE)) Piece.WHITE else Piece.BLACK
        } else s.position.side
        val winner = loser xor 1
        val o = Outcome(if (winner == Piece.WHITE) "1-0" else "0-1", "${if (loser == Piece.WHITE) "White" else "Black"} resigned", winner)
        cancelBot()
        _state.update { it.copy(outcome = o) }
        _events.tryEmit(ChessEvent.GameOver(o))
    }

    fun offerDraw() {
        val s = _state.value
        if (s.outcome != null) return
        // Bots accept a draw when they're not clearly better.
        val botColor = if (s.config.isHuman(Piece.WHITE)) Piece.BLACK else Piece.WHITE
        val eval = evalCache[s.record.positionAt().hash]?.let { e -> if (s.position.side == botColor) e.score else -e.score }
        if (s.config.mode == ChessMode.VS_BOT && (eval ?: 0) > 80) {
            _events.tryEmit(ChessEvent.Message("The bot declines the draw"))
            return
        }
        val o = Outcome("½-½", "Draw agreed", null)
        cancelBot()
        _state.update { it.copy(outcome = o) }
        _events.tryEmit(ChessEvent.GameOver(o))
    }

    /** Starts a fresh game (or one from [fen]) with [config]. */
    fun newGame(config: GameConfig, fen: String = Position.START_FEN, flipped: Boolean? = null) {
        cancelBot()
        val rec = runCatching { GameRecord.fromFen(fen) }.getOrElse { GameRecord.fromFen() }
        val flip = flipped ?: (config.mode == ChessMode.VS_BOT && config.whiteBot != null)
        _state.update {
            ChessUiState(
                record = rec, config = config, flipped = flip, boardTheme = it.boardTheme,
                liveAnalysis = it.liveAnalysis, showHints = it.showHints,
                clocks = config.timeControl?.takeIf { config.mode != ChessMode.ANALYSIS }?.let { tc -> Clocks(tc.baseMs, tc.baseMs, tc) },
                opening = Openings.nameAlong(rec.hashesUpTo(0)),
                outcome = outcomeFor(rec),
            )
        }
        persist()
        afterPositionChanged()
    }

    fun loadFen(fen: String): String? {
        Position.validate(fen)?.let { return it }
        val cfg = _state.value.config
        newGame(cfg, fen, _state.value.flipped)
        return null
    }

    fun loadPgn(text: String): String? {
        val parsed = Pgn.parse(text)
        if (parsed.record.size == 0 && parsed.error != null) return parsed.error
        cancelBot()
        val rec = parsed.record
        _state.update {
            it.copy(
                record = rec, viewIndex = rec.size, config = GameConfig(mode = ChessMode.ANALYSIS, whiteBot = null, blackBot = null),
                qualities = emptyMap(), evals = emptyMap(), outcome = outcomeFor(rec), clocks = null,
                opening = Openings.nameAlong(rec.hashesUpTo(rec.size)), selected = -1, targets = emptySet(), analysis = null,
            )
        }
        persist()
        afterPositionChanged()
        return parsed.error
    }

    fun currentFen(): String = _state.value.record.fenAt(_state.value.viewIndex)

    fun pgn(): String {
        val s = _state.value
        val headers = mapOf(
            "White" to s.config.playerName(Piece.WHITE),
            "Black" to s.config.playerName(Piece.BLACK),
            "Date" to java.time.LocalDate.now().toString().replace('-', '.'),
        ) + (s.opening?.let { mapOf("Opening" to it) } ?: emptyMap())
        return Pgn.export(s.record, headers, s.outcome?.result?.replace("½-½", "1/2-1/2") ?: "*", (0 until s.record.size).map { s.qualities[it] })
    }

    // ------------------------------------------------------------------ engine plumbing

    private fun afterPositionChanged() {
        scheduleReview()
        restartAnalysis()
        maybeBotMove()
        startClock()
    }

    private fun cancelBot() {
        botJob?.cancel()
        bot.stop()
        _state.update { it.copy(thinking = false) }
    }

    private fun maybeBotMove() {
        botJob?.cancel()
        val s = _state.value
        if (s.outcome != null || s.paused || !s.atLatest || s.pendingPromotion != null) return
        if (s.config.mode == ChessMode.ANALYSIS || s.config.mode == ChessMode.PVP) return
        val pos = s.record.positionAt()
        val elo = s.config.botElo(pos.side) ?: return
        bot.stop()
        val record = s.record
        botJob = viewModelScope.launch {
            _state.update { it.copy(thinking = true) }
            val started = System.currentTimeMillis()
            val decision = botMutex.withLock { withContext(Dispatchers.Default) { bot.chooseMove(pos, elo) } }
            val minDelay = if (s.config.mode == ChessMode.BOT_VS_BOT) s.config.botDelayMs.toLong() else 380L
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < minDelay) delay(minDelay - elapsed)
            val cur = _state.value
            if (!isActive || cur.record !== record || cur.paused || cur.outcome != null) return@launch
            _state.update { it.copy(thinking = false) }
            applyMove(decision.move)
        }
    }

    private fun restartAnalysis() {
        analysisJob?.cancel()
        analysisEngine.stopRequested = true
        val s = _state.value
        if (!s.liveAnalysis && !s.showHints) {
            _state.update { it.copy(analysis = null) }
            return
        }
        val pos = s.position
        val record = s.record
        val index = s.viewIndex
        if (!pos.hasLegalMove()) {
            val score = if (pos.inCheck()) (if (pos.side == Piece.WHITE) -Search.MATE else Search.MATE) else 0
            _state.update { it.copy(analysis = AnalysisView(0, score, emptyList(), Move.NONE, 0)) }
            return
        }
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            delay(120)
            analysisMutex.withLock {
                analysisEngine.search(pos, SearchLimits(timeMs = 8_000, multiPv = 3)) { info ->
                    val view = toView(pos, info)
                    _state.update { if (it.record === record && it.viewIndex == index) it.copy(analysis = view) else it }
                }
            }
        }
    }

    private fun toView(pos: Position, info: SearchInfo): AnalysisView {
        val sign = if (pos.side == Piece.WHITE) 1 else -1
        val lines = info.lines.map { line ->
            val p = pos.copy()
            val san = ArrayList<String>()
            for (m in line.moves.take(10)) {
                san += p.toSan(m)
                if (!p.makeMove(m)) break
            }
            EngineLineView(line.score * sign, san, line.bestMove)
        }
        return AnalysisView(info.depth, (info.best?.score ?: 0) * sign, lines, info.best?.bestMove ?: Move.NONE, info.nps)
    }

    private fun terminalScore(pos: Position): Int? {
        if (pos.hasLegalMove()) return null
        return if (pos.inCheck()) -Search.MATE else 0
    }

    private fun evaluate(pos: Position): PositionEval {
        terminalScore(pos)?.let { return PositionEval(it, Move.NONE, null, 0) }
        evalCache[pos.hash]?.let { return it }
        val info = reviewEngine.search(pos, SearchLimits(maxDepth = 14, timeMs = 350, multiPv = 2))
        val e = PositionEval(info.lines.getOrNull(0)?.score ?: 0, info.lines.getOrNull(0)?.bestMove ?: Move.NONE, info.lines.getOrNull(1)?.score, info.depth)
        evalCache[pos.hash] = e
        return e
    }

    private fun scheduleReview() {
        if (reviewJob?.isActive == true) return
        reviewJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val s = _state.value
                val rec = s.record
                val i = (0 until rec.size).firstOrNull { it !in s.qualities } ?: break
                val before = rec.positionAt(i)
                val after = rec.positionAt(i + 1)
                val evalBefore = evaluate(before)
                val evalAfter = evaluate(after)
                val scoreAfter = -evalAfter.score
                val q = MoveClassifier.classify(before, rec.plies[i].move, evalBefore, scoreAfter)
                val whiteAfter = if (after.side == Piece.WHITE) evalAfter.score else -evalAfter.score
                val whiteBefore = if (before.side == Piece.WHITE) evalBefore.score else -evalBefore.score
                _state.update { cur ->
                    if (cur.record.size > i && cur.record.plies[i] == rec.plies[i]) {
                        cur.copy(qualities = cur.qualities + (i to q), evals = cur.evals + (i to whiteBefore) + (i + 1 to whiteAfter))
                    } else cur
                }
            }
        }
    }

    private fun startClock() {
        val s = _state.value
        if (s.clocks == null || s.outcome != null || s.config.mode == ChessMode.ANALYSIS) {
            clockJob?.cancel()
            return
        }
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(100)
                val now = System.currentTimeMillis()
                val dt = now - last
                last = now
                val cur = _state.value
                val c = cur.clocks ?: break
                if (cur.outcome != null) break
                // Clocks run only once the first move has been played, and never while paused.
                if (cur.paused || cur.record.size == 0 || !cur.atLatest) continue
                val side = cur.record.positionAt().side
                val next = if (side == Piece.WHITE) c.copy(whiteMs = (c.whiteMs - dt).coerceAtLeast(0)) else c.copy(blackMs = (c.blackMs - dt).coerceAtLeast(0))
                if (next.of(side) == 0L) {
                    val winner = side xor 1
                    val o = Outcome(if (winner == Piece.WHITE) "1-0" else "0-1", "${if (side == Piece.WHITE) "White" else "Black"} ran out of time", winner)
                    cancelBot()
                    _state.update { it.copy(clocks = next, outcome = o) }
                    _events.tryEmit(ChessEvent.GameOver(o))
                    break
                }
                _state.update { it.copy(clocks = next) }
            }
        }
    }

    private fun persist() {
        val s = _state.value
        viewModelScope.launch {
            store.set(SavedGame(s.record.startFen, s.record.plies.map { it.uci }, s.config, s.flipped))
        }
    }

    override fun onCleared() {
        bot.stop()
        analysisEngine.stopRequested = true
        reviewEngine.stopRequested = true
        super.onCleared()
    }

    // ------------------------------------------------------------------ report

    data class SideReport(val accuracy: Double, val counts: Map<MoveQuality, Int>)

    fun report(s: ChessUiState): Pair<SideReport, SideReport> {
        fun side(color: Int): SideReport {
            val idx = s.record.plies.indices.filter { s.record.plies[it].color == color }
            val counts = idx.mapNotNull { s.qualities[it] }.groupingBy { it }.eachCount()
            val accs = idx.mapNotNull { i ->
                val before = s.evals[i] ?: return@mapNotNull null
                val after = s.evals[i + 1] ?: return@mapNotNull null
                val sign = if (color == Piece.WHITE) 1 else -1
                MoveClassifier.accuracy(before * sign, after * sign)
            }
            return SideReport(if (accs.isEmpty()) 0.0 else accs.average(), counts)
        }
        return side(Piece.WHITE) to side(Piece.BLACK)
    }
}
