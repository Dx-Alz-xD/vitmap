package com.vitmap.core.chess

/**
 * Pieces are small ints: bit 3 is the colour (0 white, 1 black), bits 0-2 the type.
 * Squares run 0 (a1) .. 63 (h8).
 */
object Piece {
    const val NONE = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6

    const val WHITE = 0
    const val BLACK = 1

    const val WP = PAWN
    const val WN = KNIGHT
    const val WB = BISHOP
    const val WR = ROOK
    const val WQ = QUEEN
    const val WK = KING
    const val BP = 8 or PAWN
    const val BN = 8 or KNIGHT
    const val BB = 8 or BISHOP
    const val BR = 8 or ROOK
    const val BQ = 8 or QUEEN
    const val BK = 8 or KING

    fun make(color: Int, type: Int): Int = (color shl 3) or type
    fun type(p: Int): Int = p and 7
    fun color(p: Int): Int = p shr 3

    /** Material values in centipawns used for SEE, ordering and material counts. */
    val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 20000)

    fun toChar(p: Int): Char {
        val c = when (type(p)) {
            PAWN -> 'p'
            KNIGHT -> 'n'
            BISHOP -> 'b'
            ROOK -> 'r'
            QUEEN -> 'q'
            KING -> 'k'
            else -> '.'
        }
        return if (p != NONE && color(p) == WHITE) c.uppercaseChar() else c
    }

    fun fromChar(c: Char): Int {
        val type = when (c.lowercaseChar()) {
            'p' -> PAWN
            'n' -> KNIGHT
            'b' -> BISHOP
            'r' -> ROOK
            'q' -> QUEEN
            'k' -> KING
            else -> return NONE
        }
        return make(if (c.isUpperCase()) WHITE else BLACK, type)
    }

    fun sanLetter(type: Int): String = when (type) {
        KNIGHT -> "N"
        BISHOP -> "B"
        ROOK -> "R"
        QUEEN -> "Q"
        KING -> "K"
        else -> ""
    }
}

object Square {
    fun file(sq: Int): Int = sq and 7
    fun rank(sq: Int): Int = sq shr 3
    fun of(file: Int, rank: Int): Int = rank * 8 + file
    fun name(sq: Int): String = "${'a' + file(sq)}${'1' + rank(sq)}"
    fun isLight(sq: Int): Boolean = (file(sq) + rank(sq)) % 2 == 1

    fun parse(s: String): Int {
        require(s.length == 2) { "Bad square '$s'" }
        val f = s[0] - 'a'
        val r = s[1] - '1'
        require(f in 0..7 && r in 0..7) { "Bad square '$s'" }
        return of(f, r)
    }
}

/**
 * Moves are packed ints: from (6 bits) | to (6 bits) | promotion type (3 bits) | flags.
 */
object Move {
    const val NONE = 0
    const val CAPTURE = 1 shl 15
    const val EN_PASSANT = 1 shl 16
    const val CASTLE = 1 shl 17
    const val DOUBLE_PUSH = 1 shl 18

    fun of(from: Int, to: Int, promo: Int = 0, flags: Int = 0): Int =
        from or (to shl 6) or (promo shl 12) or flags

    fun from(m: Int): Int = m and 63
    fun to(m: Int): Int = (m shr 6) and 63
    fun promo(m: Int): Int = (m shr 12) and 7
    fun isCapture(m: Int): Boolean = m and CAPTURE != 0
    fun isEnPassant(m: Int): Boolean = m and EN_PASSANT != 0
    fun isCastle(m: Int): Boolean = m and CASTLE != 0
    fun isPromotion(m: Int): Boolean = promo(m) != 0
    fun isQuiet(m: Int): Boolean = !isCapture(m) && !isPromotion(m)

    /** from/to/promo only, so moves compare equal regardless of flag bits. */
    fun key(m: Int): Int = m and 0x7FFF

    fun uci(m: Int): String {
        if (m == NONE) return "0000"
        val p = promo(m)
        val suffix = if (p == 0) "" else Piece.toChar(p).toString()
        return Square.name(from(m)) + Square.name(to(m)) + suffix
    }
}

internal object Attacks {
    val KNIGHT: Array<IntArray> = Array(64) { sq -> jumps(sq, intArrayOf(1, 2, 2, 1, -1, -2, -2, -1), intArrayOf(2, 1, -1, -2, -2, -1, 1, 2)) }
    val KING: Array<IntArray> = Array(64) { sq -> jumps(sq, intArrayOf(1, 1, 1, 0, -1, -1, -1, 0), intArrayOf(1, 0, -1, -1, -1, 0, 1, 1)) }

    /** Ray directions: 0..3 orthogonal (N, S, E, W), 4..7 diagonal (NE, NW, SE, SW). */
    private val DF = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private val DR = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)

    val RAYS: Array<Array<IntArray>> = Array(8) { d ->
        Array(64) { sq ->
            val out = ArrayList<Int>()
            var f = Square.file(sq) + DF[d]
            var r = Square.rank(sq) + DR[d]
            while (f in 0..7 && r in 0..7) {
                out.add(Square.of(f, r))
                f += DF[d]
                r += DR[d]
            }
            out.toIntArray()
        }
    }

    private fun jumps(sq: Int, df: IntArray, dr: IntArray): IntArray {
        val out = ArrayList<Int>()
        for (i in df.indices) {
            val f = Square.file(sq) + df[i]
            val r = Square.rank(sq) + dr[i]
            if (f in 0..7 && r in 0..7) out.add(Square.of(f, r))
        }
        return out.toIntArray()
    }

    /** Chebyshev distance between two squares. */
    fun distance(a: Int, b: Int): Int =
        maxOf(kotlin.math.abs(Square.file(a) - Square.file(b)), kotlin.math.abs(Square.rank(a) - Square.rank(b)))
}

internal object Zobrist {
    val PIECE: Array<LongArray>
    val SIDE: Long
    val CASTLING: LongArray
    val EP_FILE: LongArray

    init {
        var s = 0x9E3779B97F4A7C15uL.toLong()
        fun next(): Long {
            // splitmix64: deterministic so hashes are stable across runs
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        PIECE = Array(16) { LongArray(64) { next() } }
        SIDE = next()
        CASTLING = LongArray(16) { next() }
        EP_FILE = LongArray(8) { next() }
    }
}
