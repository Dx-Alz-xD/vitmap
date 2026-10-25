package com.vitmap.core.chess

enum class GameStatus(val description: String, val isOver: Boolean) {
    ONGOING("In progress", false),
    CHECKMATE("Checkmate", true),
    STALEMATE("Stalemate", true),
    FIFTY_MOVES("Draw by 50-move rule", true),
    THREEFOLD("Draw by repetition", true),
    INSUFFICIENT("Draw by insufficient material", true),
}

data class Ply(
    val move: Int,
    val uci: String,
    val san: String,
    val color: Int,
    val fenAfter: String,
    val hashAfter: Long,
    val moveNumber: Int,
)

/** Immutable record of a game: start position plus the plies played from it. */
class GameRecord private constructor(
    val startFen: String,
    val startHash: Long,
    val plies: List<Ply>,
) {
    val size: Int get() = plies.size

    fun fenAt(index: Int): String = if (index == 0) startFen else plies[index - 1].fenAfter

    /** Position after [index] plies, carrying the history needed for repetition detection. */
    fun positionAt(index: Int = plies.size): Position {
        val pos = Position.fromFen(fenAt(index))
        val hashes = LongArray(index)
        if (index > 0) hashes[0] = startHash
        for (i in 1 until index) hashes[i] = plies[i - 1].hashAfter
        pos.setHistory(hashes)
        return pos
    }

    fun hashesUpTo(index: Int): List<Long> = buildList {
        add(startHash)
        for (i in 0 until index) add(plies[i].hashAfter)
    }

    /** Plays [move] after the first [atIndex] plies, discarding any later plies. */
    fun play(move: Int, atIndex: Int = plies.size): GameRecord {
        val pos = positionAt(atIndex)
        val legal = pos.legalMoves().firstOrNull { Move.key(it) == Move.key(move) }
        requireNotNull(legal) { "Illegal move ${Move.uci(move)}" }
        val san = pos.toSan(legal)
        val color = pos.side
        val number = pos.fullmoveNumber
        pos.makeMove(legal)
        val ply = Ply(legal, Move.uci(legal), san, color, pos.toFen(), pos.hash, number)
        return GameRecord(startFen, startHash, plies.subList(0, atIndex) + ply)
    }

    fun truncate(index: Int): GameRecord = GameRecord(startFen, startHash, plies.subList(0, index))

    fun status(index: Int = plies.size): GameStatus {
        val pos = positionAt(index)
        if (!pos.hasLegalMove()) return if (pos.inCheck()) GameStatus.CHECKMATE else GameStatus.STALEMATE
        if (pos.isInsufficientMaterial()) return GameStatus.INSUFFICIENT
        if (pos.halfmoveClock >= 100) return GameStatus.FIFTY_MOVES
        if (pos.repetitionCount() >= 3) return GameStatus.THREEFOLD
        return GameStatus.ONGOING
    }

    companion object {
        fun fromFen(fen: String = Position.START_FEN): GameRecord {
            val pos = Position.fromFen(fen)
            return GameRecord(pos.toFen(), pos.hash, emptyList())
        }

        fun fromUci(fen: String, moves: List<String>): GameRecord {
            var g = fromFen(fen)
            for (u in moves) {
                val m = g.positionAt().parseMove(u)
                if (m == Move.NONE) break
                g = g.play(m)
            }
            return g
        }
    }
}

data class PgnGame(
    val headers: Map<String, String>,
    val record: GameRecord,
    /** Tokens that couldn't be played, if the PGN contained an illegal or unreadable move. */
    val error: String? = null,
)

object Pgn {
    fun export(
        record: GameRecord,
        headers: Map<String, String> = emptyMap(),
        result: String = "*",
        annotations: List<MoveQuality?> = emptyList(),
    ): String {
        val sb = StringBuilder()
        val all = LinkedHashMap<String, String>()
        all["Event"] = "Casual game"
        all["Site"] = "Vitmap"
        all["Date"] = "????.??.??"
        all["Round"] = "-"
        all["White"] = "White"
        all["Black"] = "Black"
        all.putAll(headers)
        all["Result"] = result
        if (record.startFen != Position.START_FEN) {
            all["SetUp"] = "1"
            all["FEN"] = record.startFen
        }
        for ((k, v) in all) sb.append('[').append(k).append(" \"").append(v.replace("\"", "'")).append("\"]\n")
        sb.append('\n')
        val line = StringBuilder()
        record.plies.forEachIndexed { i, ply ->
            if (ply.color == Piece.WHITE) line.append(ply.moveNumber).append(". ")
            else if (i == 0) line.append(ply.moveNumber).append("... ")
            line.append(ply.san)
            val q = annotations.getOrNull(i)
            if (q != null) {
                line.append(q.nag)
                if (q != MoveQuality.BEST && q != MoveQuality.BOOK && q != MoveQuality.FORCED) line.append(" {").append(q.label).append('}')
            }
            line.append(' ')
        }
        line.append(result)
        // Wrap at 80 columns like most PGN writers.
        var col = 0
        for (word in line.split(' ')) {
            if (col + word.length + 1 > 80) {
                sb.append('\n')
                col = 0
            } else if (col > 0) {
                sb.append(' ')
                col++
            }
            sb.append(word)
            col += word.length
        }
        sb.append('\n')
        return sb.toString()
    }

    fun parse(text: String): PgnGame {
        val headers = LinkedHashMap<String, String>()
        val headerRegex = Regex("""\[\s*(\w+)\s+"((?:[^"\\]|\\.)*)"\s*]""")
        val body = StringBuilder()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                headerRegex.find(line)?.let { headers[it.groupValues[1]] = it.groupValues[2] }
            } else if (!line.startsWith("%")) {
                body.append(line.substringBefore(';')).append(' ')
            }
        }
        // Strip comments and (nested) variations.
        val cleaned = StringBuilder()
        var depth = 0
        var inComment = false
        for (c in body) {
            when {
                inComment -> if (c == '}') inComment = false
                c == '{' -> inComment = true
                c == '(' -> depth++
                c == ')' -> depth = maxOf(0, depth - 1)
                depth == 0 -> cleaned.append(c)
            }
        }
        val fen = headers["FEN"] ?: Position.START_FEN
        var record = runCatching { GameRecord.fromFen(fen) }.getOrElse { GameRecord.fromFen() }
        var error: String? = null
        val tokens = cleaned.toString().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (raw in tokens) {
            var t = raw.replace(Regex("^\\d+\\.+"), "")
            if (t.isEmpty() || t.startsWith("$")) continue
            if (t == "1-0" || t == "0-1" || t == "1/2-1/2" || t == "*") break
            t = t.trimEnd('!', '?')
            val m = record.positionAt().parseMove(t)
            if (m == Move.NONE) {
                error = "Couldn't play \"$raw\" after ${record.size} plies"
                break
            }
            record = record.play(m)
        }
        return PgnGame(headers, record, error)
    }
}
