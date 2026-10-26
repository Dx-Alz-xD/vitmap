package com.vitmap.app.feature.sudoku

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitmap.app.vitmap
import com.vitmap.core.sudoku.Difficulty
import com.vitmap.core.sudoku.Generator
import com.vitmap.core.sudoku.Geometry
import com.vitmap.core.sudoku.LogicSolver
import com.vitmap.core.sudoku.Step
import com.vitmap.core.sudoku.Variant
import com.vitmap.core.sudoku.bitCount
import com.vitmap.core.sudoku.bitOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.random.Random

@Serializable
data class SavedSudoku(
    val size: Int,
    val variant: String,
    val difficulty: String,
    val givens: List<Int>,
    val solution: List<Int>,
    val values: List<Int>,
    val notes: List<Int>,
    val elapsed: Long,
    val mistakes: Int,
    val hints: Int,
    val daily: String? = null,
)

@Serializable
data class SudokuStat(val played: Int = 0, val won: Int = 0, val bestMs: Long = 0, val totalMs: Long = 0, val streak: Int = 0, val bestStreak: Int = 0)

@Serializable
data class SudokuPrefs(
    val current: SavedSudoku? = null,
    val stats: Map<String, SudokuStat> = emptyMap(),
    val mistakeLimit: Boolean = true,
    val showMistakes: Boolean = true,
    val highlightSame: Boolean = true,
    val autoRemoveNotes: Boolean = true,
    val dailyDone: List<String> = emptyList(),
)

/** One reversible change to the board. */
private data class Move(val cell: Int, val oldValue: Int, val newValue: Int, val oldNotes: IntArray, val newNotes: IntArray)

data class SudokuState(
    val geometry: Geometry = Geometry(9),
    val difficulty: Difficulty = Difficulty.EASY,
    val givens: IntArray = IntArray(81),
    val solution: IntArray = IntArray(81),
    val values: IntArray = IntArray(81),
    val notes: IntArray = IntArray(81),
    val selected: Int = -1,
    val notesMode: Boolean = false,
    val mistakes: Int = 0,
    val hintsUsed: Int = 0,
    val elapsed: Long = 0,
    val paused: Boolean = false,
    val generating: Boolean = true,
    val won: Boolean = false,
    val lost: Boolean = false,
    val hint: Step? = null,
    /** Bumped when a row, column or box gets completed, with the cells to flash. */
    val flash: Pair<Int, List<Int>> = 0 to emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val daily: String? = null,
    val prefs: SudokuPrefs = SudokuPrefs(),
) {
    val n: Int get() = geometry.n
    fun isGiven(i: Int) = givens[i] != 0
    fun valueAt(i: Int) = if (givens[i] != 0) givens[i] else values[i]
    fun isWrong(i: Int) = values[i] != 0 && givens[i] == 0 && values[i] != solution[i]
    fun remaining(d: Int): Int = geometry.n - (0 until geometry.cells).count { valueAt(it) == d && !isWrong(it) }
}

class SudokuViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.vitmap.store("sudoku", SudokuPrefs.serializer()) { SudokuPrefs() }
    private val _state = MutableStateFlow(SudokuState())
    val state: StateFlow<SudokuState> = _state.asStateFlow()
    private val undo = ArrayDeque<Move>()
    private val redo = ArrayDeque<Move>()
    private var timer: Job? = null
    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            val p = store.load()
            val saved = p.current
            if (saved != null) restore(saved, p) else {
                _state.update { it.copy(prefs = p) }
                newGame(9, Variant.CLASSIC, Difficulty.EASY)
            }
        }
    }

    private fun restore(s: SavedSudoku, p: SudokuPrefs) {
        val g = Geometry(s.size, runCatching { Variant.valueOf(s.variant) }.getOrDefault(Variant.CLASSIC))
        _state.value = SudokuState(
            geometry = g,
            difficulty = runCatching { Difficulty.valueOf(s.difficulty) }.getOrDefault(Difficulty.EASY),
            givens = s.givens.toIntArray(),
            solution = s.solution.toIntArray(),
            values = s.values.toIntArray(),
            notes = s.notes.toIntArray(),
            mistakes = s.mistakes,
            hintsUsed = s.hints,
            elapsed = s.elapsed,
            generating = false,
            daily = s.daily,
            prefs = p,
        )
        startTimer()
    }

    fun newGame(size: Int, variant: Variant, difficulty: Difficulty, daily: String? = null) {
        generateJob?.cancel()
        timer?.cancel()
        undo.clear()
        redo.clear()
        _state.update { it.copy(generating = true, won = false, lost = false, hint = null, selected = -1) }
        generateJob = viewModelScope.launch {
            val g = Geometry(size, variant)
            val random = if (daily != null) Random(daily.hashCode()) else Random.Default
            val puzzle = withContext(Dispatchers.Default) { Generator.generate(g, difficulty, random) }
            _state.update {
                it.copy(
                    geometry = g,
                    difficulty = difficulty,
                    givens = puzzle.givens,
                    solution = puzzle.solution,
                    values = IntArray(g.cells),
                    notes = IntArray(g.cells),
                    selected = -1,
                    notesMode = false,
                    mistakes = 0,
                    hintsUsed = 0,
                    elapsed = 0,
                    paused = false,
                    generating = false,
                    won = false,
                    lost = false,
                    hint = null,
                    canUndo = false,
                    canRedo = false,
                    daily = daily,
                )
            }
            val key = statKey(_state.value)
            store.update { p -> p.copy(stats = p.stats + (key to (p.stats[key] ?: SudokuStat()).let { st -> st.copy(played = st.played + 1) })) }
            persist()
            startTimer()
        }
    }

    fun daily() {
        val today = LocalDate.now().toString()
        newGame(9, Variant.CLASSIC, if (LocalDate.now().dayOfWeek.value >= 6) Difficulty.HARD else Difficulty.MEDIUM, daily = today)
    }

    private fun statKey(s: SudokuState) = "${s.geometry.n}-${s.geometry.variant.name}-${s.difficulty.name}"

    private fun startTimer() {
        timer?.cancel()
        timer = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val s = _state.value
                if (!s.paused && !s.generating && !s.won && !s.lost) {
                    _state.update { it.copy(elapsed = it.elapsed + 1000) }
                    if ((s.elapsed / 1000) % 10 == 0L) persist()
                }
            }
        }
    }

    fun setPaused(p: Boolean) {
        _state.update { it.copy(paused = p) }
        persist()
    }

    fun select(i: Int) = _state.update { it.copy(selected = if (it.selected == i) -1 else i, hint = null) }

    fun toggleNotes() = _state.update { it.copy(notesMode = !it.notesMode) }

    fun input(d: Int) {
        val s = _state.value
        val i = s.selected
        if (i < 0 || s.isGiven(i) || s.won || s.lost || s.paused) return
        if (s.notesMode && s.values[i] == 0) {
            val newNotes = s.notes.copyOf()
            newNotes[i] = newNotes[i] xor bitOf(d)
            applyMove(Move(i, 0, 0, s.notes.copyOf(), newNotes))
            return
        }
        val newValue = if (s.values[i] == d) 0 else d
        val newNotes = s.notes.copyOf()
        newNotes[i] = 0
        if (newValue != 0 && s.prefs.autoRemoveNotes) {
            for (p in s.geometry.peers[i]) newNotes[p] = newNotes[p] and bitOf(newValue).inv()
        }
        applyMove(Move(i, s.values[i], newValue, s.notes.copyOf(), newNotes))
        if (newValue != 0) afterPlacement(i, newValue)
    }

    fun erase() {
        val s = _state.value
        val i = s.selected
        if (i < 0 || s.isGiven(i)) return
        if (s.values[i] == 0 && s.notes[i] == 0) return
        val newNotes = s.notes.copyOf()
        newNotes[i] = 0
        applyMove(Move(i, s.values[i], 0, s.notes.copyOf(), newNotes))
    }

    private fun applyMove(m: Move, record: Boolean = true) {
        _state.update {
            val v = it.values.copyOf()
            v[m.cell] = m.newValue
            it.copy(values = v, notes = m.newNotes, hint = null)
        }
        if (record) {
            undo.addLast(m)
            if (undo.size > 400) undo.removeFirst()
            redo.clear()
        }
        _state.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        persist()
    }

    fun undo() {
        val m = undo.removeLastOrNull() ?: return
        redo.addLast(m)
        _state.update {
            val v = it.values.copyOf()
            v[m.cell] = m.oldValue
            it.copy(values = v, notes = m.oldNotes, selected = m.cell, canUndo = undo.isNotEmpty(), canRedo = true, hint = null)
        }
        persist()
    }

    fun redo() {
        val m = redo.removeLastOrNull() ?: return
        undo.addLast(m)
        _state.update {
            val v = it.values.copyOf()
            v[m.cell] = m.newValue
            it.copy(values = v, notes = m.newNotes, selected = m.cell, canUndo = true, canRedo = redo.isNotEmpty(), hint = null)
        }
        persist()
    }

    private fun afterPlacement(i: Int, d: Int) {
        val s = _state.value
        if (d != s.solution[i]) {
            if (s.prefs.showMistakes) {
                val mistakes = s.mistakes + 1
                _state.update { it.copy(mistakes = mistakes) }
                if (s.prefs.mistakeLimit && mistakes >= 3) {
                    _state.update { it.copy(lost = true) }
                    viewModelScope.launch {
                        store.update { p -> p.copy(current = null, stats = p.stats + (statKey(s) to (p.stats[statKey(s)] ?: SudokuStat()).copy(streak = 0))) }
                    }
                }
            }
            return
        }
        // Flash completed groups.
        val done = s.geometry.unitsOf[i].filter { u -> u.all { s.valueAt(it) == s.solution[it] } }
        if (done.isNotEmpty()) _state.update { it.copy(flash = (it.flash.first + 1) to done.flatMap { u -> u.toList() }.distinct()) }
        if ((0 until s.geometry.cells).all { s.valueAt(it) == s.solution[it] }) win()
    }

    private fun win() {
        val s = _state.value
        _state.update { it.copy(won = true, selected = -1) }
        viewModelScope.launch {
            val key = statKey(s)
            store.update { p ->
                val st = p.stats[key] ?: SudokuStat()
                val streak = st.streak + 1
                p.copy(
                    current = null,
                    stats = p.stats + (key to st.copy(
                        won = st.won + 1,
                        bestMs = if (st.bestMs == 0L || s.elapsed < st.bestMs) s.elapsed else st.bestMs,
                        totalMs = st.totalMs + s.elapsed,
                        streak = streak,
                        bestStreak = maxOf(st.bestStreak, streak),
                    )),
                    dailyDone = if (s.daily != null) (p.dailyDone + s.daily).distinct().takeLast(400) else p.dailyDone,
                )
            }
            _state.update { it.copy(prefs = store.load()) }
        }
    }

    /** Fills every empty cell's notes with its possible digits. */
    fun autoNotes() {
        val s = _state.value
        val grid = IntArray(s.geometry.cells) { s.valueAt(it) }
        val cand = LogicSolver(s.geometry).candidates(grid)
        val notes = IntArray(s.geometry.cells) { if (grid[it] != 0) 0 else cand[it] }
        // Recorded as one move per changed cell would be noisy; record it on the selected cell only.
        applyMove(Move(s.selected.coerceAtLeast(0), s.values[s.selected.coerceAtLeast(0)], s.values[s.selected.coerceAtLeast(0)], s.notes.copyOf(), notes))
    }

    /**
     * Shows the next logical step. A wrong entry is pointed out first; otherwise the easiest
     * technique that makes progress is explained and its cells highlighted.
     */
    fun hint() {
        val s = _state.value
        if (s.won || s.lost || s.generating) return
        val wrong = (0 until s.geometry.cells).firstOrNull { s.isWrong(it) }
        if (wrong != null) {
            _state.update { it.copy(selected = wrong, hint = Step(com.vitmap.core.sudoku.Technique.NAKED_SINGLE, wrong, s.solution[wrong], focus = listOf(wrong)), hintsUsed = it.hintsUsed + 1) }
            return
        }
        val grid = IntArray(s.geometry.cells) { s.valueAt(it) }
        val logic = LogicSolver(s.geometry)
        val cand = logic.candidates(grid)
        // Respect notes the player already pruned, as long as they still include the answer.
        for (i in grid.indices) if (grid[i] == 0 && s.notes[i] != 0 && s.notes[i] and bitOf(s.solution[i]) != 0) cand[i] = cand[i] and s.notes[i]
        val step = logic.nextStep(grid, cand) ?: run {
            val empty = (0 until s.geometry.cells).filter { grid[it] == 0 }.minByOrNull { bitCount(cand[it]) } ?: return
            Step(com.vitmap.core.sudoku.Technique.GUESS, empty, s.solution[empty], focus = listOf(empty))
        }
        _state.update { it.copy(hint = step, selected = if (step.isPlacement) step.cell else it.selected, hintsUsed = it.hintsUsed + 1) }
    }

    /** Applies the shown hint: places its digit or removes its eliminated notes. */
    fun applyHint() {
        val s = _state.value
        val h = s.hint ?: return
        if (h.isPlacement) {
            _state.update { it.copy(selected = h.cell, notesMode = false) }
            val i = h.cell
            if (s.values[i] != 0 && s.values[i] != h.digit) {
                val notes = s.notes.copyOf()
                applyMove(Move(i, s.values[i], 0, s.notes.copyOf(), notes))
            }
            if (_state.value.values[i] != h.digit) input(h.digit)
        } else {
            val grid = IntArray(s.geometry.cells) { s.valueAt(it) }
            val base = LogicSolver(s.geometry).candidates(grid)
            val notes = IntArray(s.geometry.cells) { if (grid[it] != 0) 0 else if (s.notes[it] != 0) s.notes[it] else base[it] }
            for ((c, d) in h.eliminations) notes[c] = notes[c] and bitOf(d).inv()
            applyMove(Move(h.eliminations.firstOrNull()?.first ?: 0, s.values[h.eliminations.firstOrNull()?.first ?: 0], s.values[h.eliminations.firstOrNull()?.first ?: 0], s.notes.copyOf(), notes))
        }
        _state.update { it.copy(hint = null) }
    }

    fun dismissHint() = _state.update { it.copy(hint = null) }

    fun continueAfterLoss() {
        _state.update { it.copy(lost = false, mistakes = 2) }
        persist()
    }

    fun setPref(change: (SudokuPrefs) -> SudokuPrefs) {
        viewModelScope.launch {
            val p = store.update(change)
            _state.update { it.copy(prefs = p) }
        }
    }

    private fun persist() {
        val s = _state.value
        if (s.generating || s.won || s.lost) return
        val saved = SavedSudoku(
            s.geometry.n, s.geometry.variant.name, s.difficulty.name,
            s.givens.toList(), s.solution.toList(), s.values.toList(), s.notes.toList(), s.elapsed, s.mistakes, s.hintsUsed, s.daily,
        )
        // App scope, so the final save in onCleared still runs.
        getApplication<Application>().vitmap.appScope.launch { store.update { it.copy(current = saved) } }
    }

    override fun onCleared() {
        persist()
        super.onCleared()
    }
}
