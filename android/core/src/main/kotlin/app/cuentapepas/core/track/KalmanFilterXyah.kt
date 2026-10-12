package app.cuentapepas.core.track

import kotlin.math.sqrt

/**
 * Constant-velocity Kalman filter over the box state `[cx, cy, a = w/h, h, vcx, vcy, va, vh]`,
 * a port of the `kalman_filter.py` of ByteTrack (MIT, FoundationVision/ByteTrack) working in place
 * on caller-owned arrays: `mean` is a [DoubleArray] of 8, `cov` a row-major 8x8 [DoubleArray].
 *
 * Noise scales with the box height, exactly as in the reference. The gain is computed through
 * the Cholesky factor of the projected 4x4 covariance. Not thread safe (it reuses scratch buffers).
 */
class KalmanFilterXyah(
    private val stdWeightPosition: Double = 1.0 / 20,
    private val stdWeightVelocity: Double = 1.0 / 160,
) {
    private val tmp = DoubleArray(64)
    private val pht = DoubleArray(32) // P H^T, 8x4
    private val gain = DoubleArray(32) // K, 8x4
    private val s = DoubleArray(16) // innovation covariance, 4x4
    private val l = DoubleArray(16) // Cholesky factor of s, lower triangular
    private val projMean = DoubleArray(4)
    private val y = DoubleArray(4)

    /** Initial state from a `[cx, cy, a, h]` measurement, with zero velocity. */
    fun initiate(measurement: DoubleArray, mean: DoubleArray, cov: DoubleArray) {
        val h = measurement[3]
        for (i in 0 until 4) {
            mean[i] = measurement[i]
            mean[i + 4] = 0.0
        }
        cov.fill(0.0)
        val wp = 2 * stdWeightPosition * h
        val wv = 10 * stdWeightVelocity * h
        cov[0] = wp * wp
        cov[9] = wp * wp
        cov[18] = 1e-2 * 1e-2
        cov[27] = wp * wp
        cov[36] = wv * wv
        cov[45] = wv * wv
        cov[54] = 1e-5 * 1e-5
        cov[63] = wv * wv
    }

    /** One-step prediction: `x = F x`, `P = F P F^T + Q` with dt = 1. */
    fun predict(mean: DoubleArray, cov: DoubleArray) {
        val h = mean[3]
        val sp = stdWeightPosition * h
        val sv = stdWeightVelocity * h
        // F = [I I; 0 I], so with P = [A B; C D]: F P F^T = [A+B+C+D, B+D; C+D, D].
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                val a = cov[i * 8 + j]
                val b = cov[i * 8 + j + 4]
                val c = cov[(i + 4) * 8 + j]
                val d = cov[(i + 4) * 8 + j + 4]
                tmp[i * 8 + j] = a + b + c + d
                tmp[i * 8 + j + 4] = b + d
                tmp[(i + 4) * 8 + j] = c + d
                tmp[(i + 4) * 8 + j + 4] = d
            }
        }
        System.arraycopy(tmp, 0, cov, 0, 64)
        cov[0] += sp * sp
        cov[9] += sp * sp
        cov[18] += 1e-2 * 1e-2
        cov[27] += sp * sp
        cov[36] += sv * sv
        cov[45] += sv * sv
        cov[54] += 1e-5 * 1e-5
        cov[63] += sv * sv
        for (i in 0 until 4) mean[i] += mean[i + 4]
    }

    /** Projects the state to measurement space: [outMean] (4) and [outCov] (4x4) with measurement noise added. */
    fun project(mean: DoubleArray, cov: DoubleArray, outMean: DoubleArray, outCov: DoubleArray) {
        val h = mean[3]
        val sp = stdWeightPosition * h
        for (i in 0 until 4) {
            outMean[i] = mean[i]
            for (j in 0 until 4) outCov[i * 4 + j] = cov[i * 8 + j]
        }
        outCov[0] += sp * sp
        outCov[5] += sp * sp
        outCov[10] += 1e-1 * 1e-1
        outCov[15] += sp * sp
    }

    /** Correction step with a `[cx, cy, a, h]` measurement. */
    fun update(mean: DoubleArray, cov: DoubleArray, measurement: DoubleArray) {
        project(mean, cov, projMean, s)
        cholesky(s, l)
        for (i in 0 until 4) y[i] = measurement[i] - projMean[i]
        for (r in 0 until 8) for (c in 0 until 4) pht[r * 4 + c] = cov[r * 8 + c]
        // K = P H^T S^-1: solve S k = (P H^T)[r] for every row r (S is symmetric).
        for (r in 0 until 8) solveCholesky(l, pht, r * 4, gain, r * 4)
        for (r in 0 until 8) {
            var dm = 0.0
            for (c in 0 until 4) dm += gain[r * 4 + c] * y[c]
            mean[r] += dm
        }
        // K S K^T = (P H^T) K^T because K S = P H^T.
        for (i in 0 until 8) for (j in 0 until 8) {
            var acc = 0.0
            for (k in 0 until 4) acc += pht[i * 4 + k] * gain[j * 4 + k]
            tmp[i * 8 + j] = cov[i * 8 + j] - acc
        }
        System.arraycopy(tmp, 0, cov, 0, 64)
    }

    private fun cholesky(a: DoubleArray, out: DoubleArray) {
        out.fill(0.0)
        for (i in 0 until 4) {
            for (j in 0..i) {
                var sum = a[i * 4 + j]
                for (k in 0 until j) sum -= out[i * 4 + k] * out[j * 4 + k]
                out[i * 4 + j] = if (i == j) sqrt(sum) else sum / out[j * 4 + j]
            }
        }
    }

    /** Solves `L L^T x = b` for the 4-vector at `b[bOff]`, writing to `x[xOff]`. */
    private fun solveCholesky(lo: DoubleArray, b: DoubleArray, bOff: Int, x: DoubleArray, xOff: Int) {
        for (i in 0 until 4) {
            var sum = b[bOff + i]
            for (k in 0 until i) sum -= lo[i * 4 + k] * x[xOff + k]
            x[xOff + i] = sum / lo[i * 4 + i]
        }
        for (i in 3 downTo 0) {
            var sum = x[xOff + i]
            for (k in i + 1 until 4) sum -= lo[k * 4 + i] * x[xOff + k]
            x[xOff + i] = sum / lo[i * 4 + i]
        }
    }
}
