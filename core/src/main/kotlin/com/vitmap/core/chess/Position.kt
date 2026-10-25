package com.vitmap.core.chess

import com.vitmap.core.chess.Piece.BLACK
import com.vitmap.core.chess.Piece.WHITE

/**
 * Mutable chess position with make/unmake, used both by the engine and (through copies) by the UI.
 */
class Position private constructor() {
    val board = IntArray(64)
    var side = WHITE
        private set
    /** Bits: 1 = white O-O, 2 = white O-O-O, 4 = black O-O, 8 = black O-O-O. */
    var castling = 0
        private set
    var epSquare = -1
        private set
    var halfmoveClock = 0
        private set
    var fullmoveNumber = 1
        private set
    var hash = 0L
        private set
    val kingSquare = IntArray(2)

    // Undo stack
    private var sp = 0
    private var uMove = IntArray(256)
    private var uCaptured = IntArray(256)
    private var uCastling = IntArray(256)
    private var uEp = IntArray(256)
    private var uHalfmove = IntArray(256)
    private var uHash = LongArray(256)

    // Hashes of every earlier position (game history + search path), for repetition detection
    private var history = LongArray(512)
    private var historyLen = 0

    fun copy(): Position {
        val p = Position()
        board.copyInto(p.board)
        p.side = side
        p.castling = castling
        p.epSquare = epSquare
        p.halfmoveClock = halfmoveClock
        p.fullmoveNumber = fullmoveNumber
        p.hash = hash
        kingSquare.copyInto(p.kingSquare)
        p.history = history.copyOf(maxOf(512, historyLen * 2))
        p.historyLen = historyLen
        return p
    }

    /** Hashes of previous positions in the game, oldest first. Used for threefold / search repetition. */
    fun setHistory(hashes: LongArray) {
        history = hashes.copyOf(maxOf(512, hashes.size * 2))
        historyLen = hashes.size
    }

    fun historyHashes(): LongArray = history.copyOf(historyLen)

    fun pieceAt(sq: Int): Int = board[sq]

    // ---------------------------------------------------------------- attacks

    fun isAttacked(sq: Int, by: Int): Boolean {
        val f = Square.file(sq)
        if (by == WHITE) {
            if (f > 0 && sq >= 9 && board[sq - 9] == Piece.WP) return true
            if (f < 7 && sq >= 7 && board[sq - 7] == Piece.WP) return true
        } else {
            if (f > 0 && sq + 7 < 64 && board[sq + 7] == Piece.BP) return true
            if (f < 7 && sq + 9 < 64 && board[sq + 9] == Piece.BP) return true
        }
        val knight = Piece.make(by, Piece.KNIGHT)
        for (t in Attacks.KNIGHT[sq]) if (board[t] == knight) return true
        val king = Piece.make(by, Piece.KING)
        for (t in Attacks.KING[sq]) if (board[t] == king) return true
        val rook = Piece.make(by, Piece.ROOK)
        val bishop = Piece.make(by, Piece.BISHOP)
        val queen = Piece.make(by, Piece.QUEEN)
        for (d in 0 until 8) {
            val ray = Attacks.RAYS[d][sq]
            for (t in ray) {
                val p = board[t]
                if (p == 0) continue
                if (p == queen) return true
                if (d < 4 && p == rook) return true
                if (d >= 4 && p == bishop) return true
                break
            }
        }
        return false
    }

    fun inCheck(): Boolean = isAttacked(kingSquare[side], side xor 1)

    fun inCheck(color: Int): Boolean = isAttacked(kingSquare[color], color xor 1)

    /** Least valuable piece of [by] attacking [sq] on [b], or -1. Used by SEE. */
    internal fun leastValuableAttacker(b: IntArray, sq: Int, by: Int): Int {
        val f = Square.file(sq)
        if (by == WHITE) {
            if (f > 0 && sq >= 9 && b[sq - 9] == Piece.WP) return sq - 9
            if (f < 7 && sq >= 7 && b[sq - 7] == Piece.WP) return sq - 7
        } else {
            if (f > 0 && sq + 7 < 64 && b[sq + 7] == Piece.BP) return sq + 7
            if (f < 7 && sq + 9 < 64 && b[sq + 9] == Piece.BP) return sq + 9
        }
        val knight = Piece.make(by, Piece.KNIGHT)
        for (t in Attacks.KNIGHT[sq]) if (b[t] == knight) return t
        var bestSq = -1
        var bestVal = Int.MAX_VALUE
        for (d in 0 until 8) {
            for (t in Attacks.RAYS[d][sq]) {
                val p = b[t]
                if (p == 0) continue
                if (Piece.color(p) == by) {
                    val type = Piece.type(p)
                    val ok = type == Piece.QUEEN || (d < 4 && type == Piece.ROOK) || (d >= 4 && type == Piece.BISHOP)
                    if (ok && Piece.VALUE[type] < bestVal) {
                        bestVal = Piece.VALUE[type]
                        bestSq = t
                    }
                }
                break
            }
        }
        if (bestSq >= 0) return bestSq
        val king = Piece.make(by, Piece.KING)
        for (t in Attacks.KING[sq]) if (b[t] == king) return t
        return -1
    }

    /**
     * Static exchange evaluation: material [by] can win by starting a capture sequence on [sq].
     * Returns 0 when capturing is not profitable (the side can always decline).
     */
    fun see(sq: Int, by: Int): Int {
        val b = board.copyOf()
        if (b[sq] == 0) return 0
        val gain = IntArray(40)
        var d = 0
        var color = by
        gain[0] = Piece.VALUE[Piece.type(b[sq])]
        var attacker = leastValuableAttacker(b, sq, color)
        while (attacker >= 0 && d < 38) {
            val moving = b[attacker]
            b[attacker] = 0
            // A king may only capture onto a square the opponent no longer covers.
            if (Piece.type(moving) == Piece.KING && leastValuableAttacker(b, sq, color xor 1) >= 0) {
                b[attacker] = moving
                break
            }
            b[sq] = moving
            d++
            gain[d] = Piece.VALUE[Piece.type(moving)] - gain[d - 1]
            color = color xor 1
            attacker = leastValuableAttacker(b, sq, color)
        }
        if (d == 0) return 0
        while (--d > 0) gain[d - 1] = -maxOf(-gain[d - 1], gain[d])
        return maxOf(0, gain[0])
    }

    // ---------------------------------------------------------------- move generation

    /** Pseudo-legal moves. With [noisyOnly] only captures and queen promotions are produced. */
    fun generate(out: IntArray, noisyOnly: Boolean = false): Int {
        var n = 0
        val us = side
        val them = us xor 1
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0 || Piece.color(p) != us) continue
            when (Piece.type(p)) {
                Piece.PAWN -> n = genPawn(sq, us, out, n, noisyOnly)
                Piece.KNIGHT -> for (t in Attacks.KNIGHT[sq]) {
                    val q = board[t]
                    if (q == 0) {
                        if (!noisyOnly) out[n++] = Move.of(sq, t)
                    } else if (Piece.color(q) == them) out[n++] = Move.of(sq, t, 0, Move.CAPTURE)
                }
                Piece.BISHOP -> n = genSlider(sq, 4, 8, them, out, n, noisyOnly)
                Piece.ROOK -> n = genSlider(sq, 0, 4, them, out, n, noisyOnly)
                Piece.QUEEN -> n = genSlider(sq, 0, 8, them, out, n, noisyOnly)
                Piece.KING -> {
                    for (t in Attacks.KING[sq]) {
                        val q = board[t]
                        if (q == 0) {
                            if (!noisyOnly) out[n++] = Move.of(sq, t)
                        } else if (Piece.color(q) == them) out[n++] = Move.of(sq, t, 0, Move.CAPTURE)
                    }
                    if (!noisyOnly) n = genCastles(us, out, n)
                }
            }
        }
        return n
    }

    private fun genSlider(sq: Int, d0: Int, d1: Int, them: Int, out: IntArray, start: Int, noisyOnly: Boolean): Int {
        var n = start
        for (d in d0 until d1) {
            for (t in Attacks.RAYS[d][sq]) {
                val q = board[t]
                if (q == 0) {
                    if (!noisyOnly) out[n++] = Move.of(sq, t)
                    continue
                }
                if (Piece.color(q) == them) out[n++] = Move.of(sq, t, 0, Move.CAPTURE)
                break
            }
        }
        return n
    }

    private fun genPawn(sq: Int, us: Int, out: IntArray, start: Int, noisyOnly: Boolean): Int {
        var n = start
        val dir = if (us == WHITE) 8 else -8
        val startRank = if (us == WHITE) 1 else 6
        val promoRank = if (us == WHITE) 7 else 0
        val to = sq + dir
        if (to in 0..63 && board[to] == 0) {
            if (Square.rank(to) == promoRank) {
                out[n++] = Move.of(sq, to, Piece.QUEEN)
                if (!noisyOnly) {
                    out[n++] = Move.of(sq, to, Piece.ROOK)
                    out[n++] = Move.of(sq, to, Piece.BISHOP)
                    out[n++] = Move.of(sq, to, Piece.KNIGHT)
                }
            } else if (!noisyOnly) {
                out[n++] = Move.of(sq, to)
                if (Square.rank(sq) == startRank && board[to + dir] == 0) {
                    out[n++] = Move.of(sq, to + dir, 0, Move.DOUBLE_PUSH)
                }
            }
        }
        val f = Square.file(sq)
        for (df in intArrayOf(-1, 1)) {
            if (f + df !in 0..7) continue
            val t = sq + dir + df
            if (t !in 0..63) continue
            val q = board[t]
            if (q != 0 && Piece.color(q) != us) {
                if (Square.rank(t) == promoRank) {
                    out[n++] = Move.of(sq, t, Piece.QUEEN, Move.CAPTURE)
                    if (!noisyOnly) {
                        out[n++] = Move.of(sq, t, Piece.ROOK, Move.CAPTURE)
                        out[n++] = Move.of(sq, t, Piece.BISHOP, Move.CAPTURE)
                        out[n++] = Move.of(sq, t, Piece.KNIGHT, Move.CAPTURE)
                    }
                } else {
                    out[n++] = Move.of(sq, t, 0, Move.CAPTURE)
                }
            } else if (q == 0 && t == epSquare) {
                out[n++] = Move.of(sq, t, 0, Move.CAPTURE or Move.EN_PASSANT)
            }
        }
        return n
    }

    private fun genCastles(us: Int, out: IntArray, start: Int): Int {
        var n = start
        val them = us xor 1
        if (us == WHITE) {
            if (castling and 1 != 0 && board[5] == 0 && board[6] == 0 && board[4] == Piece.WK && board[7] == Piece.WR &&
                !isAttacked(4, them) && !isAttacked(5, them) && !isAttacked(6, them)
            ) out[n++] = Move.of(4, 6, 0, Move.CASTLE)
            if (castling and 2 != 0 && board[3] == 0 && board[2] == 0 && board[1] == 0 && board[4] == Piece.WK && board[0] == Piece.WR &&
                !isAttacked(4, them) && !isAttacked(3, them) && !isAttacked(2, them)
            ) out[n++] = Move.of(4, 2, 0, Move.CASTLE)
        } else {
            if (castling and 4 != 0 && board[61] == 0 && board[62] == 0 && board[60] == Piece.BK && board[63] == Piece.BR &&
                !isAttacked(60, them) && !isAttacked(61, them) && !isAttacked(62, them)
            ) out[n++] = Move.of(60, 62, 0, Move.CASTLE)
            if (castling and 8 != 0 && board[59] == 0 && board[58] == 0 && board[57] == 0 && board[60] == Piece.BK && board[56] == Piece.BR &&
                !isAttacked(60, them) && !isAttacked(59, them) && !isAttacked(58, them)
            ) out[n++] = Move.of(60, 58, 0, Move.CASTLE)
        }
        return n
    }

    fun legalMoves(): IntArray {
        val buf = IntArray(256)
        val n = generate(buf)
        val out = IntArray(n)
        var k = 0
        for (i in 0 until n) {
            if (makeMove(buf[i])) {
                unmakeMove()
                out[k++] = buf[i]
            }
        }
        return out.copyOf(k)
    }

    fun hasLegalMove(): Boolean {
        val buf = IntArray(256)
        val n = generate(buf)
        for (i in 0 until n) {
            if (makeMove(buf[i])) {
                unmakeMove()
                return true
            }
        }
        return false
    }

    /** Finds the legal move matching from/to/promotion (flags are filled in), or [Move.NONE]. */
    fun findMove(from: Int, to: Int, promo: Int = 0): Int {
        for (m in legalMoves()) {
            if (Move.from(m) == from && Move.to(m) == to && Move.promo(m) == promo) return m
        }
        return Move.NONE
    }

    // ---------------------------------------------------------------- make / unmake

    private fun ensureStack() {
        if (sp >= uMove.size) {
            val n = uMove.size * 2
            uMove = uMove.copyOf(n)
            uCaptured = uCaptured.copyOf(n)
            uCastling = uCastling.copyOf(n)
            uEp = uEp.copyOf(n)
            uHalfmove = uHalfmove.copyOf(n)
            uHash = uHash.copyOf(n)
        }
        if (historyLen >= history.size) history = history.copyOf(history.size * 2)
    }

    /** Plays a pseudo-legal move. Returns false (and leaves the position unchanged) if it was illegal. */
    fun makeMove(m: Int): Boolean {
        makeRaw(m)
        if (isAttacked(kingSquare[side xor 1], side)) {
            unmakeMove()
            return false
        }
        return true
    }

    private fun makeRaw(m: Int) {
        ensureStack()
        val from = Move.from(m)
        val to = Move.to(m)
        val piece = board[from]
        val us = side
        uMove[sp] = m
        uCastling[sp] = castling
        uEp[sp] = epSquare
        uHalfmove[sp] = halfmoveClock
        uHash[sp] = hash
        history[historyLen++] = hash

        var h = hash
        if (epSquare >= 0) h = h xor Zobrist.EP_FILE[Square.file(epSquare)]
        h = h xor Zobrist.CASTLING[castling]

        var captured = 0
        if (m and Move.EN_PASSANT != 0) {
            val capSq = if (us == WHITE) to - 8 else to + 8
            captured = board[capSq]
            board[capSq] = 0
            h = h xor Zobrist.PIECE[captured][capSq]
        } else if (board[to] != 0) {
            captured = board[to]
            h = h xor Zobrist.PIECE[captured][to]
        }
        uCaptured[sp] = captured

        board[from] = 0
        h = h xor Zobrist.PIECE[piece][from]
        val promo = Move.promo(m)
        val placed = if (promo != 0) Piece.make(us, promo) else piece
        board[to] = placed
        h = h xor Zobrist.PIECE[placed][to]

        if (Piece.type(piece) == Piece.KING) {
            kingSquare[us] = to
            if (m and Move.CASTLE != 0) {
                val rFrom: Int
                val rTo: Int
                if (to > from) { rFrom = from + 3; rTo = from + 1 } else { rFrom = from - 4; rTo = from - 1 }
                val rook = board[rFrom]
                board[rFrom] = 0
                board[rTo] = rook
                h = h xor Zobrist.PIECE[rook][rFrom] xor Zobrist.PIECE[rook][rTo]
            }
        }

        castling = castling and CASTLE_MASK[from] and CASTLE_MASK[to]
        h = h xor Zobrist.CASTLING[castling]

        epSquare = -1
        if (m and Move.DOUBLE_PUSH != 0) {
            val epSq = (from + to) / 2
            if (enemyPawnBeside(to, us xor 1)) {
                epSquare = epSq
                h = h xor Zobrist.EP_FILE[Square.file(epSq)]
            }
        }

        halfmoveClock = if (Piece.type(piece) == Piece.PAWN || captured != 0) 0 else halfmoveClock + 1
        if (us == BLACK) fullmoveNumber++
        side = us xor 1
        h = h xor Zobrist.SIDE
        hash = h
        sp++
    }

    private fun enemyPawnBeside(sq: Int, enemy: Int): Boolean {
        val pawn = Piece.make(enemy, Piece.PAWN)
        val f = Square.file(sq)
        return (f > 0 && board[sq - 1] == pawn) || (f < 7 && board[sq + 1] == pawn)
    }

    fun unmakeMove() {
        sp--
        historyLen--
        val m = uMove[sp]
        val us = side xor 1
        if (m == NULL_MOVE) {
            side = us
            epSquare = uEp[sp]
            hash = uHash[sp]
            halfmoveClock = uHalfmove[sp]
            return
        }
        val from = Move.from(m)
        val to = Move.to(m)
        val placed = board[to]
        val piece = if (Move.promo(m) != 0) Piece.make(us, Piece.PAWN) else placed
        board[from] = piece
        if (m and Move.EN_PASSANT != 0) {
            board[to] = 0
            val capSq = if (us == WHITE) to - 8 else to + 8
            board[capSq] = uCaptured[sp]
        } else {
            board[to] = uCaptured[sp]
        }
        if (Piece.type(piece) == Piece.KING) {
            kingSquare[us] = from
            if (m and Move.CASTLE != 0) {
                val rFrom: Int
                val rTo: Int
                if (to > from) { rFrom = from + 3; rTo = from + 1 } else { rFrom = from - 4; rTo = from - 1 }
                board[rFrom] = board[rTo]
                board[rTo] = 0
            }
        }
        castling = uCastling[sp]
        epSquare = uEp[sp]
        halfmoveClock = uHalfmove[sp]
        hash = uHash[sp]
        if (us == BLACK) fullmoveNumber--
        side = us
    }

    fun makeNullMove() {
        ensureStack()
        uMove[sp] = NULL_MOVE
        uEp[sp] = epSquare
        uHash[sp] = hash
        uHalfmove[sp] = halfmoveClock
        history[historyLen++] = hash
        var h = hash
        if (epSquare >= 0) h = h xor Zobrist.EP_FILE[Square.file(epSquare)]
        epSquare = -1
        halfmoveClock++
        side = side xor 1
        hash = h xor Zobrist.SIDE
        sp++
    }

    fun unmakeNullMove() = unmakeMove()

    // ---------------------------------------------------------------- draws

    /** True if the current position occurred before since the last irreversible move. */
    fun isRepetition(): Boolean {
        var i = historyLen - 2
        val stop = maxOf(0, historyLen - halfmoveClock)
        while (i >= stop) {
            if (history[i] == hash) return true
            i -= 2
        }
        return false
    }

    /** Number of times the current position has appeared in the whole recorded history (including now). */
    fun repetitionCount(): Int {
        var count = 1
        var i = historyLen - 2
        val stop = maxOf(0, historyLen - halfmoveClock)
        while (i >= stop) {
            if (history[i] == hash) count++
            i -= 2
        }
        return count
    }

    fun isInsufficientMaterial(): Boolean {
        var minors = 0
        var lightBishops = 0
        var darkBishops = 0
        var knights = 0
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0) continue
            when (Piece.type(p)) {
                Piece.PAWN, Piece.ROOK, Piece.QUEEN -> return false
                Piece.KNIGHT -> { minors++; knights++ }
                Piece.BISHOP -> {
                    minors++
                    if (Square.isLight(sq)) lightBishops++ else darkBishops++
                }
            }
        }
        if (minors <= 1) return true
        return knights == 0 && (lightBishops == 0 || darkBishops == 0)
    }

    fun hasNonPawnMaterial(color: Int): Boolean {
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p != 0 && Piece.color(p) == color) {
                val t = Piece.type(p)
                if (t != Piece.PAWN && t != Piece.KING) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- notation

    fun toSan(m: Int): String {
        val sb = StringBuilder()
        val from = Move.from(m)
        val to = Move.to(m)
        val type = Piece.type(board[from])
        if (Move.isCastle(m)) {
            sb.append(if (to > from) "O-O" else "O-O-O")
        } else if (type == Piece.PAWN) {
            if (Move.isCapture(m)) sb.append('a' + Square.file(from)).append('x')
            sb.append(Square.name(to))
            if (Move.promo(m) != 0) sb.append('=').append(Piece.sanLetter(Move.promo(m)))
        } else {
            sb.append(Piece.sanLetter(type))
            var sameFile = false
            var sameRank = false
            var ambiguous = false
            for (o in legalMoves()) {
                if (o == m || Move.to(o) != to || Move.from(o) == from) continue
                if (Piece.type(board[Move.from(o)]) != type) continue
                ambiguous = true
                if (Square.file(Move.from(o)) == Square.file(from)) sameFile = true
                if (Square.rank(Move.from(o)) == Square.rank(from)) sameRank = true
            }
            if (ambiguous) {
                if (!sameFile) sb.append('a' + Square.file(from))
                else if (!sameRank) sb.append('1' + Square.rank(from))
                else sb.append(Square.name(from))
            }
            if (Move.isCapture(m)) sb.append('x')
            sb.append(Square.name(to))
        }
        if (makeMove(m)) {
            if (inCheck()) sb.append(if (hasLegalMove()) '+' else '#')
            unmakeMove()
        }
        return sb.toString()
    }

    /** Parses SAN ("Nbd7", "exd5", "O-O", "e8=Q+") or UCI ("e2e4"). Returns [Move.NONE] if not legal. */
    fun parseMove(text: String): Int {
        val clean = text.trim().replace("0-0-0", "O-O-O").replace("0-0", "O-O")
            .trimEnd('+', '#', '!', '?').replace("ep", "").replace("e.p.", "").trim()
        if (clean.isEmpty()) return Move.NONE
        val legal = legalMoves()
        if (clean.length in 4..5 && clean[0] in 'a'..'h' && clean[1] in '1'..'8' && clean[2] in 'a'..'h' && clean[3] in '1'..'8') {
            val promo = if (clean.length == 5) Piece.type(Piece.fromChar(clean[4])) else 0
            val from = Square.parse(clean.substring(0, 2))
            val to = Square.parse(clean.substring(2, 4))
            legal.firstOrNull { Move.from(it) == from && Move.to(it) == to && Move.promo(it) == promo }?.let { return it }
        }
        val norm = clean.replace("=", "")
        // Only moves landing on the square named in the text can match, which keeps parsing cheap.
        val candidates = if (norm.startsWith("O-O")) {
            legal.filter { Move.isCastle(it) }
        } else {
            legal.filter { norm.contains(Square.name(Move.to(it))) }
        }
        for (m in candidates) {
            val san = toSan(m).trimEnd('+', '#').replace("=", "")
            if (san == norm) return m
        }
        // Lenient: accept missing capture marks ("Nf3xe5" vs "Nxe5", "ed5" vs "exd5")
        val stripped = norm.replace("x", "")
        for (m in candidates) {
            val san = toSan(m).trimEnd('+', '#').replace("=", "").replace("x", "")
            if (san == stripped) return m
        }
        return Move.NONE
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[Square.of(f, r)]
                if (p == 0) empty++ else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(Piece.toChar(p))
                }
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (side == WHITE) " w " else " b ")
        val c = StringBuilder()
        if (castling and 1 != 0) c.append('K')
        if (castling and 2 != 0) c.append('Q')
        if (castling and 4 != 0) c.append('k')
        if (castling and 8 != 0) c.append('q')
        sb.append(if (c.isEmpty()) "-" else c)
        sb.append(' ').append(if (epSquare >= 0) Square.name(epSquare) else "-")
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    private fun computeHash(): Long {
        var h = 0L
        for (sq in 0 until 64) if (board[sq] != 0) h = h xor Zobrist.PIECE[board[sq]][sq]
        if (side == BLACK) h = h xor Zobrist.SIDE
        h = h xor Zobrist.CASTLING[castling]
        if (epSquare >= 0) h = h xor Zobrist.EP_FILE[Square.file(epSquare)]
        return h
    }

    override fun toString(): String = toFen()

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private const val NULL_MOVE = -1

        private val CASTLE_MASK = IntArray(64) { 15 }.also {
            it[0] = 15 and 2.inv()
            it[7] = 15 and 1.inv()
            it[4] = 15 and 3.inv()
            it[56] = 15 and 8.inv()
            it[63] = 15 and 4.inv()
            it[60] = 15 and 12.inv()
        }

        fun start(): Position = fromFen(START_FEN)

        /**
         * Parses a FEN string. Castling rights that don't match the king/rook placement and en-passant
         * squares without a capturing pawn are dropped, so the hash always reflects the real position.
         */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.isNotEmpty() && parts[0].isNotEmpty()) { "Empty FEN" }
            val p = Position()
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN must have 8 ranks" }
            for ((i, row) in rows.withIndex()) {
                val r = 7 - i
                var f = 0
                for (ch in row) {
                    if (ch.isDigit()) {
                        f += ch - '0'
                    } else {
                        val piece = Piece.fromChar(ch)
                        require(piece != 0) { "Bad piece '$ch'" }
                        require(f < 8) { "Rank ${r + 1} is too long" }
                        p.board[Square.of(f, r)] = piece
                        f++
                    }
                }
                require(f == 8) { "Rank ${r + 1} must have 8 squares" }
            }
            p.side = if (parts.getOrNull(1)?.lowercase() == "b") BLACK else WHITE
            val c = parts.getOrNull(2) ?: "-"
            var rights = 0
            if ('K' in c) rights = rights or 1
            if ('Q' in c) rights = rights or 2
            if ('k' in c) rights = rights or 4
            if ('q' in c) rights = rights or 8
            if (p.board[4] != Piece.WK) rights = rights and 3.inv()
            if (p.board[7] != Piece.WR) rights = rights and 1.inv()
            if (p.board[0] != Piece.WR) rights = rights and 2.inv()
            if (p.board[60] != Piece.BK) rights = rights and 12.inv()
            if (p.board[63] != Piece.BR) rights = rights and 4.inv()
            if (p.board[56] != Piece.BR) rights = rights and 8.inv()
            p.castling = rights
            val ep = parts.getOrNull(3) ?: "-"
            p.epSquare = -1
            if (ep != "-" && ep.length == 2) {
                val sq = runCatching { Square.parse(ep) }.getOrDefault(-1)
                if (sq >= 0) {
                    val pawnSq = if (p.side == WHITE) sq - 8 else sq + 8
                    val expectedRank = if (p.side == WHITE) 5 else 2
                    if (Square.rank(sq) == expectedRank && pawnSq in 0..63 &&
                        p.board[pawnSq] == Piece.make(p.side xor 1, Piece.PAWN) && p.enemyPawnBeside(pawnSq, p.side)
                    ) p.epSquare = sq
                }
            }
            p.halfmoveClock = parts.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            p.fullmoveNumber = parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            var wk = 0
            var bk = 0
            for (sq in 0 until 64) {
                if (p.board[sq] == Piece.WK) { p.kingSquare[WHITE] = sq; wk++ }
                if (p.board[sq] == Piece.BK) { p.kingSquare[BLACK] = sq; bk++ }
            }
            require(wk == 1 && bk == 1) { "Each side needs exactly one king" }
            p.hash = p.computeHash()
            return p
        }

        /** Human readable reason the FEN can't be played, or null if it is fine. */
        fun validate(fen: String): String? {
            val p = try {
                fromFen(fen)
            } catch (e: IllegalArgumentException) {
                return e.message ?: "Invalid FEN"
            }
            for (sq in 0 until 64) {
                val piece = p.board[sq]
                if (Piece.type(piece) == Piece.PAWN && (Square.rank(sq) == 0 || Square.rank(sq) == 7)) {
                    return "Pawns can't stand on the first or last rank"
                }
            }
            if (Attacks.distance(p.kingSquare[0], p.kingSquare[1]) <= 1) return "Kings can't be adjacent"
            if (p.inCheck(p.side xor 1)) return "The side not to move is in check"
            return null
        }
    }
}
