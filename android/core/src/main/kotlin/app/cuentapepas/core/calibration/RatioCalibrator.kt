package app.cuentapepas.core.calibration

import app.cuentapepas.core.stats.Bootstrap
import app.cuentapepas.core.stats.Interval
import kotlinx.serialization.Serializable
import java.util.SplittableRandom
import kotlin.math.abs

/** One sample plant: cherries seen by the detector and cherries counted by hand on the same plant. */
@Serializable
data class CalibrationSample(val plantId: String, val visible: Int, val manual: Int)

/**
 * Visible -> real calibration. When [usable] is false every numeric field is null and [reason] says why (Spanish).
 * [loocvMeanAbsPctError] can be null even when [usable] is true (no plant with manual > 0 and a defined leave-one-out ratio).
 * [skippedReplicates] counts bootstrap resamples dropped because they had no visible cherries (ratio undefined).
 */
@Serializable
data class CalibrationResult(
    val n: Int,
    val k: Double?,
    val kCi: Interval?,
    val plantFactorInterval: Interval?,
    val loocvMeanAbsPctError: Double?, // null can occur even when usable = true
    val usable: Boolean,
    val reason: String?,
    val skippedReplicates: Int = 0,
)

object RatioCalibrator {
    /**
     * Fits the visible-to-manual ratio. Negative counts in [samples] throw IllegalArgumentException;
     * callers in the app must validate input before calling.
     */
    fun fit(
        samples: List<CalibrationSample>,
        minSamples: Int = 10,
        confidence: Double = 0.90,
        replicates: Int = 2000,
        seed: Long = 20261011L,
    ): CalibrationResult {
        require(replicates > 0) { "replicates must be positive" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be in (0, 1)" }
        for (s in samples) {
            require(s.visible >= 0 && s.manual >= 0) {
                "negative count in sample ${s.plantId}: visible=${s.visible}, manual=${s.manual}"
            }
        }
        val n = samples.size
        fun unusable(reason: String) = CalibrationResult(n, null, null, null, null, false, reason)

        if (n < minSamples) return unusable("Se necesitan al menos $minSamples plantas de muestra")
        val visible = IntArray(n) { samples[it].visible }
        val manual = IntArray(n) { samples[it].manual }
        val sumVisible = visible.sumOf { it.toLong() }
        val sumManual = manual.sumOf { it.toLong() }
        if (sumVisible == 0L) return unusable("Las plantas de muestra no tienen pepas visibles; no se puede calibrar")
        if (sumManual == 0L) return unusable("El conteo manual de las plantas de muestra suma 0; no se puede calibrar")

        val k = sumManual.toDouble() / sumVisible

        // Per-plant ratios r_j = manual_j / visible_j, defined only where visible_j > 0.
        val positive = (0 until n).filter { visible[it] > 0 }.toIntArray()
        val rng = SplittableRandom(seed)
        val ks = DoubleArray(replicates)
        val plantFactors = DoubleArray(replicates)
        var valid = 0
        var skipped = 0
        repeat(replicates) {
            val kb = resampledRatio(visible, manual, rng)
            if (kb.isNaN()) {
                skipped++
            } else {
                val j = positive[rng.nextInt(positive.size)]
                val e = (manual[j].toDouble() / visible[j]) / k
                ks[valid] = kb
                plantFactors[valid] = kb * e
                valid++
            }
        }
        if (valid == 0) {
            return unusable("No se pudo estimar el intervalo: todas las réplicas bootstrap quedaron sin pepas visibles")
        }
        return CalibrationResult(
            n = n,
            k = k,
            kCi = Bootstrap.percentile(ks.copyOf(valid), confidence),
            plantFactorInterval = Bootstrap.percentile(plantFactors.copyOf(valid), confidence),
            loocvMeanAbsPctError = loocv(visible, manual, sumVisible, sumManual),
            usable = true,
            reason = null,
            skippedReplicates = skipped,
        )
    }

    /**
     * Ratio (sum of resampled manual) / (sum of resampled visible) of one with-replacement resample of the samples, or NaN when the resample has
     * no visible cherries. NaN is internal: callers must skip it, never emit it.
     */
    internal fun resampledRatio(visible: IntArray, manual: IntArray, rng: SplittableRandom): Double {
        val n = visible.size
        var sv = 0L
        var sm = 0L
        repeat(n) {
            val i = rng.nextInt(n)
            sv += visible[i]
            sm += manual[i]
        }
        return if (sv == 0L) Double.NaN else sm.toDouble() / sv
    }

    /** Mean of `|k_{-i}·v_i − m_i| / m_i` over plants with `m_i > 0` and a defined leave-one-out ratio. */
    private fun loocv(visible: IntArray, manual: IntArray, sumVisible: Long, sumManual: Long): Double? {
        var total = 0.0
        var terms = 0
        for (i in visible.indices) {
            if (manual[i] <= 0) continue
            val sv = sumVisible - visible[i]
            if (sv == 0L) continue
            val kMinus = (sumManual - manual[i]).toDouble() / sv
            total += abs(kMinus * visible[i] - manual[i]) / manual[i]
            terms++
        }
        return if (terms == 0) null else total / terms
    }
}
