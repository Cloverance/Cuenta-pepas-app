package app.cuentapepas.core.stats

import kotlin.math.floor

object Bootstrap {
    /**
     * Percentile interval with two-sided [confidence]: quantiles `(1-c)/2` and `1-(1-c)/2` of [values]
     * with linear interpolation, equal to `numpy.quantile` with its default method. Does not mutate [values].
     */
    fun percentile(values: DoubleArray, confidence: Double): Interval {
        require(values.isNotEmpty()) { "values must not be empty" }
        require(values.none { it.isNaN() }) { "values must not contain NaN" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be in (0, 1): $confidence" }
        val sorted = values.copyOf().also { it.sort() }
        val alpha = (1.0 - confidence) / 2.0
        return Interval(quantileOfSorted(sorted, alpha), quantileOfSorted(sorted, 1.0 - alpha))
    }

    /** Linear-interpolation quantile of an already sorted, non-empty array (`q` in [0, 1]). */
    fun quantileOfSorted(sorted: DoubleArray, q: Double): Double {
        require(sorted.isNotEmpty()) { "values must not be empty" }
        require(q >= 0.0 && q <= 1.0) { "q must be in [0, 1]: $q" }
        val h = (sorted.size - 1) * q
        val lo = floor(h).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
    }
}
