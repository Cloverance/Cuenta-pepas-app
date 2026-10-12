package app.cuentapepas.core.track

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalmanFilterXyahTest {

    private val start = doubleArrayOf(100.0, 50.0, 0.5, 20.0)

    private fun initiated(kf: KalmanFilterXyah, measurement: DoubleArray = start): Pair<DoubleArray, DoubleArray> {
        val mean = DoubleArray(8)
        val cov = DoubleArray(64)
        kf.initiate(measurement, mean, cov)
        return mean to cov
    }

    private fun diag(cov: DoubleArray) = DoubleArray(8) { cov[it * 9] }

    @Test
    fun initiateMatchesByteTrack() {
        val (mean, cov) = initiated(KalmanFilterXyah())
        assertArrayEquals(doubleArrayOf(100.0, 50.0, 0.5, 20.0, 0.0, 0.0, 0.0, 0.0), mean, 0.0)
        val h = 20.0
        val std = doubleArrayOf(2 * h / 20, 2 * h / 20, 1e-2, 2 * h / 20, 10 * h / 160, 10 * h / 160, 1e-5, 10 * h / 160)
        for (r in 0 until 8) for (c in 0 until 8) {
            val expected = if (r == c) std[r] * std[r] else 0.0
            assertEquals("cov[$r][$c]", expected, cov[r * 8 + c], 1e-15)
        }
    }

    @Test
    fun predictKeepsMeanAndGrowsCovariance() {
        val kf = KalmanFilterXyah()
        val (mean, cov) = initiated(kf)
        val before = diag(cov)
        kf.predict(mean, cov)
        assertArrayEquals(doubleArrayOf(100.0, 50.0, 0.5, 20.0, 0.0, 0.0, 0.0, 0.0), mean, 0.0)
        val after = diag(cov)
        for (i in 0 until 8) assertTrue("diag[$i] ${after[i]} > ${before[i]}", after[i] > before[i])
    }

    @Test
    fun updateWithSameMeasurementKeepsMeanAndShrinksCovariance() {
        val kf = KalmanFilterXyah()
        val (mean, cov) = initiated(kf)
        kf.predict(mean, cov)
        val before = diag(cov)
        kf.update(mean, cov, start)
        assertArrayEquals(doubleArrayOf(100.0, 50.0, 0.5, 20.0, 0.0, 0.0, 0.0, 0.0), mean, 1e-12)
        val after = diag(cov)
        for (i in 0 until 8) assertTrue("diag[$i] ${after[i]} < ${before[i]}", after[i] < before[i])
    }

    @Test
    fun velocityConvergesForObjectMovingFivePixelsPerFrame() {
        val kf = KalmanFilterXyah()
        val (mean, cov) = initiated(kf)
        for (k in 1..10) {
            kf.predict(mean, cov)
            kf.update(mean, cov, doubleArrayOf(100.0 + 5.0 * k, 50.0, 0.5, 20.0))
        }
        assertEquals(5.0, mean[4], 0.5)
        assertEquals(0.0, mean[5], 1e-9)
    }

    @Test
    fun projectAddsMeasurementNoise() {
        val kf = KalmanFilterXyah()
        val (mean, cov) = initiated(kf)
        val pm = DoubleArray(4)
        val pc = DoubleArray(16)
        kf.project(mean, cov, pm, pc)
        assertArrayEquals(start, pm, 0.0)
        val h = 20.0
        val r = doubleArrayOf(h / 20, h / 20, 1e-1, h / 20)
        for (i in 0 until 4) for (j in 0 until 4) {
            val expected = cov[i * 8 + j] + if (i == j) r[i] * r[i] else 0.0
            assertEquals("S[$i][$j]", expected, pc[i * 4 + j], 1e-15)
        }
    }

    /** The in-place, Cholesky-based implementation must equal the textbook matrix formulas. */
    @Test
    fun matchesNaiveMatrixFormulasOverASequence() {
        val kf = KalmanFilterXyah()
        val (mean, cov) = initiated(kf, doubleArrayOf(300.0, 200.0, 0.8, 25.0))
        var nMean = mean.copyOf()
        var nCov = cov.copyOf()
        val measurements = listOf(
            doubleArrayOf(304.0, 199.0, 0.82, 25.5),
            doubleArrayOf(309.0, 197.5, 0.79, 26.0),
            doubleArrayOf(312.5, 196.0, 0.81, 25.0),
            doubleArrayOf(318.0, 195.0, 0.80, 24.0),
        )
        for (z in measurements) {
            kf.predict(mean, cov)
            val p = Naive.predict(nMean, nCov)
            nMean = p.first; nCov = p.second
            assertArrayEquals(nMean, mean, 1e-9)
            assertArrayEquals(nCov, cov, 1e-9)

            kf.update(mean, cov, z)
            val u = Naive.update(nMean, nCov, z)
            nMean = u.first; nCov = u.second
            assertArrayEquals(nMean, mean, 1e-9)
            assertArrayEquals(nCov, cov, 1e-9)
        }
    }

    /** Dense textbook Kalman filter with explicit F, H, Q, R and Gauss-Jordan inversion. */
    private object Naive {
        private const val WP = 1.0 / 20
        private const val WV = 1.0 / 160

        private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
            Array(a.size) { i -> DoubleArray(b[0].size) { j -> (b.indices).sumOf { k -> a[i][k] * b[k][j] } } }

        private fun t(a: Array<DoubleArray>) = Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }

        private fun inv(a: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
            for (c in 0 until n) {
                val piv = (c until n).maxBy { kotlin.math.abs(m[it][c]) }
                val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
                val d = m[c][c]
                for (j in 0 until 2 * n) m[c][j] /= d
                for (r in 0 until n) if (r != c) {
                    val f = m[r][c]
                    for (j in 0 until 2 * n) m[r][j] -= f * m[c][j]
                }
            }
            return Array(n) { i -> DoubleArray(n) { j -> m[i][n + j] } }
        }

        private fun toMat(flat: DoubleArray, n: Int) = Array(n) { i -> DoubleArray(n) { j -> flat[i * n + j] } }
        private fun flat(m: Array<DoubleArray>) = DoubleArray(m.size * m.size) { m[it / m.size][it % m.size] }

        fun predict(mean: DoubleArray, cov: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val f = Array(8) { i -> DoubleArray(8) { j -> if (i == j || j == i + 4) 1.0 else 0.0 } }
            val h = mean[3]
            val std = doubleArrayOf(WP * h, WP * h, 1e-2, WP * h, WV * h, WV * h, 1e-5, WV * h)
            val newMean = DoubleArray(8) { i -> (0 until 8).sumOf { k -> f[i][k] * mean[k] } }
            val fp = mul(mul(f, toMat(cov, 8)), t(f))
            for (i in 0 until 8) fp[i][i] += std[i] * std[i]
            return newMean to flat(fp)
        }

        fun update(mean: DoubleArray, cov: DoubleArray, z: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val hm = Array(4) { i -> DoubleArray(8) { j -> if (i == j) 1.0 else 0.0 } }
            val p = toMat(cov, 8)
            val hh = mean[3]
            val r = doubleArrayOf(WP * hh, WP * hh, 1e-1, WP * hh)
            val s = mul(mul(hm, p), t(hm))
            for (i in 0 until 4) s[i][i] += r[i] * r[i]
            val k = mul(mul(p, t(hm)), inv(s))
            val y = DoubleArray(4) { i -> z[i] - mean[i] }
            val newMean = DoubleArray(8) { i -> mean[i] + (0 until 4).sumOf { j -> k[i][j] * y[j] } }
            val kskt = mul(mul(k, s), t(k))
            val newCov = Array(8) { i -> DoubleArray(8) { j -> p[i][j] - kskt[i][j] } }
            return newMean to flat(newCov)
        }
    }
}
