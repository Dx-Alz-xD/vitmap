package com.vitmap.core.tictactoe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicTacToeTest {
    private fun board(size: Int, k: Int, vararg moves: Pair<Int, Int>): Board {
        var b = Board(size, k)
        for ((i, p) in moves) b = b.play(i, p)
        return b
    }

    @Test
    fun detectsWins() {
        val b = board(3, 3, 0 to Player.X, 4 to Player.X, 8 to Player.X)
        val w = b.winAt(8)
        assertNotNull(w)
        assertEquals(listOf(0, 4, 8), w.cells)
        assertNull(board(3, 3, 0 to Player.X, 1 to Player.X).winner())
        val anti = board(5, 4, 3 to Player.O, 7 to Player.O, 11 to Player.O, 15 to Player.O)
        assertEquals(Player.O, anti.winner()?.player)
    }

    @Test
    fun hardBotNeverLosesOnThreeByThree() {
        val ai = TicTacToeAi(Random(1))
        val rnd = Random(42)
        repeat(60) {
            var b = Board(3, 3)
            var turn = if (it % 2 == 0) Player.X else Player.O
            while (b.winner() == null && !b.isFull) {
                val m = if (turn == Player.X) {
                    (0 until 9).filter { b[it] == Player.NONE }.random(rnd)
                } else ai.chooseMove(b, Player.O, Difficulty.HARD)
                b = b.play(m, turn)
                turn = Player.other(turn)
            }
            assertTrue(b.winner()?.player != Player.X, "random player beat the hard bot")
        }
    }

    @Test
    fun bigBoardBotBlocksAndWins() {
        val ai = TicTacToeAi(Random(3))
        // X threatens four in a row on a 9x9 board with k=5; O must block
        var b = Board(9, 5)
        for (c in 2..5) b = b.play(4 * 9 + c, Player.X)
        b = b.play(0, Player.O).play(80, Player.O)
        val m = ai.chooseMove(b, Player.O, Difficulty.MEDIUM)
        assertTrue(m == 4 * 9 + 1 || m == 4 * 9 + 6, "should block, played $m")
        // O can win immediately
        var w = Board(9, 5)
        for (c in 0..3) w = w.play(c, Player.O)
        w = w.play(40, Player.X).play(41, Player.X)
        assertEquals(4, ai.chooseMove(w, Player.O, Difficulty.HARD))
    }
}
