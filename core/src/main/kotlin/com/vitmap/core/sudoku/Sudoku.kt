package com.vitmap.core.sudoku

import kotlin.random.Random

enum class Variant(val label: String) { CLASSIC("Classic"), DIAGONAL("Diagonal") }

enum class Difficulty(val label: String, val clueRange: IntRange) {
    EASY("Easy", 36..45),
    MEDIUM("Medium", 30..35),
    HARD("Hard", 26..31),
    EXPERT("Expert", 23..28),
    MASTER("Master", 20..26),
}

/** Board geometry: an n×n grid of boxes boxRows × boxCols (9 = 3×3, 6 = 2×3, 4 = 2×2, 16 = 4×4). */
class Geometry(val n: Int, val variant: Variant = Variant.CLASSIC) {
    val boxRows: Int
    val boxCols: Int

    init {
        when (n) {
            4 -> { boxRows = 2; boxCols = 2 }
            6 -> { boxRows = 2; boxCols = 3 }
            8 -> { boxRows = 2; boxCols = 4 }
            9 -> { boxRows = 3; boxCols = 3 }
            12 -> { boxRows = 3; boxCols = 4 }
            16 -> { boxRows = 4; boxCols = 4 }
            else -> throw IllegalArgumentException("Unsupported size $n")
        }
    }

    val cells = n * n
    val all: Int = (1 shl n) - 1

    fun row(i: Int) = i / n
    fun col(i: Int) = i % n
    fun box(i: Int) = (row(i) / boxRows) * (n / boxCols) + col(i) / boxCols

    /** Every constraint group (rows, columns, boxes, and diagonals for the X variant). */
    val units: List<IntArray> = buildList {
        for (r in 0 until n) add(IntArray(n) { r * n + it })
        for (c in 0 until n) add(IntArray(n) { it * n + c })
        for (b in 0 until n) {
            val br = (b / (n / boxCols)) * boxRows
            val bc = (b % (n / boxCols)) * boxCols
            add(IntArray(n) { k -> (br + k / boxCols) * n + bc + k % boxCols })
        }
        if (variant == Variant.DIAGONAL) {
            add(IntArray(n) { it * n + it })
            add(IntArray(n) { it * n + (n - 1 - it) })
        }
    }

    /** For each cell, the units it belongs to. */
    val unitsOf: Array<List<IntArray>> = Array(cells) { i -> units.filter { u -> i in u } }

    /** For each cell, every other cell sharing a unit with it. */
    val peers: Array<IntArray> = Array(cells) { i -> unitsOf[i].flatMap { it.asIterable() }.filter { it != i }.distinct().toIntArray() }
}

fun bitCount(x: Int) = Integer.bitCount(x)
fun digitOf(bit: Int) = Integer.numberOfTrailingZeros(bit) + 1
fun bitOf(d: Int) = 1 shl (d - 1)

/** Backtracking solver over candidate bitmasks with most-constrained-cell ordering. */
class Solver(private val g: Geometry) {
    /** Number of solutions up to [limit]; fills [out] with the first one found. */
    fun count(grid: IntArray, limit: Int = 2, out: IntArray? = null, random: Random? = null): Int {
        val cand = IntArray(g.cells) { g.all }
        val values = grid.copyOf()
        for (i in 0 until g.cells) {
            val v = values[i]
            if (v != 0) {
                if (cand[i] and bitOf(v) == 0) return 0
                if (!assign(values, cand, i, v)) return 0
            }
        }
        var found = 0
        fun search(vals: IntArray, cnd: IntArray): Boolean {
            var best = -1
            var bestCount = Int.MAX_VALUE
            for (i in 0 until g.cells) {
                if (vals[i] == 0) {
                    val c = bitCount(cnd[i])
                    if (c == 0) return false
                    if (c < bestCount) {
                        bestCount = c
                        best = i
                        if (c == 1) break
                    }
                }
            }
            if (best < 0) {
                found++
                if (found == 1 && out != null) vals.copyInto(out)
                return found >= limit
            }
            var mask = cnd[best]
            val digits = ArrayList<Int>(bitCount(mask))
            while (mask != 0) {
                val bit = mask and -mask
                digits += digitOf(bit)
                mask = mask xor bit
            }
            if (random != null) digits.shuffle(random)
            for (d in digits) {
                val v2 = vals.copyOf()
                val c2 = cnd.copyOf()
                if (assign(v2, c2, best, d) && search(v2, c2)) return true
            }
            return false
        }
        search(values, cand)
        return found
    }

    /** Places [d] at [i] and propagates naked singles; false on contradiction. */
    private fun assign(vals: IntArray, cand: IntArray, i: Int, d: Int): Boolean {
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(i to d)
        while (stack.isNotEmpty()) {
            val (cell, digit) = stack.removeLast()
            val bit = bitOf(digit)
            if (cand[cell] and bit == 0) return false
            vals[cell] = digit
            cand[cell] = bit
            for (p in g.peers[cell]) {
                if (cand[p] and bit != 0) {
                    if (vals[p] == digit) return false
                    cand[p] = cand[p] and bit.inv()
                    if (cand[p] == 0) return false
                    if (vals[p] == 0 && bitCount(cand[p]) == 1) stack.addLast(p to digitOf(cand[p]))
                }
            }
        }
        return true
    }

    fun solve(grid: IntArray): IntArray? {
        val out = IntArray(g.cells)
        return if (count(grid, 1, out) >= 1) out else null
    }

    fun isUnique(grid: IntArray): Boolean = count(grid, 2) == 1
}

/** Logical techniques, in the order a person would try them. */
enum class Technique(val label: String, val level: Int, val explain: String) {
    NAKED_SINGLE("Naked single", 1, "Only one digit can go in this cell."),
    HIDDEN_SINGLE("Hidden single", 1, "This is the only cell in the group where the digit fits."),
    POINTING("Locked candidates", 2, "Within a box the digit is confined to one line, so it can't appear elsewhere on that line (or vice versa)."),
    NAKED_PAIR("Naked pair", 3, "Two cells in a group share the same two candidates, so those digits can be removed from the rest of the group."),
    HIDDEN_PAIR("Hidden pair", 3, "Two digits only fit in the same two cells of a group, so other candidates can be removed from those cells."),
    NAKED_TRIPLE("Naked triple", 3, "Three cells in a group hold only three digits between them."),
    X_WING("X-Wing", 4, "A digit forms a rectangle across two rows and two columns, eliminating it from the rest of those columns."),
    SWORDFISH("Swordfish", 5, "A digit is confined to three columns across three rows."),
    XY_WING("XY-Wing", 5, "A pivot cell and two pincers force a digit out of cells seen by both pincers."),
    GUESS("Trial and error", 6, "No simple pattern is left; this step needs trying a value and seeing if it leads to a contradiction."),
}

/** A step: either a placement or candidate eliminations. */
data class Step(
    val technique: Technique,
    val cell: Int = -1,
    val digit: Int = 0,
    val eliminations: List<Pair<Int, Int>> = emptyList(),
    val focus: List<Int> = emptyList(),
) {
    val isPlacement: Boolean get() = cell >= 0 && digit > 0
}

/** Human-style solver used for grading puzzles and for explained hints. */
class LogicSolver(private val g: Geometry) {
    /** Candidate masks for [grid], derived from the filled cells only. */
    fun candidates(grid: IntArray): IntArray = IntArray(g.cells) { i ->
        if (grid[i] != 0) bitOf(grid[i]) else {
            var m = g.all
            for (p in g.peers[i]) if (grid[p] != 0) m = m and bitOf(grid[p]).inv()
            m
        }
    }

    /** The easiest next step, using [cand] (which may already have eliminations applied). */
    fun nextStep(grid: IntArray, cand: IntArray, maxLevel: Int = 5): Step? {
        nakedSingle(grid, cand)?.let { return it }
        hiddenSingle(grid, cand)?.let { return it }
        if (maxLevel >= 2) pointing(grid, cand)?.let { return it }
        if (maxLevel >= 3) {
            nakedSubset(grid, cand, 2)?.let { return it }
            hiddenPair(grid, cand)?.let { return it }
            nakedSubset(grid, cand, 3)?.let { return it }
        }
        if (maxLevel >= 4) fish(grid, cand, 2)?.let { return it }
        if (maxLevel >= 5) {
            fish(grid, cand, 3)?.let { return it }
            xyWing(grid, cand)?.let { return it }
        }
        return null
    }

    data class Grade(val difficulty: Difficulty, val hardest: Technique, val solvedLogically: Boolean, val steps: Int)

    fun grade(puzzle: IntArray): Grade {
        val grid = puzzle.copyOf()
        val cand = candidates(grid)
        var hardest = Technique.NAKED_SINGLE
        var steps = 0
        while (grid.any { it == 0 }) {
            val step = nextStep(grid, cand) ?: return Grade(Difficulty.MASTER, Technique.GUESS, false, steps)
            if (step.technique.level > hardest.level) hardest = step.technique
            apply(grid, cand, step)
            steps++
        }
        val clues = puzzle.count { it != 0 }
        val d = when {
            hardest.level <= 1 && clues >= 34 -> Difficulty.EASY
            hardest.level <= 2 -> Difficulty.MEDIUM
            hardest.level <= 3 -> Difficulty.HARD
            else -> Difficulty.EXPERT
        }
        return Grade(d, hardest, true, steps)
    }

    fun apply(grid: IntArray, cand: IntArray, step: Step) {
        if (step.isPlacement) {
            grid[step.cell] = step.digit
            cand[step.cell] = bitOf(step.digit)
            for (p in g.peers[step.cell]) cand[p] = cand[p] and bitOf(step.digit).inv()
        }
        for ((c, d) in step.eliminations) cand[c] = cand[c] and bitOf(d).inv()
    }

    private fun nakedSingle(grid: IntArray, cand: IntArray): Step? {
        for (i in 0 until g.cells) if (grid[i] == 0 && bitCount(cand[i]) == 1) return Step(Technique.NAKED_SINGLE, i, digitOf(cand[i]), focus = listOf(i))
        return null
    }

    private fun hiddenSingle(grid: IntArray, cand: IntArray): Step? {
        for (u in g.units) {
            for (d in 1..g.n) {
                val bit = bitOf(d)
                if (u.any { grid[it] == d }) continue
                var where = -1
                var count = 0
                for (c in u) if (grid[c] == 0 && cand[c] and bit != 0) { where = c; count++ }
                if (count == 1) return Step(Technique.HIDDEN_SINGLE, where, d, focus = u.toList())
            }
        }
        return null
    }

    private fun pointing(grid: IntArray, cand: IntArray): Step? {
        val boxes = g.units.subList(2 * g.n, 3 * g.n)
        val lines = g.units.subList(0, 2 * g.n)
        for (d in 1..g.n) {
            val bit = bitOf(d)
            // Pointing: candidates in a box all on one line.
            for (b in boxes) {
                val cells = b.filter { grid[it] == 0 && cand[it] and bit != 0 }
                if (cells.size < 2) continue
                for (line in lines) {
                    if (cells.all { it in line }) {
                        val elim = line.filter { it !in b && grid[it] == 0 && cand[it] and bit != 0 }.map { it to d }
                        if (elim.isNotEmpty()) return Step(Technique.POINTING, eliminations = elim, focus = cells)
                    }
                }
            }
            // Claiming: candidates on a line all inside one box.
            for (line in lines) {
                val cells = line.filter { grid[it] == 0 && cand[it] and bit != 0 }
                if (cells.size < 2) continue
                for (b in boxes) {
                    if (cells.all { it in b }) {
                        val elim = b.filter { it !in line && grid[it] == 0 && cand[it] and bit != 0 }.map { it to d }
                        if (elim.isNotEmpty()) return Step(Technique.POINTING, eliminations = elim, focus = cells)
                    }
                }
            }
        }
        return null
    }

    private fun nakedSubset(grid: IntArray, cand: IntArray, size: Int): Step? {
        for (u in g.units) {
            val open = u.filter { grid[it] == 0 && bitCount(cand[it]) in 2..size }
            if (open.size < size) continue
            for (combo in combinations(open, size)) {
                var union = 0
                for (c in combo) union = union or cand[c]
                if (bitCount(union) != size) continue
                val elim = ArrayList<Pair<Int, Int>>()
                for (c in u) {
                    if (c in combo || grid[c] != 0) continue
                    var m = cand[c] and union
                    while (m != 0) {
                        val bit = m and -m
                        elim += c to digitOf(bit)
                        m = m xor bit
                    }
                }
                if (elim.isNotEmpty()) return Step(if (size == 2) Technique.NAKED_PAIR else Technique.NAKED_TRIPLE, eliminations = elim, focus = combo)
            }
        }
        return null
    }

    private fun hiddenPair(grid: IntArray, cand: IntArray): Step? {
        for (u in g.units) {
            val places = (1..g.n).associateWith { d -> u.filter { grid[it] == 0 && cand[it] and bitOf(d) != 0 } }.filterValues { it.size == 2 }
            val digits = places.keys.toList()
            for (a in digits.indices) for (b in a + 1 until digits.size) {
                val da = digits[a]
                val db = digits[b]
                if (places[da] != places[db]) continue
                val keep = bitOf(da) or bitOf(db)
                val elim = ArrayList<Pair<Int, Int>>()
                for (c in places[da]!!) {
                    var m = cand[c] and keep.inv()
                    while (m != 0) {
                        val bit = m and -m
                        elim += c to digitOf(bit)
                        m = m xor bit
                    }
                }
                if (elim.isNotEmpty()) return Step(Technique.HIDDEN_PAIR, eliminations = elim, focus = places[da]!!)
            }
        }
        return null
    }

    /** X-Wing (size 2) and Swordfish (size 3) on rows and columns. */
    private fun fish(grid: IntArray, cand: IntArray, size: Int): Step? {
        val n = g.n
        for (d in 1..n) {
            val bit = bitOf(d)
            for (byRow in listOf(true, false)) {
                val lines = (0 until n).map { l -> (0 until n).filter { k -> val c = if (byRow) l * n + k else k * n + l; grid[c] == 0 && cand[c] and bit != 0 } }
                val eligible = (0 until n).filter { lines[it].size in 2..size }
                for (combo in combinations(eligible, size)) {
                    val cover = combo.flatMap { lines[it] }.toSet()
                    if (cover.size != size) continue
                    val elim = ArrayList<Pair<Int, Int>>()
                    for (k in cover) for (l in 0 until n) {
                        if (l in combo) continue
                        val c = if (byRow) l * n + k else k * n + l
                        if (grid[c] == 0 && cand[c] and bit != 0) elim += c to d
                    }
                    if (elim.isNotEmpty()) {
                        val focus = combo.flatMap { l -> lines[l].map { k -> if (byRow) l * n + k else k * n + l } }
                        return Step(if (size == 2) Technique.X_WING else Technique.SWORDFISH, eliminations = elim, focus = focus)
                    }
                }
            }
        }
        return null
    }

    private fun xyWing(grid: IntArray, cand: IntArray): Step? {
        val bivalue = (0 until g.cells).filter { grid[it] == 0 && bitCount(cand[it]) == 2 }
        for (pivot in bivalue) {
            val pm = cand[pivot]
            val wings = g.peers[pivot].filter { it in bivalue && bitCount(cand[it] and pm) == 1 }
            for (a in wings.indices) for (b in a + 1 until wings.size) {
                val wa = wings[a]
                val wb = wings[b]
                val ca = cand[wa]
                val cb = cand[wb]
                if (ca == cb) continue
                val shared = ca and cb and pm.inv()
                if (bitCount(shared) != 1) continue
                if ((ca or cb) and pm != pm) continue
                val z = digitOf(shared)
                val elim = g.peers[wa].filter { it != wb && it != pivot && it in g.peers[wb] && grid[it] == 0 && cand[it] and shared != 0 }.map { it to z }
                if (elim.isNotEmpty()) return Step(Technique.XY_WING, eliminations = elim, focus = listOf(pivot, wa, wb))
            }
        }
        return null
    }

    private fun <T> combinations(items: List<T>, k: Int): Sequence<List<T>> = sequence {
        val idx = IntArray(k) { it }
        if (items.size < k) return@sequence
        while (true) {
            yield(idx.map { items[it] })
            var i = k - 1
            while (i >= 0 && idx[i] == items.size - k + i) i--
            if (i < 0) break
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }
}

data class Puzzle(val geometry: Geometry, val givens: IntArray, val solution: IntArray, val difficulty: Difficulty, val hardest: Technique)

object Generator {
    /** A full random grid. */
    fun solution(g: Geometry, random: Random): IntArray {
        val out = IntArray(g.cells)
        val seed = IntArray(g.cells)
        // Seed the first row randomly (rows are permutation-symmetric) to speed up big grids.
        val digits = (1..g.n).shuffled(random)
        for (c in 0 until g.n) seed[c] = digits[c]
        val s = Solver(g)
        if (s.count(seed, 1, out, random) == 0) error("No solution")
        return out
    }

    /**
     * Makes a puzzle with a unique solution, aiming for [target]. Clues are removed in symmetric
     * pairs; the result is graded with [LogicSolver] and the closest match is returned.
     */
    fun generate(g: Geometry, target: Difficulty, random: Random = Random.Default, attempts: Int = if (g.n <= 9) 24 else 2): Puzzle {
        val solver = Solver(g)
        val logic = LogicSolver(g)
        var best: Puzzle? = null
        var bestScore = Int.MAX_VALUE
        repeat(attempts) {
            val sol = solution(g, random)
            val puzzle = sol.copyOf()
            val order = (0 until g.cells).shuffled(random)
            val scale = g.cells / 81.0
            val minClues = ((target.clueRange.first) * scale).toInt().coerceAtLeast(g.n)
            var clues = g.cells
            for (i in order) {
                if (clues <= minClues) break
                val j = g.cells - 1 - i
                if (puzzle[i] == 0) continue
                val a = puzzle[i]
                val b = puzzle[j]
                puzzle[i] = 0
                puzzle[j] = 0
                if (solver.isUnique(puzzle)) {
                    clues -= if (i == j || b == 0) 1 else 2
                } else {
                    puzzle[i] = a
                    puzzle[j] = b
                }
            }
            val grade = if (g.n == 9 || g.n <= 6) logic.grade(puzzle) else LogicSolver.Grade(target, Technique.NAKED_SINGLE, true, 0)
            val score = kotlin.math.abs(grade.difficulty.ordinal - target.ordinal)
            if (score < bestScore) {
                bestScore = score
                best = Puzzle(g, puzzle.copyOf(), sol, grade.difficulty, grade.hardest)
            }
            if (score == 0) return best!!
        }
        return best!!
    }
}
