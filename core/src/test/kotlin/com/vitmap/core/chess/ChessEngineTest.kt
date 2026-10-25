package com.vitmap.core.chess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessEngineTest {
    private fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        val buf = IntArray(256)
        val n = pos.generate(buf)
        var total = 0L
        for (i in 0 until n) {
            if (!pos.makeMove(buf[i])) continue
            total += perft(pos, depth - 1)
            pos.unmakeMove()
        }
        return total
    }

    @Test
    fun perftStartPosition() {
        val pos = Position.start()
        assertEquals(20, perft(pos, 1))
        assertEquals(400, perft(pos, 2))
        assertEquals(8902, perft(pos, 3))
        assertEquals(197281, perft(pos, 4))
    }

    @Test
    fun perftKiwipete() {
        val pos = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48, perft(pos, 1))
        assertEquals(2039, perft(pos, 2))
        assertEquals(97862, perft(pos, 3))
    }

    @Test
    fun perftPosition3() {
        val pos = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14, perft(pos, 1))
        assertEquals(191, perft(pos, 2))
        assertEquals(2812, perft(pos, 3))
        assertEquals(43238, perft(pos, 4))
    }

    @Test
    fun perftPosition4() {
        val pos = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6, perft(pos, 1))
        assertEquals(264, perft(pos, 2))
        assertEquals(9467, perft(pos, 3))
    }

    @Test
    fun perftPosition5() {
        val pos = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44, perft(pos, 1))
        assertEquals(1486, perft(pos, 2))
        assertEquals(62379, perft(pos, 3))
    }

    @Test
    fun makeUnmakeRestoresHashAndFen() {
        val pos = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        val fen = pos.toFen()
        val hash = pos.hash
        for (m in pos.legalMoves()) {
            assertTrue(pos.makeMove(m))
            assertEquals(Position.fromFen(pos.toFen()).hash, pos.hash, "incremental hash after ${Move.uci(m)}")
            pos.unmakeMove()
            assertEquals(fen, pos.toFen())
            assertEquals(hash, pos.hash)
        }
    }

    @Test
    fun fenRoundTrip() {
        val fens = listOf(
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
        )
        for (f in fens) assertEquals(f, Position.fromFen(f).toFen())
    }

    @Test
    fun sanGeneration() {
        val pos = Position.start()
        assertEquals("e4", pos.toSan(pos.parseMove("e2e4")))
        assertEquals("Nf3", pos.toSan(pos.parseMove("g1f3")))
        val p2 = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals("O-O", p2.toSan(p2.parseMove("e1g1")))
        assertEquals("O-O-O", p2.toSan(p2.parseMove("e1c1")))
        val p3 = Position.fromFen("4k3/8/8/8/8/8/8/R4RK1 w - - 0 1")
        assertEquals("Rad1", p3.toSan(p3.parseMove("a1d1")))
        val p4 = Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals("a8=Q+", p4.toSan(p4.parseMove("a7a8q")))
        val mate = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("Ra8#", mate.toSan(mate.parseMove("Ra8")))
    }

    @Test
    fun parsesSan() {
        val pos = Position.start()
        for (san in listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O")) {
            val m = pos.parseMove(san)
            assertNotEquals(Move.NONE, m, san)
            pos.makeMove(m)
        }
        assertEquals("r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQ1RK1 b kq - 3 5", pos.toFen())
    }

    @Test
    fun findsMateInOne() {
        val pos = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        val info = Search(16).search(pos, SearchLimits(maxDepth = 4))
        assertEquals("a1a8", Move.uci(info.best!!.bestMove))
        assertTrue(Search.isMate(info.best!!.score))
        assertEquals(1, Search.mateIn(info.best!!.score))
    }

    @Test
    fun findsMateInTwo() {
        // Classic back-rank: 1. Qd8+ Rxd8 2. Rxd8#
        val pos = Position.fromFen("1r4k1/5ppp/8/8/8/8/3Q1PPP/3R2K1 w - - 0 1")
        val info = Search(16).search(pos, SearchLimits(maxDepth = 6))
        assertTrue(Search.isMate(info.best!!.score), "score=${info.best!!.score}")
        assertEquals(2, Search.mateIn(info.best!!.score))
    }

    @Test
    fun winsHangingQueen() {
        val pos = Position.fromFen("rnb1kbnr/pppp1ppp/8/4p1q1/4P3/3P4/PPP2PPP/RNBQKBNR w KQkq - 1 3")
        val info = Search(16).search(pos, SearchLimits(maxDepth = 4))
        assertEquals("c1g5", Move.uci(info.best!!.bestMove))
    }

    @Test
    fun multiPvReturnsDistinctLines() {
        val info = Search(16).search(Position.start(), SearchLimits(maxDepth = 4, multiPv = 3))
        assertEquals(3, info.lines.size)
        assertEquals(3, info.lines.map { it.bestMove }.toSet().size)
    }

    @Test
    fun detectsGameEndings() {
        val mate = GameRecord.fromFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3")
        assertEquals(GameStatus.CHECKMATE, mate.status())
        val stalemate = GameRecord.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(GameStatus.STALEMATE, stalemate.status())
        val bare = GameRecord.fromFen("8/8/4k3/8/8/3K4/8/8 w - - 0 1")
        assertEquals(GameStatus.INSUFFICIENT, bare.status())
        var rep = GameRecord.fromFen()
        repeat(2) {
            for (u in listOf("g1f3", "g8f6", "f3g1", "f6g8")) rep = rep.play(rep.positionAt().parseMove(u))
        }
        assertEquals(GameStatus.THREEFOLD, rep.status())
    }

    @Test
    fun staticExchange() {
        // Pawn takes undefended knight
        val p = Position.fromFen("4k3/8/8/3n4/4P3/8/8/4K3 w - - 0 1")
        assertEquals(320, p.see(Square.parse("d5"), Piece.WHITE))
        // Knight defended by pawn: pawn x knight, pawn x pawn
        val q = Position.fromFen("4k3/8/2p5/3n4/4P3/8/8/4K3 w - - 0 1")
        assertEquals(220, q.see(Square.parse("d5"), Piece.WHITE))
        // King can't capture a defended piece
        val r = Position.fromFen("8/8/8/2p5/3n4/4K3/8/7k w - - 0 1")
        assertEquals(0, r.see(Square.parse("d4"), Piece.WHITE))
    }

    @Test
    fun openingBookParsesEveryLine() {
        assertEquals(emptyList(), Openings.brokenLines())
        val g = GameRecord.fromUci(Position.START_FEN, listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6"))
        assertEquals("Sicilian Defense: Najdorf Variation", Openings.nameAlong(g.hashesUpTo(g.size)))
    }

    @Test
    fun pgnRoundTrip() {
        var g = GameRecord.fromFen()
        for (u in listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5c6", "d7c6", "e1g1")) {
            g = g.play(g.positionAt().parseMove(u))
        }
        val text = Pgn.export(g, mapOf("White" to "Me", "Black" to "Bot"))
        val parsed = Pgn.parse(text)
        assertNull(parsed.error)
        assertEquals(g.plies.map { it.uci }, parsed.record.plies.map { it.uci })
        assertEquals("Me", parsed.headers["White"])
        val withComments = Pgn.parse("1. e4 {best} e5 (1... c5 2. Nf3) 2. Nf3! Nc6?! 3. Bb5 1-0")
        assertNull(withComments.error)
        assertEquals(5, withComments.record.size)
    }

    @Test
    fun validatesFen() {
        assertNull(Position.validate(Position.START_FEN))
        assertTrue(Position.validate("8/8/8/8/8/8/8/8 w - - 0 1") != null)
        assertTrue(Position.validate("4k3/8/8/8/8/8/8/4K2P w - - 0 1") != null)
        assertTrue(Position.validate("4k3/8/8/8/8/8/8/R3K3 b - - 0 1") == null)
        assertTrue(Position.validate("R3k3/8/8/8/8/8/8/4K3 w - - 0 1") != null) // black in check, white to move
    }

    @Test
    fun classifiesObviousBlunder() {
        val before = Position.fromFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2")
        val search = Search(16)
        val eval = search.search(before, SearchLimits(maxDepth = 5, multiPv = 2))
        val pe = PositionEval(eval.lines[0].score, eval.lines[0].bestMove, eval.lines.getOrNull(1)?.score, eval.depth)
        // 2... Qh4?? hangs the queen to Nxh4
        val blunder = before.parseMove("Qh4")
        val after = before.copy().also { it.makeMove(blunder) }
        val afterScore = -search.search(after, SearchLimits(maxDepth = 5)).best!!.score
        assertEquals(MoveQuality.BLUNDER, MoveClassifier.classify(before, blunder, pe, afterScore))
    }

    @Test
    fun botPlaysLegalMovesAtEveryLevel() {
        val bot = ChessBot(Search(14), kotlin.random.Random(7))
        for (elo in listOf(200, 800, 1500, 2100)) {
            val pos = Position.start()
            repeat(6) {
                if (!pos.hasLegalMove()) return@repeat
                val d = bot.chooseMove(pos, elo)
                assertTrue(pos.legalMoves().contains(d.move))
                pos.makeMove(d.move)
            }
        }
    }
}
