package app.cuentapepas.core.calibration

import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Synthetic calibration plants shared by the calibration and lot-estimate tests. */
internal object SyntheticSamples {
    /**
     * `visible ~ U{50..300}` and `manual = round(trueK · visible · ε)` with log-normal `ε = exp(σZ − σ²/2)`.
     * The `−σ²/2` shift makes `E[ε] = 1`, so the population ratio `E[manual] / E[visible]` is exactly `trueK`
     * (up to the negligible rounding of `manual`).
     */
    fun generate(n: Int, seed: Long, trueK: Double = 3.0, sigma: Double = 0.15): List<CalibrationSample> {
        val rng = SplittableRandom(seed)
        return List(n) { i ->
            val visible = rng.nextInt(50, 301)
            val eps = exp(sigma * gaussian(rng) - sigma * sigma / 2)
            CalibrationSample("s$i", visible, (trueK * visible * eps).roundToInt())
        }
    }

    /** Standard normal by Box–Muller (SplittableRandom has no nextGaussian on every JDK we target). */
    fun gaussian(rng: SplittableRandom): Double {
        val u1 = 1.0 - rng.nextDouble() // (0, 1], keeps ln finite
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}
