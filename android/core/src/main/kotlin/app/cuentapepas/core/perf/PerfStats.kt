package app.cuentapepas.core.perf

import app.cuentapepas.core.stats.Bootstrap

/** Linear-interpolation quantile (numpy default) of unsorted [values]; throws on empty input. */
fun percentileOf(values: List<Double>, q: Double): Double =
    Bootstrap.quantileOfSorted(values.sorted().toDoubleArray(), q)

/** Growable primitive array that caches its sorted copy until the next add. */
private class Samples {
    private var data = DoubleArray(64)
    var size = 0
        private set
    private var sorted: DoubleArray? = null

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
        sorted = null
    }

    fun quantile(q: Double): Double? {
        if (size == 0) return null
        val s = sorted ?: data.copyOf(size).also { it.sort(); sorted = it }
        return Bootstrap.quantileOfSorted(s, q)
    }
}

/**
 * Accumulates inference latencies and inter-frame intervals. Statistics are null when there is no data (never 0).
 * Invalid samples (non-finite, negative, or a zero interval) are ignored and counted in [droppedSamples]; they never throw.
 */
class PerfStats {
    private val latenciesMs = Samples()
    private val intervalsMs = Samples()

    /** Number of samples rejected as invalid. */
    var droppedSamples: Int = 0
        private set

    fun addLatencyMs(ms: Double) {
        if (ms.isFinite() && ms >= 0.0) latenciesMs.add(ms) else droppedSamples++
    }

    fun addFrameIntervalMs(ms: Double) {
        if (ms.isFinite() && ms > 0.0) intervalsMs.add(ms) else droppedSamples++
    }

    val latencyCount: Int get() = latenciesMs.size
    val intervalCount: Int get() = intervalsMs.size

    val inferenceMsMedian: Double? get() = latenciesMs.quantile(0.5)
    val inferenceMsP90: Double? get() = latenciesMs.quantile(0.9)

    /** FPS = 1000 / interval. */
    val fpsMedian: Double? get() = intervalsMs.quantile(0.5)?.let { 1000.0 / it }

    /** The slow tail: p10 of FPS comes from the p90 of the interval. */
    val fpsP10: Double? get() = intervalsMs.quantile(0.9)?.let { 1000.0 / it }
}
