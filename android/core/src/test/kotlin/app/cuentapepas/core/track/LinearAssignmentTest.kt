package app.cuentapepas.core.track

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearAssignmentTest {

    @Test
    fun diagonalMatrixMatchesDiagonal() {
        val r = LinearAssignment.solve(arrayOf(doubleArrayOf(0.1, 0.9), doubleArrayOf(0.9, 0.1)), 0.8)
        assertEquals(listOf(0 to 0, 1 to 1), r.matches)
        assertTrue(r.unmatchedRows.isEmpty() && r.unmatchedCols.isEmpty())
    }

    @Test
    fun oneRowThreeColumnsLeavesTwoColumnsUnassigned() {
        val r = LinearAssignment.solve(arrayOf(doubleArrayOf(0.1, 0.2, 0.9)), 0.8)
        assertEquals(listOf(0 to 0), r.matches)
        assertEquals(listOf(1, 2), r.unmatchedCols)
        assertTrue(r.unmatchedRows.isEmpty())
    }

    @Test
    fun costAboveThresholdIsNotMatched() {
        val r = LinearAssignment.solve(arrayOf(doubleArrayOf(0.6)), 0.5)
        assertTrue(r.matches.isEmpty())
        assertEquals(listOf(0), r.unmatchedRows)
        assertEquals(listOf(0), r.unmatchedCols)
    }

    @Test
    fun emptyInputsReturnEverythingUnassigned() {
        val noRows = LinearAssignment.solve(emptyArray(), 0.5, nCols = 3)
        assertEquals(listOf(0, 1, 2), noRows.unmatchedCols)
        assertTrue(noRows.matches.isEmpty() && noRows.unmatchedRows.isEmpty())
        val noCols = LinearAssignment.solve(Array(2) { DoubleArray(0) }, 0.5)
        assertEquals(listOf(0, 1), noCols.unmatchedRows)
        assertTrue(noCols.matches.isEmpty() && noCols.unmatchedCols.isEmpty())
    }

    @Test
    fun conflictingPreferencesGetGlobalOptimum() {
        // Greedy would take (0,0)=0.1 and leave row 1 with 0.9; the optimum is 0.2 + 0.2.
        val r = LinearAssignment.solve(arrayOf(doubleArrayOf(0.1, 0.2), doubleArrayOf(0.2, 0.9)), 1.0)
        assertEquals(listOf(0 to 1, 1 to 0), r.matches)
    }

    @Test
    fun random6x6MatchesBruteForceMinimum() {
        val rnd = Random(42)
        repeat(50) {
            val cost = Array(6) { DoubleArray(6) { rnd.nextDouble() } }
            val r = LinearAssignment.solve(cost, 10.0)
            assertEquals(6, r.matches.size)
            val got = r.matches.sumOf { (i, j) -> cost[i][j] }
            assertEquals(bruteForceMin(cost), got, 1e-12)
        }
    }

    @Test
    fun randomRectangularMatchesBruteForceMinimum() {
        val rnd = Random(7)
        repeat(30) {
            val cost = Array(4) { DoubleArray(6) { rnd.nextDouble() } }
            val r = LinearAssignment.solve(cost, 10.0)
            assertEquals(4, r.matches.size)
            assertEquals(bruteForceMin(cost), r.matches.sumOf { (i, j) -> cost[i][j] }, 1e-12)
        }
    }

    @Test
    fun matchesAreOneToOneAndAdmissibleUnderThreshold() {
        val rnd = Random(3)
        val cost = Array(40) { DoubleArray(55) { if (rnd.nextInt(5) == 0) rnd.nextDouble() else 1.0 } }
        val r = LinearAssignment.solve(cost, 0.8)
        assertEquals(r.matches.size, r.matches.map { it.first }.toSet().size)
        assertEquals(r.matches.size, r.matches.map { it.second }.toSet().size)
        assertTrue(r.matches.all { (i, j) -> cost[i][j] <= 0.8 })
        assertEquals(40, r.matches.size + r.unmatchedRows.size)
        assertEquals(55, r.matches.size + r.unmatchedCols.size)
    }

    @Test
    fun sparseMatricesMatchBruteForceObjective() {
        val thr = 0.5
        for (seed in longArrayOf(1, 2, 3, 11, 42, 99)) {
            val rnd = Random(seed)
            repeat(40) {
                val rows = 4 + rnd.nextInt(3)
                val cols = 4 + rnd.nextInt(3)
                // About 70% of the pairs are forbidden (cost above the threshold).
                val cost = Array(rows) {
                    DoubleArray(cols) { if (rnd.nextInt(10) < 7) thr + 1e-3 + rnd.nextDouble() * 0.5 else rnd.nextDouble() * thr }
                }
                val r = LinearAssignment.solve(cost, thr)
                assertTrue(r.matches.all { (i, j) -> cost[i][j] <= thr })
                assertEquals(r.matches.size, r.matches.map { it.first }.toSet().size)
                assertEquals(r.matches.size, r.matches.map { it.second }.toSet().size)
                val got = r.matches.sumOf { (i, j) -> thr + 1e-4 - cost[i][j] }
                assertEquals("seed $seed", bruteForceGain(cost, thr), got, 1e-9)
            }
        }
    }

    @Test
    fun denseSquareWithRealThresholdMatchesBruteForceObjective() {
        val rnd = Random(5)
        repeat(30) {
            val cost = Array(6) { DoubleArray(6) { rnd.nextDouble() } }
            val r = LinearAssignment.solve(cost, 0.5)
            assertEquals(bruteForceGain(cost, 0.5), r.matches.sumOf { (i, j) -> 0.5 + 1e-4 - cost[i][j] }, 1e-9)
        }
    }

    /** Max of sum(threshold + 1e-4 - c) over admissible pairs, with any rows left unmatched. */
    private fun bruteForceGain(cost: Array<DoubleArray>, thr: Double): Double {
        val rows = cost.size
        val cols = cost[0].size
        var best = 0.0
        fun rec(row: Int, used: Int, acc: Double) {
            if (row == rows) { if (acc > best) best = acc; return }
            rec(row + 1, used, acc)
            for (c in 0 until cols) if (used and (1 shl c) == 0 && cost[row][c] <= thr) {
                rec(row + 1, used or (1 shl c), acc + thr + 1e-4 - cost[row][c])
            }
        }
        rec(0, 0, 0.0)
        return best
    }

    /** Minimum total cost assigning every row of a rows <= cols matrix to a distinct column. */
    private fun bruteForceMin(cost: Array<DoubleArray>): Double {
        val rows = cost.size
        val cols = cost[0].size
        var best = Double.MAX_VALUE
        fun rec(row: Int, used: Int, acc: Double) {
            if (acc >= best) return
            if (row == rows) { best = acc; return }
            for (c in 0 until cols) if (used and (1 shl c) == 0) rec(row + 1, used or (1 shl c), acc + cost[row][c])
        }
        rec(0, 0, 0.0)
        return best
    }
}
