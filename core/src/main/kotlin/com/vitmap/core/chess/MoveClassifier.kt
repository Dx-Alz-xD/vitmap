package com.vitmap.core.chess

import kotlin.math.exp
import kotlin.math.max

enum class MoveQuality(val label: String, val nag: String) {
    BRILLIANT("Brilliant", "!!"),
    GREAT("Great", "!"),
    BEST("Best", ""),
    EXCELLENT("Excellent", ""),
    GOOD("Good", ""),
    BOOK("Book", ""),
    FORCED("Forced", ""),
    INACCURACY("Inaccuracy", "?!"),
    MISTAKE("Mistake", "?"),
    BLUNDER("Blunder", "??"),
    ;

    val isPositive: Boolean get() = ordinal <= GOOD.ordinal || this == BOOK || this == FORCED
}

/** Engine view of a position: best score and up to two candidate lines, from the side to move. */
data class PositionEval(
    val score: Int,
    val bestMove: Int,
    val secondScore: Int?,
    val depth: Int,
    val pv: List<Int> = emptyList(),
)

object MoveClassifier {
    /** Expected score (0..1) for a centipawn/mate score from the mover's point of view. */
    fun winProbability(score: Int): Double {
        if (Search.isMate(score)) return if (score > 0) 1.0 else 0.0
        return 1.0 / (1.0 + exp(-0.00368208 * score))
    }

    /** Lichess-style per-move accuracy from the drop in win probability. */
    fun accuracy(before: Int, after: Int): Double {
        val diff = max(0.0, (winProbability(before) - winProbability(after)) * 100.0)
        return (103.1668 * exp(-0.04354 * diff) - 3.1669).coerceIn(0.0, 100.0)
    }

    /**
     * @param before position with the mover to play
     * @param evalBefore engine evaluation of [before]
     * @param scoreAfter evaluation after [move], still from the mover's point of view
     */
    fun classify(before: Position, move: Int, evalBefore: PositionEval, scoreAfter: Int): MoveQuality {
        val legal = before.legalMoves()
        if (legal.size == 1) return MoveQuality.FORCED
        if (Openings.isBookMove(before, move)) return MoveQuality.BOOK

        val wpBefore = winProbability(evalBefore.score)
        val wpAfter = winProbability(scoreAfter)
        var loss = max(0.0, wpBefore - wpAfter)
        // Converting a won game less efficiently is rarely a real error.
        if (wpBefore >= 0.9 && wpAfter >= 0.9) loss *= 0.5

        val isBest = Move.key(move) == Move.key(evalBefore.bestMove) || loss < 0.004

        if (loss < 0.02 && wpAfter >= 0.5 && wpBefore < 0.97 && isSacrifice(before, move)) {
            return MoveQuality.BRILLIANT
        }
        if (isBest) {
            val second = evalBefore.secondScore
            if (second != null && wpBefore < 0.97 && wpBefore - winProbability(second) >= 0.15 && wpAfter >= 0.35) {
                return MoveQuality.GREAT
            }
            return MoveQuality.BEST
        }
        return when {
            loss < 0.02 -> MoveQuality.EXCELLENT
            loss < 0.05 -> MoveQuality.GOOD
            loss < 0.10 -> MoveQuality.INACCURACY
            loss < 0.20 -> MoveQuality.MISTAKE
            else -> MoveQuality.BLUNDER
        }
    }

    /** True if the move deliberately leaves material en prise (worth at least a minor piece net). */
    fun isSacrifice(before: Position, move: Int): Boolean {
        val pos = before.copy()
        val mover = pos.side
        val movedType = Piece.type(pos.board[Move.from(move)])
        if (movedType == Piece.KING) return false
        val captured = when {
            Move.isEnPassant(move) -> Piece.VALUE[Piece.PAWN]
            Move.isCapture(move) -> Piece.VALUE[Piece.type(pos.board[Move.to(move)])]
            else -> 0
        }
        val hangingBefore = maxHanging(pos, mover)
        if (!pos.makeMove(move)) return false
        val hangingAfter = maxHanging(pos, mover)
        // If the opponent is now mated or forced to deal with check, a "sacrifice" may simply be winning.
        return hangingAfter - captured >= 200 && hangingAfter > hangingBefore + 150
    }

    private fun maxHanging(pos: Position, color: Int): Int {
        var best = 0
        for (sq in 0 until 64) {
            val p = pos.board[sq]
            if (p == 0 || Piece.color(p) != color || Piece.type(p) == Piece.KING || Piece.type(p) == Piece.PAWN) continue
            val gain = pos.see(sq, color xor 1)
            if (gain > best) best = gain
        }
        return best
    }
}
