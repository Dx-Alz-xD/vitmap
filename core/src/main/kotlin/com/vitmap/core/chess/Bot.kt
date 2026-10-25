package com.vitmap.core.chess

import kotlin.math.sqrt
import kotlin.random.Random

/** How a bot of a given rating plays. Weaker bots search shallower and choose noisily. */
data class BotLevel(
    val elo: Int,
    val depth: Int,
    val timeMs: Long,
    val noiseCp: Int,
    val blunderChance: Double,
    val useBook: Boolean,
) {
    companion object {
        const val MIN_ELO = 100
        const val MAX_ELO = 3000

        fun forElo(elo: Int): BotLevel {
            val e = elo.coerceIn(MIN_ELO, MAX_ELO)
            val depth = when {
                e < 500 -> 1
                e < 1000 -> 2
                e < 1400 -> 3
                e < 1700 -> 4
                e < 2000 -> 5
                e < 2300 -> 7
                e < 2600 -> 10
                else -> 64
            }
            val time = when {
                e < 1400 -> 350L
                e < 2000 -> 700L
                e < 2300 -> 1100L
                e < 2600 -> 1800L
                else -> 3000L
            }
            val noise = maxOf(0, ((2300 - e) * 0.22).toInt())
            val blunder = if (e < 1600) (1600 - e) / 1500.0 * 0.2 else 0.0
            return BotLevel(e, depth, time, noise, blunder, e >= 900)
        }

        fun title(elo: Int): String = when {
            elo < 600 -> "Beginner"
            elo < 1000 -> "Novice"
            elo < 1400 -> "Casual"
            elo < 1800 -> "Club player"
            elo < 2100 -> "Expert"
            elo < 2400 -> "Master"
            elo < 2700 -> "Grandmaster"
            else -> "Engine"
        }
    }
}

data class BotDecision(val move: Int, val score: Int, val depth: Int, val fromBook: Boolean)

class ChessBot(
    private val search: Search = Search(),
    private val random: Random = Random.Default,
) {
    fun stop() {
        search.stopRequested = true
    }

    fun chooseMove(pos: Position, elo: Int): BotDecision {
        val level = BotLevel.forElo(elo)
        val legal = pos.legalMoves()
        require(legal.isNotEmpty()) { "No legal moves" }
        if (legal.size == 1) return BotDecision(legal[0], 0, 0, false)

        if (level.useBook && pos.fullmoveNumber <= 12) {
            val book = Openings.bookMoves(pos)
            if (book.isNotEmpty() && random.nextDouble() < 0.9) {
                return BotDecision(book[random.nextInt(book.size)], 0, 0, true)
            }
        }

        if (level.noiseCp == 0) {
            val info = search.search(pos, SearchLimits(maxDepth = level.depth, timeMs = level.timeMs))
            val line = info.best
            return BotDecision(line?.bestMove ?: legal[0], line?.score ?: 0, info.depth, false)
        }

        val scored = search.scoreRootMoves(pos, level.depth, level.timeMs)
        if (scored.isEmpty()) return BotDecision(legal[random.nextInt(legal.size)], 0, 0, false)

        if (random.nextDouble() < level.blunderChance) {
            // A careless move, but not one that walks straight into mate.
            val pool = scored.filter { it.second > -Search.MATE_BOUND }.ifEmpty { scored }
            val pick = pool[random.nextInt(pool.size)]
            return BotDecision(pick.first, pick.second, level.depth, false)
        }

        var bestMove = scored[0].first
        var bestNoisy = Double.NEGATIVE_INFINITY
        var bestRaw = scored[0].second
        for ((m, s) in scored) {
            val clamped = s.coerceIn(-3000, 3000)
            val noisy = clamped + gaussian() * level.noiseCp
            if (noisy > bestNoisy) {
                bestNoisy = noisy
                bestMove = m
                bestRaw = s
            }
        }
        return BotDecision(bestMove, bestRaw, level.depth, false)
    }

    private fun gaussian(): Double {
        // Box-Muller
        var u = 0.0
        while (u == 0.0) u = random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }
}
