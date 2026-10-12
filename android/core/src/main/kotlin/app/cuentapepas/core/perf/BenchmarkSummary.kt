package app.cuentapepas.core.perf

/** One inference latency measurement taken [tSec] seconds after the benchmark started. */
data class LatencyPoint(val tSec: Double, val ms: Double)

/** Median latency of the first and last window of a run, and their ratio ([lastOverFirst] > 1 means slowing down). */
data class LatencyTrend(val windowSec: Double, val firstMedianMs: Double, val lastMedianMs: Double) {
    val lastOverFirst: Double get() = lastMedianMs / firstMedianMs
}

/** Pure aggregation helpers of the on-device benchmark. Every result is null when there is no data (never 0). */
object BenchmarkSummary {
    const val SAMPLE_INTERVAL_SEC = 10.0
    const val TREND_WINDOW_SEC = 60.0

    /** Median of the points with `fromSec <= t < toSec` (the end is inclusive when [inclusiveEnd]), or null if none. */
    fun medianInWindow(points: List<LatencyPoint>, fromSec: Double, toSec: Double, inclusiveEnd: Boolean = false): Double? {
        val v = points.filter { it.tSec >= fromSec && (if (inclusiveEnd) it.tSec <= toSec else it.tSec < toSec) }.map { it.ms }
        return if (v.isEmpty()) null else percentileOf(v, 0.5)
    }

    /**
     * First-window vs last-window median over a run of [totalSec]. The window is [TREND_WINDOW_SEC] but at most half
     * the run, so that the two windows never overlap (a 1-minute run compares 30 s with 30 s). Null when either is empty.
     */
    fun trend(points: List<LatencyPoint>, totalSec: Double): LatencyTrend? {
        if (totalSec <= 0.0) return null
        val w = minOf(TREND_WINDOW_SEC, totalSec / 2)
        val first = medianInWindow(points, 0.0, w) ?: return null
        val last = medianInWindow(points, totalSec - w, totalSec, inclusiveEnd = true) ?: return null
        return LatencyTrend(w, first, last)
    }

    /** Battery drain in percentage points per hour (positive = discharging); null when unknown or [elapsedSec] <= 0. */
    fun batteryPctPerHour(startPct: Int?, endPct: Int?, elapsedSec: Double): Double? {
        if (startPct == null || endPct == null || elapsedSec <= 0.0) return null
        return (startPct - endPct) * 3600.0 / elapsedSec
    }
}
