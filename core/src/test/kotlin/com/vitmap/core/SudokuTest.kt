package com.vitmap.core

import com.vitmap.core.sudoku.Difficulty
import com.vitmap.core.sudoku.Generator
import com.vitmap.core.sudoku.Geometry
import com.vitmap.core.sudoku.LogicSolver
import com.vitmap.core.sudoku.Solver
import com.vitmap.core.sudoku.Technique
import com.vitmap.core.sudoku.Variant
import com.vitmap.core.sudoku.bitOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SudokuTest {
    private fun parse(s: String) = s.filter { it.isDigit() || it == '.' }.map { if (it == '.') 0 else it - '0' }.toIntArray()

    private val easy = parse("530070000600195000098000060800060003400803001700020006060000280000419005000080079")
    private val easySolution = parse("534678912672195348198342567859761423426853791713924856961537284287419635345286179")

    private fun valid(g: Geometry, grid: IntArray) = g.units.all { u -> u.map { grid[it] }.toSet() == (1..g.n).toSet() }

    @Test
    fun solvesClassicPuzzle() {
        val g = Geometry(9)
        val sol = assertNotNull(Solver(g).solve(easy))
        assertContentEquals(easySolution, sol)
        assertTrue(Solver(g).isUnique(easy))
    }

    @Test
    fun hardPuzzleNeedsSearchButSolves() {
        val g = Geometry(9)
        // "World's hardest sudoku" (Arto Inkala).
        val p = parse("800000000003600000070090200050007000000045700000100030001000068008500010090000400")
        val sol = assertNotNull(Solver(g).solve(p))
        assertTrue(valid(g, sol))
        assertEquals(Technique.GUESS, LogicSolver(g).grade(p).hardest)
    }

    @Test
    fun gradesEasyPuzzleAsSinglesOnly() {
        val grade = LogicSolver(Geometry(9)).grade(easy)
        assertTrue(grade.solvedLogically)
        assertTrue(grade.hardest.level <= 1, "hardest ${grade.hardest}")
    }

    @Test
    fun generatesUniquePuzzlesAtEachLevel() {
        val g = Geometry(9)
        val rnd = Random(42)
        for (d in listOf(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD)) {
            val p = Generator.generate(g, d, rnd)
            assertTrue(valid(g, p.solution))
            assertTrue(Solver(g).isUnique(p.givens), "not unique for $d")
            for (i in p.givens.indices) if (p.givens[i] != 0) assertEquals(p.solution[i], p.givens[i])
            assertTrue(p.givens.count { it != 0 } in 17..50)
        }
    }

    @Test
    fun logicStepsAreSound() {
        val g = Geometry(9)
        val logic = LogicSolver(g)
        val rnd = Random(7)
        repeat(6) {
            val p = Generator.generate(g, Difficulty.HARD, rnd, attempts = 3)
            val grid = p.givens.copyOf()
            val cand = logic.candidates(grid)
            while (true) {
                val step = logic.nextStep(grid, cand) ?: break
                if (step.isPlacement) assertEquals(p.solution[step.cell], step.digit, "bad placement by ${step.technique}")
                for ((c, d) in step.eliminations) assertTrue(p.solution[c] != d, "${step.technique} removed the answer at $c")
                logic.apply(grid, cand, step)
                for (i in grid.indices) assertTrue(cand[i] and bitOf(p.solution[i]) != 0)
            }
        }
    }

    @Test
    fun diagonalAndSmallBoards() {
        val diag = Geometry(9, Variant.DIAGONAL)
        val p = Generator.generate(diag, Difficulty.EASY, Random(3), attempts = 4)
        assertTrue(valid(diag, p.solution))
        assertTrue(Solver(diag).isUnique(p.givens))
        for (n in listOf(4, 6)) {
            val g = Geometry(n)
            val q = Generator.generate(g, Difficulty.MEDIUM, Random(n), attempts = 4)
            assertTrue(valid(g, q.solution))
            assertTrue(Solver(g).isUnique(q.givens))
        }
    }

    @Test
    fun sixteenBySixteenGenerates() {
        val g = Geometry(16)
        val t = System.currentTimeMillis()
        val p = Generator.generate(g, Difficulty.EASY, Random(5))
        assertTrue(valid(g, p.solution))
        assertTrue(Solver(g).isUnique(p.givens))
        println("16x16 easy in ${System.currentTimeMillis() - t} ms with ${p.givens.count { it != 0 }} clues")
    }
}
