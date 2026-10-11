package app.cuentapepas.core.perf

import app.cuentapepas.core.stats.Bootstrap

/** Linear-interpolation quantile (numpy default) of unsorted [values]; throws on empty input. */
fun percentileOf(values: List<Double>, q: Double): Double =
    Bootstrap.quantileOfSorted(values.sorted().toDoubleArray(), q)

/** Accumulates inference latencies and inter-frame intervals. Statistics are null when there is no data (never 0). */
class PerfStats {
    private val latenciesMs = ArrayList<Double>()
    private val intervalsMs = ArrayList<Double>()

    fun addLatencyMs(ms: Double) {
        require(ms.isFinite() && ms >= 0.0) { "invalid latency: $ms" }
        latenciesMs += ms
    }

    fun addFrameIntervalMs(ms: Double) {
        require(ms.isFinite() && ms > 0.0) { "invalid frame interval: $ms" }
        intervalsMs += ms
    }

    val latencyCount: Int get() = latenciesMs.size
    val intervalCount: Int get() = intervalsMs.size

    val inferenceMsMedian: Double? get() = latenciesMs.takeIf { it.isNotEmpty() }?.let { percentileOf(it, 0.5) }
    val inferenceMsP90: Double? get() = latenciesMs.takeIf { it.isNotEmpty() }?.let { percentileOf(it, 0.9) }

    /** FPS = 1000 / interval. */
    val fpsMedian: Double? get() = intervalsMs.takeIf { it.isNotEmpty() }?.let { 1000.0 / percentileOf(it, 0.5) }

    /** The slow tail: p10 of FPS comes from the p90 of the interval. */
    val fpsP10: Double? get() = intervalsMs.takeIf { it.isNotEmpty() }?.let { 1000.0 / percentileOf(it, 0.9) }
}
