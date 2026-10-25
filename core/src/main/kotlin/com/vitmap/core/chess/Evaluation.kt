package com.vitmap.core.chess

import com.vitmap.core.chess.Piece.BLACK
import com.vitmap.core.chess.Piece.WHITE
import kotlin.math.abs

/**
 * Tapered evaluation built on the PeSTO piece-square tables plus a few cheap structural terms.
 * Scores are centipawns from the side to move's point of view.
 */
object Evaluation {
    private val MG_VALUE = intArrayOf(0, 82, 337, 365, 477, 1025, 0)
    private val EG_VALUE = intArrayOf(0, 94, 281, 297, 512, 936, 0)
    private val PHASE_INC = intArrayOf(0, 0, 1, 1, 2, 4, 0)

    // Tables are written rank 8 first (index 0 = a8) from white's point of view.
    private val MG_PAWN = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        98, 134, 61, 95, 68, 126, 34, -11,
        -6, 7, 26, 31, 65, 56, 25, -20,
        -14, 13, 6, 21, 23, 12, 17, -23,
        -27, -2, -5, 12, 17, 6, 10, -25,
        -26, -4, -4, -10, 3, 3, 33, -12,
        -35, -1, -20, -23, -15, 24, 38, -22,
        0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val EG_PAWN = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        178, 173, 158, 134, 147, 132, 165, 187,
        94, 100, 85, 67, 56, 53, 82, 84,
        32, 24, 13, 5, -2, 4, 17, 17,
        13, 9, -3, -7, -7, -8, 3, -1,
        4, 7, -6, 1, 0, -5, -1, -8,
        13, 8, 8, 10, 13, 0, 2, -7,
        0, 0, 0, 0, 0, 0, 0, 0,
    )
    private val MG_KNIGHT = intArrayOf(
        -167, -89, -34, -49, 61, -97, -15, -107,
        -73, -41, 72, 36, 23, 62, 7, -17,
        -47, 60, 37, 65, 84, 129, 73, 44,
        -9, 17, 19, 53, 37, 69, 18, 22,
        -13, 4, 16, 13, 28, 19, 21, -8,
        -23, -9, 12, 10, 19, 17, 25, -16,
        -29, -53, -12, -3, -1, 18, -14, -19,
        -105, -21, -58, -33, -17, -28, -19, -23,
    )
    private val EG_KNIGHT = intArrayOf(
        -58, -38, -13, -28, -31, -27, -63, -99,
        -25, -8, -25, -2, -9, -25, -24, -52,
        -24, -20, 10, 9, -1, -9, -19, -41,
        -17, 3, 22, 22, 22, 11, 8, -18,
        -18, -6, 16, 25, 16, 17, 4, -18,
        -23, -3, -1, 15, 10, -3, -20, -22,
        -42, -20, -10, -5, -2, -20, -23, -44,
        -29, -51, -23, -15, -22, -18, -50, -64,
    )
    private val MG_BISHOP = intArrayOf(
        -29, 4, -82, -37, -25, -42, 7, -8,
        -26, 16, -18, -13, 30, 59, 18, -47,
        -16, 37, 43, 40, 35, 50, 37, -2,
        -4, 5, 19, 50, 37, 37, 7, -2,
        -6, 13, 13, 26, 34, 12, 10, 4,
        0, 15, 15, 15, 14, 27, 18, 10,
        4, 15, 16, 0, 7, 21, 33, 1,
        -33, -3, -14, -21, -13, -12, -39, -21,
    )
    private val EG_BISHOP = intArrayOf(
        -14, -21, -11, -8, -7, -9, -17, -24,
        -8, -4, 7, -12, -3, -13, -4, -14,
        2, -8, 0, -1, -2, 6, 0, 4,
        -3, 9, 12, 9, 14, 10, 3, 2,
        -6, 3, 13, 19, 7, 10, -3, -9,
        -12, -3, 8, 10, 13, 3, -7, -15,
        -14, -18, -7, -1, 4, -9, -15, -27,
        -23, -9, -23, -5, -9, -16, -5, -17,
    )
    private val MG_ROOK = intArrayOf(
        32, 42, 32, 51, 63, 9, 31, 43,
        27, 32, 58, 62, 80, 67, 26, 44,
        -5, 19, 26, 36, 17, 45, 61, 16,
        -24, -11, 7, 26, 24, 35, -8, -20,
        -36, -26, -12, -1, 9, -7, 6, -23,
        -45, -25, -16, -17, 3, 0, -5, -33,
        -44, -16, -20, -9, -1, 11, -6, -71,
        -19, -13, 1, 17, 16, 7, -37, -26,
    )
    private val EG_ROOK = intArrayOf(
        13, 10, 18, 15, 12, 12, 8, 5,
        11, 13, 13, 11, -3, 3, 8, 3,
        7, 7, 7, 5, 4, -3, -5, -3,
        4, 3, 13, 1, 2, 1, -1, 2,
        3, 5, 8, 4, -5, -6, -8, -11,
        -4, 0, -5, -1, -7, -12, -8, -16,
        -6, -6, 0, 2, -9, -9, -11, -3,
        -9, 2, 3, -1, -5, -13, 4, -20,
    )
    private val MG_QUEEN = intArrayOf(
        -28, 0, 29, 12, 59, 44, 43, 45,
        -24, -39, -5, 1, -16, 57, 28, 54,
        -13, -17, 7, 8, 29, 56, 47, 57,
        -27, -27, -16, -16, -1, 17, -2, 1,
        -9, -26, -9, -10, -2, -4, 3, -3,
        -14, 2, -11, -2, -5, 2, 14, 5,
        -35, -8, 11, 2, 8, 15, -3, 1,
        -1, -18, -9, 10, -15, -25, -31, -50,
    )
    private val EG_QUEEN = intArrayOf(
        -9, 22, 22, 27, 27, 19, 10, 20,
        -17, 20, 32, 41, 58, 25, 30, 0,
        -20, 6, 9, 49, 47, 35, 19, 9,
        3, 22, 24, 45, 57, 40, 57, 36,
        -18, 28, 19, 47, 31, 34, 39, 23,
        -16, -27, 15, 6, 9, 17, 10, 5,
        -22, -23, -30, -16, -16, -23, -36, -32,
        -33, -28, -22, -43, -5, -32, -20, -41,
    )
    private val MG_KING = intArrayOf(
        -65, 23, 16, -15, -56, -34, 2, 13,
        29, -1, -20, -7, -8, -4, -38, -29,
        -9, 24, 2, -16, -20, 6, 22, -22,
        -17, -20, -12, -27, -30, -25, -14, -36,
        -49, -1, -27, -39, -46, -44, -33, -51,
        -14, -14, -22, -46, -44, -30, -15, -27,
        1, 7, -8, -64, -43, -16, 9, 8,
        -15, 36, 12, -54, 8, -28, 24, 14,
    )
    private val EG_KING = intArrayOf(
        -74, -35, -18, -18, -11, 15, 4, -17,
        -12, 17, 14, 17, 17, 38, 23, 11,
        10, 17, 23, 15, 20, 45, 44, 13,
        -8, 22, 24, 27, 26, 33, 26, 3,
        -18, -4, 21, 24, 27, 23, 9, -11,
        -19, -3, 11, 21, 23, 16, 7, -9,
        -27, -11, 4, 13, 14, 4, -5, -17,
        -53, -34, -21, -11, -28, -14, -24, -43,
    )

    private val MG_TABLES = arrayOf(IntArray(64), MG_PAWN, MG_KNIGHT, MG_BISHOP, MG_ROOK, MG_QUEEN, MG_KING)
    private val EG_TABLES = arrayOf(IntArray(64), EG_PAWN, EG_KNIGHT, EG_BISHOP, EG_ROOK, EG_QUEEN, EG_KING)

    /** [piece][square] with material folded in, for both colours. */
    private val MG = Array(16) { IntArray(64) }
    private val EG = Array(16) { IntArray(64) }

    private val PASSED_BONUS_EG = intArrayOf(0, 10, 17, 25, 50, 90, 140, 0)
    private val PASSED_BONUS_MG = intArrayOf(0, 5, 8, 12, 25, 45, 70, 0)

    /** Squares in front of a pawn (same and adjacent files) used for the passed pawn test. */
    private val PASSED_MASK = Array(2) { LongArray(64) }
    private val FILE_MASK = LongArray(8) { f -> var m = 0L; for (r in 0..7) m = m or (1L shl (r * 8 + f)); m }

    init {
        for (type in 1..6) {
            for (sq in 0 until 64) {
                // White piece on sq reads table entry for the mirrored index (tables start at a8).
                val whiteIdx = sq xor 56
                MG[Piece.make(WHITE, type)][sq] = MG_VALUE[type] + MG_TABLES[type][whiteIdx]
                EG[Piece.make(WHITE, type)][sq] = EG_VALUE[type] + EG_TABLES[type][whiteIdx]
                MG[Piece.make(BLACK, type)][sq] = MG_VALUE[type] + MG_TABLES[type][sq]
                EG[Piece.make(BLACK, type)][sq] = EG_VALUE[type] + EG_TABLES[type][sq]
            }
        }
        for (sq in 0 until 64) {
            val f = Square.file(sq)
            val r = Square.rank(sq)
            var w = 0L
            var b = 0L
            for (df in -1..1) {
                val ff = f + df
                if (ff !in 0..7) continue
                for (rr in r + 1..7) w = w or (1L shl (rr * 8 + ff))
                for (rr in 0 until r) b = b or (1L shl (rr * 8 + ff))
            }
            PASSED_MASK[WHITE][sq] = w
            PASSED_MASK[BLACK][sq] = b
        }
    }

    fun evaluate(pos: Position): Int {
        val board = pos.board
        val mg = IntArray(2)
        val eg = IntArray(2)
        var phase = 0
        var whitePawns = 0L
        var blackPawns = 0L
        val bishops = IntArray(2)
        val nonPawn = IntArray(2)
        val pawnCount = IntArray(2)

        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0) continue
            val c = Piece.color(p)
            val t = Piece.type(p)
            mg[c] += MG[p][sq]
            eg[c] += EG[p][sq]
            phase += PHASE_INC[t]
            when (t) {
                Piece.PAWN -> {
                    pawnCount[c]++
                    if (c == WHITE) whitePawns = whitePawns or (1L shl sq) else blackPawns = blackPawns or (1L shl sq)
                }
                Piece.BISHOP -> { bishops[c]++; nonPawn[c] += Piece.VALUE[t] }
                Piece.KING -> {}
                else -> nonPawn[c] += Piece.VALUE[t]
            }
        }

        // Bishop pair
        for (c in 0..1) if (bishops[c] >= 2) { mg[c] += 30; eg[c] += 50 }

        // Pawn structure
        evalPawns(WHITE, whitePawns, blackPawns, mg, eg)
        evalPawns(BLACK, blackPawns, whitePawns, mg, eg)

        // Rooks on open / half-open files
        for (sq in 0 until 64) {
            val p = board[sq]
            if (Piece.type(p) != Piece.ROOK) continue
            val c = Piece.color(p)
            val fileMask = FILE_MASK[Square.file(sq)]
            val own = if (c == WHITE) whitePawns else blackPawns
            val opp = if (c == WHITE) blackPawns else whitePawns
            if (own and fileMask == 0L) {
                if (opp and fileMask == 0L) { mg[c] += 25; eg[c] += 10 } else { mg[c] += 12; eg[c] += 5 }
            }
        }

        val clampedPhase = minOf(phase, 24)
        var score = ((mg[WHITE] - mg[BLACK]) * clampedPhase + (eg[WHITE] - eg[BLACK]) * (24 - clampedPhase)) / 24

        // Mop-up: help the engine convert won pawnless endings by herding the enemy king.
        if (pawnCount[WHITE] == 0 || pawnCount[BLACK] == 0) {
            val diff = nonPawn[WHITE] - nonPawn[BLACK]
            if (abs(diff) >= 400) {
                val winner = if (diff > 0) WHITE else BLACK
                if (pawnCount[winner xor 1] == 0) {
                    val loserKing = pos.kingSquare[winner xor 1]
                    val winnerKing = pos.kingSquare[winner]
                    val centre = centreDistance(loserKing)
                    val close = 14 - manhattan(loserKing, winnerKing)
                    val bonus = centre * 10 + close * 4
                    score += if (winner == WHITE) bonus else -bonus
                }
            }
        }

        // Drawish material: the stronger side has no pawns and at most a minor piece extra.
        val stronger = if (score > 0) WHITE else BLACK
        if (pawnCount[stronger] == 0 && nonPawn[stronger] - nonPawn[stronger xor 1] <= 330) {
            score /= 4
        }

        val tempo = 10
        return if (pos.side == WHITE) score + tempo else -score + tempo
    }

    private fun evalPawns(c: Int, own: Long, opp: Long, mg: IntArray, eg: IntArray) {
        var bits = own
        while (bits != 0L) {
            val sq = java.lang.Long.numberOfTrailingZeros(bits)
            bits = bits and (bits - 1)
            val f = Square.file(sq)
            val relRank = if (c == WHITE) Square.rank(sq) else 7 - Square.rank(sq)
            if (opp and PASSED_MASK[c][sq] == 0L) {
                mg[c] += PASSED_BONUS_MG[relRank]
                eg[c] += PASSED_BONUS_EG[relRank]
            }
            val left = if (f > 0) FILE_MASK[f - 1] else 0L
            val right = if (f < 7) FILE_MASK[f + 1] else 0L
            if (own and (left or right) == 0L) { mg[c] -= 12; eg[c] -= 16 }
        }
        for (f in 0..7) {
            val n = java.lang.Long.bitCount(own and FILE_MASK[f])
            if (n > 1) { mg[c] -= 10 * (n - 1); eg[c] -= 20 * (n - 1) }
        }
    }

    private fun centreDistance(sq: Int): Int {
        val f = Square.file(sq)
        val r = Square.rank(sq)
        val df = maxOf(3 - f, f - 4)
        val dr = maxOf(3 - r, r - 4)
        return df + dr
    }

    private fun manhattan(a: Int, b: Int): Int =
        abs(Square.file(a) - Square.file(b)) + abs(Square.rank(a) - Square.rank(b))

    /** Material balance in pawns (white minus black), for the captured-pieces strip. */
    fun materialBalance(pos: Position): Int {
        var s = 0
        for (sq in 0 until 64) {
            val p = pos.board[sq]
            if (p == 0 || Piece.type(p) == Piece.KING) continue
            val v = when (Piece.type(p)) {
                Piece.PAWN -> 1
                Piece.KNIGHT, Piece.BISHOP -> 3
                Piece.ROOK -> 5
                Piece.QUEEN -> 9
                else -> 0
            }
            s += if (Piece.color(p) == WHITE) v else -v
        }
        return s
    }
}
