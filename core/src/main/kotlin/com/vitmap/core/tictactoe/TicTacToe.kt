package com.vitmap.core.tictactoe

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object Player {
    const val NONE = 0
    const val X = 1
    const val O = 2
    fun other(p: Int) = if (p == X) O else X
    fun symbol(p: Int) = when (p) { X -> "X"; O -> "O"; else -> "" }
}

data class Win(val player: Int, val cells: List<Int>)

/** Immutable n×n board where [winLength] in a row (any direction) wins. */
class Board(val size: Int, val winLength: Int, private val cells: IntArray = IntArray(size * size)) {
    init {
        require(size in 3..30) { "Board size must be 3..30" }
        require(winLength in 3..size) { "Win length must be between 3 and the board size" }
    }

    operator fun get(index: Int): Int = cells[index]
    fun get(row: Int, col: Int): Int = cells[row * size + col]
    val cellCount: Int get() = size * size
    val emptyCount: Int get() = cells.count { it == Player.NONE }
    val isFull: Boolean get() = cells.none { it == Player.NONE }
    val isEmpty: Boolean get() = cells.all { it == Player.NONE }

    fun play(index: Int, player: Int): Board {
        require(cells[index] == Player.NONE) { "Cell taken" }
        val c = cells.copyOf()
        c[index] = player
        return Board(size, winLength, c)
    }

    fun snapshot(): IntArray = cells.copyOf()

    /** Checks the lines through [index] for a win by whoever stands there. */
    fun winAt(index: Int): Win? {
        val p = cells[index]
        if (p == Player.NONE) return null
        val r0 = index / size
        val c0 = index % size
        for ((dr, dc) in DIRS) {
            val line = ArrayList<Int>()
            line += index
            var r = r0 + dr
            var c = c0 + dc
            while (r in 0 until size && c in 0 until size && cells[r * size + c] == p) { line += r * size + c; r += dr; c += dc }
            r = r0 - dr
            c = c0 - dc
            while (r in 0 until size && c in 0 until size && cells[r * size + c] == p) { line.add(0, r * size + c); r -= dr; c -= dc }
            if (line.size >= winLength) return Win(p, orderLine(line))
        }
        return null
    }

    private fun orderLine(line: List<Int>): List<Int> = line.sortedWith(compareBy({ it / size }, { it % size }))

    fun winner(): Win? {
        for (i in cells.indices) if (cells[i] != Player.NONE) winAt(i)?.let { return it }
        return null
    }

    companion object {
        val DIRS = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)

        fun defaultWinLength(size: Int): Int = when {
            size <= 3 -> 3
            size <= 6 -> 4
            else -> 5
        }
    }
}

enum class Difficulty(val title: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    HARD("Hard"),
}

class TicTacToeAi(private val random: Random = Random.Default) {
    fun chooseMove(board: Board, player: Int, difficulty: Difficulty): Int {
        val empties = (0 until board.cellCount).filter { board[it] == Player.NONE }
        require(empties.isNotEmpty()) { "Board is full" }
        if (board.isEmpty) {
            val centre = (board.size / 2) * board.size + board.size / 2
            return if (difficulty == Difficulty.EASY) empties.random(random) else centre
        }
        val opponent = Player.other(player)

        if (difficulty == Difficulty.EASY && random.nextDouble() < 0.55) return empties.random(random)

        // Win now, or block the opponent's immediate win.
        empties.firstOrNull { board.play(it, player).winAt(it) != null }?.let { return it }
        val block = empties.firstOrNull { board.play(it, opponent).winAt(it) != null }
        if (block != null && !(difficulty == Difficulty.EASY && random.nextDouble() < 0.5)) return block

        if (board.size == 3 && difficulty == Difficulty.HARD) return perfect(board, player)
        if (empties.size <= 9 && difficulty == Difficulty.HARD) return perfect(board, player)

        val candidates = candidates(board, player)
        return when (difficulty) {
            Difficulty.EASY -> candidates.take(4).random(random)
            Difficulty.MEDIUM -> if (random.nextDouble() < 0.2) candidates.take(3).random(random) else candidates.first()
            Difficulty.HARD -> searchBest(board, player, if (board.size <= 7) 4 else 3, candidates)
        }
    }

    // ------------------------------------------------------------------ exact minimax for small boards

    private val memo = HashMap<String, Int>()

    private fun perfect(board: Board, player: Int): Int {
        memo.clear()
        var best = -1
        var bestScore = Int.MIN_VALUE
        val moves = (0 until board.cellCount).filter { board[it] == Player.NONE }.shuffled(random)
        for (m in moves) {
            val next = board.play(m, player)
            val s = if (next.winAt(m) != null) 100 else -minimax(next, Player.other(player), 1, -1000, 1000)
            if (s > bestScore) { bestScore = s; best = m }
        }
        return best
    }

    private fun minimax(board: Board, toMove: Int, depth: Int, alphaIn: Int, beta: Int): Int {
        if (board.isFull) return 0
        val key = board.snapshot().joinToString("") + toMove
        memo[key]?.let { return it }
        var alpha = alphaIn
        var best = -1000
        for (m in 0 until board.cellCount) {
            if (board[m] != Player.NONE) continue
            val next = board.play(m, toMove)
            val s = if (next.winAt(m) != null) 100 - depth else -minimax(next, Player.other(toMove), depth + 1, -beta, -alpha)
            if (s > best) best = s
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        if (alphaIn < best && best < beta) memo[key] = best
        return best
    }

    // ------------------------------------------------------------------ heuristic search for big boards

    /** Empty cells near existing stones, ordered by a quick attack + defence score. */
    private fun candidates(board: Board, player: Int): List<Int> {
        val n = board.size
        val near = HashSet<Int>()
        for (i in 0 until board.cellCount) {
            if (board[i] == Player.NONE) continue
            val r = i / n
            val c = i % n
            for (dr in -2..2) for (dc in -2..2) {
                val rr = r + dr
                val cc = c + dc
                if (rr in 0 until n && cc in 0 until n && board[rr * n + cc] == Player.NONE) near += rr * n + cc
            }
        }
        if (near.isEmpty()) near += (0 until board.cellCount).filter { board[it] == Player.NONE }
        val opp = Player.other(player)
        return near.sortedByDescending { cellScore(board, it, player) * 1.0 + cellScore(board, it, opp) * 0.9 }
    }

    private fun cellScore(board: Board, index: Int, player: Int): Long {
        val n = board.size
        val k = board.winLength
        val r0 = index / n
        val c0 = index % n
        var total = 0L
        for ((dr, dc) in Board.DIRS) {
            for (offset in 0 until k) {
                val sr = r0 - dr * offset
                val sc = c0 - dc * offset
                val er = sr + dr * (k - 1)
                val ec = sc + dc * (k - 1)
                if (sr !in 0 until n || sc !in 0 until n || er !in 0 until n || ec !in 0 until n) continue
                var mine = 0
                var blocked = false
                for (t in 0 until k) {
                    val v = board[(sr + dr * t) * n + (sc + dc * t)]
                    if (v == player) mine++ else if (v != Player.NONE) { blocked = true; break }
                }
                if (!blocked) total += weight(mine + 1, k)
            }
        }
        return total
    }

    private fun evaluate(board: Board, player: Int): Long {
        val n = board.size
        val k = board.winLength
        var mine = 0L
        var theirs = 0L
        val opp = Player.other(player)
        for ((dr, dc) in Board.DIRS) {
            for (r in 0 until n) for (c in 0 until n) {
                val er = r + dr * (k - 1)
                val ec = c + dc * (k - 1)
                if (er !in 0 until n || ec !in 0 until n) continue
                var a = 0
                var b = 0
                for (t in 0 until k) {
                    when (board[(r + dr * t) * n + (c + dc * t)]) {
                        player -> a++
                        opp -> b++
                    }
                }
                if (a > 0 && b == 0) mine += weight(a, k)
                if (b > 0 && a == 0) theirs += weight(b, k)
            }
        }
        return mine - theirs * 12 / 10
    }

    private fun searchBest(board: Board, player: Int, depth: Int, candidates: List<Int>): Int {
        var best = candidates.first()
        var bestScore = Long.MIN_VALUE
        var alpha = Long.MIN_VALUE / 2
        val beta = Long.MAX_VALUE / 2
        for (m in candidates.take(BEAM)) {
            val next = board.play(m, player)
            val s = if (next.winAt(m) != null) WIN else -negamax(next, Player.other(player), depth - 1, -beta, -alpha, m)
            if (s > bestScore) { bestScore = s; best = m }
            alpha = max(alpha, s)
        }
        return best
    }

    private fun negamax(board: Board, toMove: Int, depth: Int, alphaIn: Long, beta: Long, last: Int): Long {
        if (board.isFull) return 0
        if (depth == 0) return evaluate(board, toMove)
        var alpha = alphaIn
        var best = Long.MIN_VALUE / 2
        val opp = Player.other(toMove)
        val moves = candidates(board, toMove).take(BEAM)
        // Forced moves: win if possible, otherwise answering an immediate threat is mandatory.
        for (m in moves) if (board.play(m, toMove).winAt(m) != null) return WIN - (10 - depth)
        val threat = moves.firstOrNull { board.play(it, opp).winAt(it) != null }
        val list = if (threat != null) listOf(threat) else moves
        for (m in list) {
            val s = -negamax(board.play(m, toMove), opp, depth - 1, -beta, -alpha, m)
            if (s > best) best = s
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    companion object {
        private const val BEAM = 10
        private const val WIN = 1_000_000_000_000L

        /** Value of a window holding [count] stones when [k] are needed: driven by how many are missing. */
        private fun weight(count: Int, k: Int): Long = when (k - min(count, k)) {
            0 -> 50_000_000
            1 -> 1_000_000
            2 -> 40_000
            3 -> 1_500
            4 -> 80
            5 -> 8
            else -> 1
        }
    }
}
