package app.cuentapepas.core.track

/** Result of [LinearAssignment.solve]: matched (row, col) pairs plus the rows and columns left over. */
data class Assignment(
    val matches: List<Pair<Int, Int>>,
    val unmatchedRows: List<Int>,
    val unmatchedCols: List<Int>,
)

/** Rectangular assignment with a cost cut-off, built on the Hungarian (Kuhn-Munkres) algorithm. */
object LinearAssignment {

    /**
     * Minimum-cost matching of rows to columns, dropping every pair whose cost is above [threshold].
     *
     * The cost matrix is padded to a square with `threshold + 1e-4`, which is also the cost given to
     * any forbidden pair (cost > threshold), so the result maximises the number of admissible pairs
     * before minimising their total cost (same semantics as `lap.lapjv(extend_cost, cost_limit)` in
     * ByteTrack). The problem is split into connected components of the admissible pairs (exact),
     * which keeps the typical sparse IoU matrices far below the O(n^3) worst case.
     * [nCols] only matters when [cost] has no rows (the column count cannot be read from it).
     */
    fun solve(
        cost: Array<DoubleArray>,
        threshold: Double,
        nCols: Int = if (cost.isEmpty()) 0 else cost[0].size,
    ): Assignment {
        val nRows = cost.size
        if (nRows == 0 || nCols == 0) {
            return Assignment(emptyList(), (0 until nRows).toList(), (0 until nCols).toList())
        }
        // Connected components of the "admissible pair" graph (cost <= threshold). Every forbidden pair
        // costs the same as padding, so the global optimum is the union of per-component optima, and
        // the sparse IoU matrices of a tracker split into many tiny problems instead of one O(n^3).
        val parent = IntArray(nRows + nCols) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        val rowAdmissible = BooleanArray(nRows)
        val colAdmissible = BooleanArray(nCols)
        for (i in 0 until nRows) {
            val row = cost[i]
            for (j in 0 until nCols) if (row[j] <= threshold) {
                rowAdmissible[i] = true
                colAdmissible[j] = true
                val a = find(i)
                val b = find(nRows + j)
                if (a != b) parent[a] = b
            }
        }
        val compRows = HashMap<Int, ArrayList<Int>>()
        val compCols = HashMap<Int, ArrayList<Int>>()
        for (i in 0 until nRows) if (rowAdmissible[i]) compRows.getOrPut(find(i)) { ArrayList() }.add(i)
        for (j in 0 until nCols) if (colAdmissible[j]) compCols.getOrPut(find(nRows + j)) { ArrayList() }.add(j)

        val matches = ArrayList<Pair<Int, Int>>()
        val rowMatched = BooleanArray(nRows)
        val colMatched = BooleanArray(nCols)
        val filler = threshold + 1e-4
        for ((root, rowList) in compRows) {
            val colList = compCols.getValue(root)
            val n = maxOf(rowList.size, colList.size)
            val sq = Array(n) { DoubleArray(n) { filler } }
            for (a in rowList.indices) {
                val src = cost[rowList[a]]
                val dst = sq[a]
                for (b in colList.indices) {
                    val c = src[colList[b]]
                    if (c <= threshold) dst[b] = c
                }
            }
            val colToRow = hungarian(sq, n)
            for (b in 1..n) {
                val a = colToRow[b] - 1
                if (a < rowList.size && b - 1 < colList.size && cost[rowList[a]][colList[b - 1]] <= threshold) {
                    matches.add(rowList[a] to colList[b - 1])
                    rowMatched[rowList[a]] = true
                    colMatched[colList[b - 1]] = true
                }
            }
        }
        matches.sortBy { it.first }
        return Assignment(
            matches,
            (0 until nRows).filter { !rowMatched[it] },
            (0 until nCols).filter { !colMatched[it] },
        )
    }

    /** Potentials-based Hungarian on a square [n]x[n] matrix. Returns, 1-indexed, the row of each column. */
    private fun hungarian(a: Array<DoubleArray>, n: Int): IntArray {
        val inf = Double.MAX_VALUE
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)
        val minv = DoubleArray(n + 1)
        val used = BooleanArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            minv.fill(inf)
            used.fill(false)
            do {
                used[j0] = true
                val i0 = p[j0]
                val row = a[i0 - 1]
                val ui0 = u[i0]
                var delta = inf
                var j1 = 0
                for (j in 1..n) {
                    if (used[j]) continue
                    val cur = row[j - 1] - ui0 - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        return p
    }
}
