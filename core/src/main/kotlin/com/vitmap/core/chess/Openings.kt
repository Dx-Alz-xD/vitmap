package com.vitmap.core.chess

/**
 * A small named opening book. It names the opening being played, marks moves as "Book" in game
 * review and gives the bot some variety in the first moves.
 */
object Openings {
    private val LINES: List<Pair<String, String>> = listOf(
        "King's Pawn Opening" to "e4",
        "Queen's Pawn Opening" to "d4",
        "English Opening" to "c4",
        "Zukertort Opening" to "Nf3",
        "Bird's Opening" to "f4",
        "Nimzo-Larsen Attack" to "b3",
        "Polish Opening" to "b4",
        "Grob Opening" to "g4",
        "Hungarian Opening" to "g3",
        "Van 't Kruijs Opening" to "e3",
        "Sicilian Defense" to "e4 c5",
        "Sicilian Defense: Najdorf Variation" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6",
        "Sicilian Defense: Najdorf, English Attack" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6 Be3 e5 Nb3 Be6 f3",
        "Sicilian Defense: Dragon Variation" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6",
        "Sicilian Defense: Dragon, Yugoslav Attack" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6 Be3 Bg7 f3 O-O Qd2 Nc6",
        "Sicilian Defense: Classical Variation" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 Nc6",
        "Sicilian Defense: Scheveningen Variation" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 e6",
        "Sicilian Defense: Sveshnikov Variation" to "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 Nf6 Nc3 e5",
        "Sicilian Defense: Taimanov Variation" to "e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6",
        "Sicilian Defense: Kan Variation" to "e4 c5 Nf3 e6 d4 cxd4 Nxd4 a6",
        "Sicilian Defense: Accelerated Dragon" to "e4 c5 Nf3 Nc6 d4 cxd4 Nxd4 g6",
        "Sicilian Defense: Alapin Variation" to "e4 c5 c3",
        "Sicilian Defense: Closed Variation" to "e4 c5 Nc3",
        "Sicilian Defense: Smith-Morra Gambit" to "e4 c5 d4 cxd4 c3",
        "Sicilian Defense: Rossolimo Variation" to "e4 c5 Nf3 Nc6 Bb5",
        "Sicilian Defense: Moscow Variation" to "e4 c5 Nf3 d6 Bb5+",
        "French Defense" to "e4 e6",
        "French Defense: Advance Variation" to "e4 e6 d4 d5 e5",
        "French Defense: Exchange Variation" to "e4 e6 d4 d5 exd5 exd5",
        "French Defense: Winawer Variation" to "e4 e6 d4 d5 Nc3 Bb4",
        "French Defense: Classical Variation" to "e4 e6 d4 d5 Nc3 Nf6",
        "French Defense: Tarrasch Variation" to "e4 e6 d4 d5 Nd2",
        "French Defense: Rubinstein Variation" to "e4 e6 d4 d5 Nc3 dxe4",
        "Caro-Kann Defense" to "e4 c6",
        "Caro-Kann Defense: Advance Variation" to "e4 c6 d4 d5 e5",
        "Caro-Kann Defense: Classical Variation" to "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5",
        "Caro-Kann Defense: Exchange Variation" to "e4 c6 d4 d5 exd5 cxd5",
        "Caro-Kann Defense: Panov Attack" to "e4 c6 d4 d5 exd5 cxd5 c4",
        "Caro-Kann Defense: Two Knights Attack" to "e4 c6 Nc3 d5 Nf3",
        "Pirc Defense" to "e4 d6 d4 Nf6 Nc3 g6",
        "Modern Defense" to "e4 g6",
        "Alekhine's Defense" to "e4 Nf6",
        "Alekhine's Defense: Four Pawns Attack" to "e4 Nf6 e5 Nd5 d4 d6 c4 Nb6 f4",
        "Scandinavian Defense" to "e4 d5",
        "Scandinavian Defense: Main Line" to "e4 d5 exd5 Qxd5 Nc3 Qa5",
        "Scandinavian Defense: Modern Variation" to "e4 d5 exd5 Nf6",
        "Owen's Defense" to "e4 b6",
        "Nimzowitsch Defense" to "e4 Nc6",
        "Open Game" to "e4 e5",
        "King's Knight Opening" to "e4 e5 Nf3",
        "Philidor Defense" to "e4 e5 Nf3 d6",
        "Petrov's Defense" to "e4 e5 Nf3 Nf6",
        "Petrov's Defense: Classical Attack" to "e4 e5 Nf3 Nf6 Nxe5 d6 Nf3 Nxe4 d4",
        "Latvian Gambit" to "e4 e5 Nf3 f5",
        "Elephant Gambit" to "e4 e5 Nf3 d5",
        "Damiano Defense" to "e4 e5 Nf3 f6",
        "King's Knight Opening: Normal Variation" to "e4 e5 Nf3 Nc6",
        "Ruy Lopez" to "e4 e5 Nf3 Nc6 Bb5",
        "Ruy Lopez: Morphy Defense" to "e4 e5 Nf3 Nc6 Bb5 a6",
        "Ruy Lopez: Closed" to "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7",
        "Ruy Lopez: Closed, Main Line" to "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 d6 c3 O-O",
        "Ruy Lopez: Open Variation" to "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Nxe4",
        "Ruy Lopez: Exchange Variation" to "e4 e5 Nf3 Nc6 Bb5 a6 Bxc6",
        "Ruy Lopez: Berlin Defense" to "e4 e5 Nf3 Nc6 Bb5 Nf6",
        "Ruy Lopez: Berlin Defense, Berlin Wall" to "e4 e5 Nf3 Nc6 Bb5 Nf6 O-O Nxe4 d4 Nd6 Bxc6 dxc6 dxe5 Nf5 Qxd8+ Kxd8",
        "Ruy Lopez: Marshall Attack" to "e4 e5 Nf3 Nc6 Bb5 a6 Ba4 Nf6 O-O Be7 Re1 b5 Bb3 O-O c3 d5",
        "Ruy Lopez: Schliemann Defense" to "e4 e5 Nf3 Nc6 Bb5 f5",
        "Italian Game" to "e4 e5 Nf3 Nc6 Bc4",
        "Italian Game: Giuoco Piano" to "e4 e5 Nf3 Nc6 Bc4 Bc5",
        "Italian Game: Giuoco Pianissimo" to "e4 e5 Nf3 Nc6 Bc4 Bc5 d3",
        "Italian Game: Main Line" to "e4 e5 Nf3 Nc6 Bc4 Bc5 c3 Nf6 d4",
        "Italian Game: Evans Gambit" to "e4 e5 Nf3 Nc6 Bc4 Bc5 b4",
        "Italian Game: Two Knights Defense" to "e4 e5 Nf3 Nc6 Bc4 Nf6",
        "Italian Game: Fried Liver Attack" to "e4 e5 Nf3 Nc6 Bc4 Nf6 Ng5 d5 exd5 Nxd5 Nxf7",
        "Italian Game: Traxler Counterattack" to "e4 e5 Nf3 Nc6 Bc4 Nf6 Ng5 Bc5",
        "Hungarian Defense" to "e4 e5 Nf3 Nc6 Bc4 Be7",
        "Scotch Game" to "e4 e5 Nf3 Nc6 d4",
        "Scotch Game: Main Line" to "e4 e5 Nf3 Nc6 d4 exd4 Nxd4",
        "Scotch Gambit" to "e4 e5 Nf3 Nc6 d4 exd4 Bc4",
        "Three Knights Opening" to "e4 e5 Nf3 Nc6 Nc3",
        "Four Knights Game" to "e4 e5 Nf3 Nc6 Nc3 Nf6",
        "Four Knights Game: Spanish Variation" to "e4 e5 Nf3 Nc6 Nc3 Nf6 Bb5",
        "Ponziani Opening" to "e4 e5 Nf3 Nc6 c3",
        "Vienna Game" to "e4 e5 Nc3",
        "Vienna Gambit" to "e4 e5 Nc3 Nf6 f4",
        "King's Gambit" to "e4 e5 f4",
        "King's Gambit Accepted" to "e4 e5 f4 exf4",
        "King's Gambit Declined: Falkbeer Countergambit" to "e4 e5 f4 d5",
        "Bishop's Opening" to "e4 e5 Bc4",
        "Center Game" to "e4 e5 d4 exd4 Qxd4",
        "Danish Gambit" to "e4 e5 d4 exd4 c3",
        "Queen's Gambit" to "d4 d5 c4",
        "Queen's Gambit Declined" to "d4 d5 c4 e6",
        "Queen's Gambit Declined: Orthodox Defense" to "d4 d5 c4 e6 Nc3 Nf6 Bg5 Be7",
        "Queen's Gambit Declined: Exchange Variation" to "d4 d5 c4 e6 Nc3 Nf6 cxd5 exd5",
        "Queen's Gambit Declined: Ragozin Defense" to "d4 d5 c4 e6 Nc3 Nf6 Nf3 Bb4",
        "Queen's Gambit Accepted" to "d4 d5 c4 dxc4",
        "Slav Defense" to "d4 d5 c4 c6",
        "Slav Defense: Main Line" to "d4 d5 c4 c6 Nf3 Nf6 Nc3 dxc4",
        "Semi-Slav Defense" to "d4 d5 c4 c6 Nf3 Nf6 Nc3 e6",
        "Semi-Slav Defense: Meran Variation" to "d4 d5 c4 c6 Nf3 Nf6 Nc3 e6 e3 Nbd7 Bd3 dxc4 Bxc4 b5",
        "Albin Countergambit" to "d4 d5 c4 e5",
        "Chigorin Defense" to "d4 d5 c4 Nc6",
        "Tarrasch Defense" to "d4 d5 c4 e6 Nc3 c5",
        "London System" to "d4 d5 Bf4",
        "London System: Main Line" to "d4 d5 Nf3 Nf6 Bf4 e6 e3 c5 c3",
        "London System: Indian Setup" to "d4 Nf6 Bf4",
        "Colle System" to "d4 d5 Nf3 Nf6 e3",
        "Jobava London System" to "d4 d5 Nc3 Nf6 Bf4",
        "Richter-Veresov Attack" to "d4 d5 Nc3 Nf6 Bg5",
        "Blackmar-Diemer Gambit" to "d4 d5 e4",
        "Trompowsky Attack" to "d4 Nf6 Bg5",
        "Torre Attack" to "d4 Nf6 Nf3 e6 Bg5",
        "Indian Defense" to "d4 Nf6",
        "King's Indian Defense" to "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6",
        "King's Indian Defense: Classical Variation" to "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O Be2 e5",
        "King's Indian Defense: Sämisch Variation" to "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 f3",
        "King's Indian Defense: Four Pawns Attack" to "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 f4",
        "Grünfeld Defense" to "d4 Nf6 c4 g6 Nc3 d5",
        "Grünfeld Defense: Exchange Variation" to "d4 Nf6 c4 g6 Nc3 d5 cxd5 Nxd5 e4 Nxc3 bxc3",
        "Nimzo-Indian Defense" to "d4 Nf6 c4 e6 Nc3 Bb4",
        "Nimzo-Indian Defense: Rubinstein Variation" to "d4 Nf6 c4 e6 Nc3 Bb4 e3",
        "Nimzo-Indian Defense: Classical Variation" to "d4 Nf6 c4 e6 Nc3 Bb4 Qc2",
        "Queen's Indian Defense" to "d4 Nf6 c4 e6 Nf3 b6",
        "Bogo-Indian Defense" to "d4 Nf6 c4 e6 Nf3 Bb4+",
        "Catalan Opening" to "d4 Nf6 c4 e6 g3",
        "Catalan Opening: Open Defense" to "d4 Nf6 c4 e6 g3 d5 Bg2 dxc4",
        "Benoni Defense" to "d4 Nf6 c4 c5 d5",
        "Modern Benoni" to "d4 Nf6 c4 c5 d5 e6",
        "Benko Gambit" to "d4 Nf6 c4 c5 d5 b5",
        "Budapest Gambit" to "d4 Nf6 c4 e5",
        "Old Indian Defense" to "d4 Nf6 c4 d6",
        "Dutch Defense" to "d4 f5",
        "Dutch Defense: Leningrad Variation" to "d4 f5 c4 Nf6 g3 g6",
        "Dutch Defense: Stonewall Variation" to "d4 f5 c4 Nf6 g3 e6 Bg2 d5",
        "Dutch Defense: Staunton Gambit" to "d4 f5 e4",
        "Englund Gambit" to "d4 e5",
        "English Opening: Reversed Sicilian" to "c4 e5",
        "English Opening: Symmetrical Variation" to "c4 c5",
        "English Opening: Anglo-Indian Defense" to "c4 Nf6",
        "English Opening: Four Knights Variation" to "c4 e5 Nc3 Nf6 Nf3 Nc6",
        "English Opening: Botvinnik System" to "c4 e5 Nc3 Nc6 g3 g6 Bg2 Bg7 e4",
        "Réti Opening" to "Nf3 d5 c4",
        "King's Indian Attack" to "Nf3 d5 g3",
        "King's Indian Attack: Main Line" to "Nf3 Nf6 g3 g6 Bg2 Bg7 O-O O-O d3",
        "Bird's Opening: From's Gambit" to "f4 e5",
        "Bird's Opening: Dutch Variation" to "f4 d5",
    )

    private class Data(
        val names: Map<Long, String>,
        val bookMoves: Map<Long, List<String>>,
    )

    private val data: Data by lazy { build() }

    /** Lines that failed to parse; exposed for tests. */
    internal fun brokenLines(): List<String> = LINES.filter { (_, line) ->
        val pos = Position.start()
        line.split(' ').any { token ->
            val m = pos.parseMove(token)
            if (m == Move.NONE) true else { pos.makeMove(m); false }
        }
    }.map { it.first }

    private fun build(): Data {
        val names = HashMap<Long, String>()
        val moves = HashMap<Long, LinkedHashSet<String>>()
        for ((name, line) in LINES) {
            val pos = Position.start()
            var complete = true
            for (token in line.split(' ')) {
                val m = pos.parseMove(token)
                if (m == Move.NONE) { complete = false; break }
                moves.getOrPut(pos.hash) { LinkedHashSet() }.add(Move.uci(m))
                pos.makeMove(m)
            }
            // Longer lines are listed after their parents, so the most specific name wins.
            if (complete) names[pos.hash] = name
        }
        return Data(names, moves.mapValues { it.value.toList() })
    }

    /** Warm the book off the main thread. */
    fun preload() {
        data.names.size
    }

    fun nameOf(hash: Long): String? = data.names[hash]

    /** Name of the deepest known opening along a sequence of position hashes. */
    fun nameAlong(hashes: List<Long>): String? {
        var found: String? = null
        for (h in hashes) data.names[h]?.let { found = it }
        return found
    }

    fun bookMoves(pos: Position): List<Int> {
        val ucis = data.bookMoves[pos.hash] ?: return emptyList()
        val legal = pos.legalMoves()
        return ucis.mapNotNull { u -> legal.firstOrNull { Move.uci(it) == u } }
    }

    fun isBookPosition(hash: Long): Boolean = data.names.containsKey(hash) || data.bookMoves.containsKey(hash)

    /** True if playing [move] from [pos] stays inside the book. */
    fun isBookMove(pos: Position, move: Int): Boolean {
        val ucis = data.bookMoves[pos.hash] ?: return false
        return Move.uci(move) in ucis
    }
}
