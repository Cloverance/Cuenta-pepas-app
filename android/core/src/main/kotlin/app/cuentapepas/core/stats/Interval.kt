package app.cuentapepas.core.stats

import kotlinx.serialization.Serializable

/** Closed interval of finite numbers. */
@Serializable
data class Interval(val low: Double, val high: Double) {
    init {
        require(low.isFinite() && high.isFinite()) { "interval bounds must be finite: [$low, $high]" }
        require(low <= high) { "interval bounds are inverted: [$low, $high]" }
    }

    val width: Double get() = high - low

    fun contains(x: Double): Boolean = x in low..high
}

/** A point estimate always travels with its interval; there is no way to build one without it. */
@Serializable
data class Estimate(val value: Double, val ci: Interval) {
    init {
        require(value.isFinite()) { "estimate value must be finite: $value" }
    }
}
