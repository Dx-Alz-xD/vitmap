package com.vitmap.core.chess

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A principal variation; [score] is from the point of view of the side to move at the root. */
data class SearchLine(val moves: List<Int>, val score: Int, val depth: Int) {
    val bestMove: Int get() = moves.firstOrNull() ?: Move.NONE
}

data class SearchInfo(
    val depth: Int,
    val lines: List<SearchLine>,
    val nodes: Long,
    val timeMs: Long,
) {
    val best: SearchLine? get() = lines.firstOrNull()
    val nps: Long get() = if (timeMs <= 0) nodes * 1000 else nodes * 1000 / timeMs
}

data class SearchLimits(
    val maxDepth: Int = 64,
    val timeMs: Long = Long.MAX_VALUE,
    val nodes: Long = Long.MAX_VALUE,
    val multiPv: Int = 1,
)

/**
 * Iterative deepening alpha-beta (PVS) with a transposition table, null-move pruning, late move
 * reductions, killer/history ordering and quiescence search. One instance per thread.
 */
class Search(ttBits: Int = 18) {
    companion object {
        const val INF = 32000
        const val MATE = 31000
        const val MATE_BOUND = MATE - 1000
        const val MAX_PLY = 128

        private const val EXACT: Byte = 1
        private const val LOWER: Byte = 2
        private const val UPPER: Byte = 3

        private val FUTILITY = intArrayOf(0, 130, 240, 360)
        private val LMR = Array(64) { d -> IntArray(64) { m -> if (d == 0 || m == 0) 0 else (0.8 + ln(d.toDouble()) * ln(m.toDouble()) / 2.4).toInt() } }

        fun isMate(score: Int): Boolean = abs(score) >= MATE_BOUND

        /** Moves to mate: positive if the side to move mates, negative if it gets mated. */
        fun mateIn(score: Int): Int = if (score > 0) (MATE - score + 1) / 2 else -((MATE + score) / 2)
    }

    @Volatile
    var stopRequested = false

    var nodes = 0L
        private set

    private val ttSize = 1 shl ttBits
    private val ttMask = ttSize - 1
    private val ttKey = LongArray(ttSize)
    private val ttMove = IntArray(ttSize)
    private val ttScore = IntArray(ttSize)
    private val ttDepth = ByteArray(ttSize)
    private val ttFlag = ByteArray(ttSize)

    private val killers = Array(MAX_PLY + 1) { IntArray(2) }
    private val history = IntArray(2 * 64 * 64)
    private val moveBuf = Array(MAX_PLY + 1) { IntArray(256) }
    private val scoreBuf = Array(MAX_PLY + 1) { IntArray(256) }
    private val pvTable = Array(MAX_PLY + 1) { IntArray(MAX_PLY + 1) }
    private val pvLen = IntArray(MAX_PLY + 1)

    private var excluded = IntArray(0)
    private var aborted = false
    private var canAbort = false
    private var deadline = Long.MAX_VALUE
    private var nodeLimit = Long.MAX_VALUE
    private var startNs = 0L

    fun clear() {
        ttKey.fill(0)
        ttMove.fill(0)
        ttScore.fill(0)
        ttDepth.fill(0)
        ttFlag.fill(0)
        history.fill(0)
        for (k in killers) k.fill(0)
    }

    private fun prepare(limits: SearchLimits) {
        nodes = 0
        aborted = false
        canAbort = false
        stopRequested = false
        startNs = System.nanoTime()
        deadline = if (limits.timeMs == Long.MAX_VALUE) Long.MAX_VALUE else startNs + limits.timeMs * 1_000_000
        nodeLimit = limits.nodes
        for (k in killers) k.fill(0)
        for (i in history.indices) history[i] = history[i] / 8
    }

    private fun elapsedMs(): Long = (System.nanoTime() - startNs) / 1_000_000

    fun search(position: Position, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)? = null): SearchInfo {
        val pos = position.copy()
        prepare(limits)
        val legal = pos.legalMoves()
        if (legal.isEmpty()) return SearchInfo(0, emptyList(), 0, 0)
        val multiPv = limits.multiPv.coerceIn(1, legal.size)
        var best: SearchInfo? = null
        var prevScore = 0
        val maxDepth = limits.maxDepth.coerceIn(1, MAX_PLY - 16)

        for (depth in 1..maxDepth) {
            val lines = ArrayList<SearchLine>(multiPv)
            val excl = ArrayList<Int>()
            for (k in 0 until multiPv) {
                excluded = excl.toIntArray()
                var score: Int
                if (k == 0 && depth >= 5 && !isMate(prevScore)) {
                    var window = 35
                    var alpha = prevScore - window
                    var beta = prevScore + window
                    while (true) {
                        score = negamax(pos, depth, alpha, beta, 0, true)
                        if (aborted) break
                        if (score <= alpha) {
                            window *= 2
                            alpha = max(-INF, score - window)
                        } else if (score >= beta) {
                            window *= 2
                            beta = min(INF, score + window)
                        } else break
                        if (window > 800) { alpha = -INF; beta = INF }
                    }
                } else {
                    score = negamax(pos, depth, -INF, INF, 0, true)
                }
                if (aborted || pvLen[0] == 0) break
                val line = collectPv(pos)
                lines.add(SearchLine(line, score, depth))
                excl.add(line[0])
                if (k == 0) prevScore = score
            }
            excluded = IntArray(0)
            if (aborted) {
                if (best == null && lines.isNotEmpty()) best = SearchInfo(depth, lines, nodes, elapsedMs())
                break
            }
            canAbort = true
            lines.sortByDescending { it.score }
            val info = SearchInfo(depth, lines, nodes, elapsedMs())
            best = info
            onInfo?.invoke(info)
            if (stopRequested) break
            if (limits.timeMs != Long.MAX_VALUE) {
                if (elapsedMs() > limits.timeMs * 55 / 100) break
                if (legal.size == 1 && depth >= 3) break
            }
            if (isMate(lines[0].score) && depth > 2 * abs(mateIn(lines[0].score)) + 4) break
        }
        return best ?: SearchInfo(0, listOf(SearchLine(listOf(legal[0]), 0, 0)), nodes, elapsedMs())
    }

    /**
     * Scores every root move with a full window (used by the weaker bot levels to pick among
     * near-best moves). Results are sorted best first; scores are from the mover's perspective.
     */
    fun scoreRootMoves(position: Position, depth: Int, timeMs: Long): List<Pair<Int, Int>> {
        val pos = position.copy()
        prepare(SearchLimits(depth, timeMs))
        val legal = pos.legalMoves()
        var result: List<Pair<Int, Int>> = legal.map { it to 0 }
        for (d in 1..max(1, depth)) {
            val cur = ArrayList<Pair<Int, Int>>(legal.size)
            for ((m, _) in result) {
                pos.makeMove(m)
                val s = -negamax(pos, d - 1, -INF, INF, 1, true)
                pos.unmakeMove()
                if (aborted) break
                cur.add(m to s)
            }
            if (aborted) break
            result = cur.sortedByDescending { it.second }
            canAbort = true
        }
        return result
    }

    private fun collectPv(pos: Position): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until pvLen[0]) out.add(pvTable[0][i])
        // Extend a TT-truncated PV so the UI can show a longer line.
        var played = 0
        for (m in out) { if (!pos.makeMove(m)) break; played++ }
        while (out.size < 14) {
            val idx = ttIndex(pos.hash)
            if (ttKey[idx] != pos.hash || ttMove[idx] == 0) break
            val m = ttMove[idx]
            val legal = pos.legalMoves().firstOrNull { Move.key(it) == Move.key(m) } ?: break
            if (!pos.makeMove(legal)) break
            played++
            out.add(legal)
            if (pos.isRepetition()) break
        }
        repeat(played) { pos.unmakeMove() }
        return out
    }

    private fun ttIndex(hash: Long): Int = ((hash xor (hash ushr 29)).toInt()) and ttMask

    private fun toTt(score: Int, ply: Int): Int = when {
        score >= MATE_BOUND -> score + ply
        score <= -MATE_BOUND -> score - ply
        else -> score
    }

    private fun fromTt(score: Int, ply: Int): Int = when {
        score >= MATE_BOUND -> score - ply
        score <= -MATE_BOUND -> score + ply
        else -> score
    }

    private fun checkAbort(): Boolean {
        if (aborted) return true
        if (canAbort && (nodes and 1023L) == 0L) {
            if (stopRequested || System.nanoTime() > deadline || nodes > nodeLimit) aborted = true
        }
        return aborted
    }

    private fun isExcluded(m: Int): Boolean {
        for (e in excluded) if (Move.key(e) == Move.key(m)) return true
        return false
    }

    private fun negamax(pos: Position, depthIn: Int, alphaIn: Int, betaIn: Int, ply: Int, allowNull: Boolean): Int {
        var depth = depthIn
        var alpha = alphaIn
        var beta = betaIn
        pvLen[ply] = 0
        if (checkAbort()) return 0
        val isPv = beta - alpha > 1

        if (ply > 0) {
            if (pos.halfmoveClock >= 100 || pos.isRepetition() || pos.isInsufficientMaterial()) return 0
            alpha = max(alpha, -MATE + ply)
            beta = min(beta, MATE - ply - 1)
            if (alpha >= beta) return alpha
        }

        val inCheck = pos.inCheck()
        if (inCheck && ply < MAX_PLY - 20) depth++
        if (depth <= 0) return quiesce(pos, alpha, beta, ply)
        nodes++
        if (ply >= MAX_PLY - 4) return Evaluation.evaluate(pos)

        val idx = ttIndex(pos.hash)
        var ttMoveHere = 0
        if (ttKey[idx] == pos.hash) {
            ttMoveHere = ttMove[idx]
            if (ply > 0 && !isPv && ttDepth[idx] >= depth) {
                val s = fromTt(ttScore[idx], ply)
                when (ttFlag[idx]) {
                    EXACT -> return s
                    LOWER -> if (s >= beta) return s
                    UPPER -> if (s <= alpha) return s
                }
            }
        }

        val staticEval = if (inCheck) -INF else Evaluation.evaluate(pos)

        if (!isPv && !inCheck && ply > 0) {
            if (depth <= 6 && staticEval - 85 * depth >= beta && abs(beta) < MATE_BOUND) return staticEval
            if (allowNull && depth >= 3 && staticEval >= beta && pos.hasNonPawnMaterial(pos.side)) {
                val r = 3 + depth / 6
                pos.makeNullMove()
                val s = -negamax(pos, depth - 1 - r, -beta, -beta + 1, ply + 1, false)
                pos.unmakeNullMove()
                if (aborted) return 0
                if (s >= beta) return if (s >= MATE_BOUND) beta else s
            }
        }

        val moves = moveBuf[ply]
        val scores = scoreBuf[ply]
        val n = pos.generate(moves)
        scoreMoves(pos, moves, scores, n, ttMoveHere, ply)

        var best = -INF
        var bestMove = 0
        var legal = 0
        val origAlpha = alpha
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            if (ply == 0 && excluded.isNotEmpty() && isExcluded(m)) continue
            val quiet = Move.isQuiet(m)
            if (!isPv && !inCheck && quiet && legal > 0 && depth <= 3 && best > -MATE_BOUND &&
                legal >= 4 + depth * depth * 2
            ) continue
            if (!pos.makeMove(m)) continue
            legal++
            val givesCheck = pos.inCheck()
            if (!isPv && !inCheck && quiet && !givesCheck && depth <= 3 && legal > 1 && best > -MATE_BOUND &&
                staticEval + FUTILITY[depth] <= alpha
            ) {
                pos.unmakeMove()
                continue
            }

            var score: Int
            if (legal == 1) {
                score = -negamax(pos, depth - 1, -beta, -alpha, ply + 1, true)
            } else {
                var r = 0
                if (depth >= 3 && quiet && !inCheck && !givesCheck && legal > 3) {
                    r = LMR[min(depth, 63)][min(legal, 63)]
                    if (isPv) r--
                    if (m == killers[ply][0] || m == killers[ply][1]) r--
                    r = r.coerceIn(0, depth - 2)
                }
                score = -negamax(pos, depth - 1 - r, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && r > 0) score = -negamax(pos, depth - 1, -alpha - 1, -alpha, ply + 1, true)
                if (score > alpha && score < beta) score = -negamax(pos, depth - 1, -beta, -alpha, ply + 1, true)
            }
            pos.unmakeMove()
            if (aborted) return 0

            if (score > best) {
                best = score
                bestMove = m
                if (score > alpha) {
                    alpha = score
                    val row = pvTable[ply]
                    row[0] = m
                    val childLen = pvLen[ply + 1]
                    System.arraycopy(pvTable[ply + 1], 0, row, 1, childLen)
                    pvLen[ply] = childLen + 1
                    if (score >= beta) {
                        if (quiet) {
                            if (killers[ply][0] != m) {
                                killers[ply][1] = killers[ply][0]
                                killers[ply][0] = m
                            }
                            val h = pos.side * 4096 + Move.from(m) * 64 + Move.to(m)
                            history[h] += depth * depth
                            if (history[h] > 600_000) for (j in history.indices) history[j] /= 2
                        }
                        break
                    }
                }
            }
        }

        if (legal == 0) return if (inCheck) -MATE + ply else 0

        if (!(ply == 0 && excluded.isNotEmpty())) {
            val flag = when {
                best >= beta -> LOWER
                best > origAlpha -> EXACT
                else -> UPPER
            }
            if (ttKey[idx] != pos.hash || depth >= ttDepth[idx] - 1 || flag == EXACT) {
                ttKey[idx] = pos.hash
                ttMove[idx] = if (best > origAlpha) bestMove else (if (ttMoveHere != 0) ttMoveHere else bestMove)
                ttScore[idx] = toTt(best, ply)
                ttDepth[idx] = min(depth, 127).toByte()
                ttFlag[idx] = flag
            }
        }
        return best
    }

    private fun quiesce(pos: Position, alphaIn: Int, beta: Int, ply: Int): Int {
        var alpha = alphaIn
        pvLen[ply] = 0
        if (checkAbort()) return 0
        nodes++
        val standPat = Evaluation.evaluate(pos)
        if (ply >= MAX_PLY - 4) return standPat
        if (standPat >= beta) return standPat
        if (standPat > alpha) alpha = standPat

        val moves = moveBuf[ply]
        val scores = scoreBuf[ply]
        val n = pos.generate(moves, noisyOnly = true)
        scoreMoves(pos, moves, scores, n, 0, ply)
        var best = standPat
        for (i in 0 until n) {
            pickNext(moves, scores, i, n)
            val m = moves[i]
            if (!Move.isPromotion(m)) {
                val victim = if (Move.isEnPassant(m)) Piece.PAWN else Piece.type(pos.board[Move.to(m)])
                if (standPat + Piece.VALUE[victim] + 200 <= alpha) continue
            }
            if (!pos.makeMove(m)) continue
            val score = -quiesce(pos, -beta, -alpha, ply + 1)
            pos.unmakeMove()
            if (aborted) return 0
            if (score > best) {
                best = score
                if (score > alpha) {
                    alpha = score
                    if (score >= beta) break
                }
            }
        }
        return best
    }

    private fun scoreMoves(pos: Position, moves: IntArray, scores: IntArray, n: Int, ttMove: Int, ply: Int) {
        val ttKeyMove = Move.key(ttMove)
        val side = pos.side
        for (i in 0 until n) {
            val m = moves[i]
            scores[i] = when {
                ttMove != 0 && Move.key(m) == ttKeyMove -> 3_000_000
                Move.isCapture(m) -> {
                    val victim = if (Move.isEnPassant(m)) Piece.PAWN else Piece.type(pos.board[Move.to(m)])
                    val attacker = Piece.type(pos.board[Move.from(m)])
                    2_000_000 + victim * 100 - attacker + Move.promo(m) * 10
                }
                Move.isPromotion(m) -> 1_900_000 + Move.promo(m)
                m == killers[ply][0] -> 1_800_000
                m == killers[ply][1] -> 1_790_000
                else -> history[side * 4096 + Move.from(m) * 64 + Move.to(m)]
            }
        }
    }

    private fun pickNext(moves: IntArray, scores: IntArray, start: Int, n: Int) {
        var bestIdx = start
        var bestScore = scores[start]
        for (j in start + 1 until n) {
            if (scores[j] > bestScore) {
                bestScore = scores[j]
                bestIdx = j
            }
        }
        if (bestIdx != start) {
            val tm = moves[start]; moves[start] = moves[bestIdx]; moves[bestIdx] = tm
            val ts = scores[start]; scores[start] = scores[bestIdx]; scores[bestIdx] = ts
        }
    }
}
