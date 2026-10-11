package app.cuentapepas.pipeline

/**
 * Rolling latency / throughput accumulator over the last [window] processed frames.
 * Not thread-safe: owned by the frame-processing thread.
 *
 * TODO(B7-merge): replace with core.perf.PerfStats once that branch lands.
 */
class FramePerf(private val window: Int = 30) {
    init {
        require(window >= 2) { "window must be >= 2, got $window" }
    }

    private val latencies = FloatArray(window)
    private val times = LongArray(window)
    private var count = 0
    private var next = 0

    /** Records one processed frame: its inference time and the monotonic time ([System.nanoTime]) it finished at. */
    fun record(inferenceMs: Float, nowNs: Long) {
        latencies[next] = inferenceMs
        times[next] = nowNs
        next = (next + 1) % window
        if (count < window) count++
    }

    /** Mean inference time over the window, or NaN before any frame ("no data", never 0). */
    fun meanInferenceMs(): Float {
        if (count == 0) return Float.NaN
        var sum = 0f
        for (i in 0 until count) sum += latencies[i]
        return sum / count
    }

    /** Processed frames per second over the window, or NaN before two frames. */
    fun fps(): Float {
        if (count < 2) return Float.NaN
        val newest = times[(next - 1 + window) % window]
        val oldest = times[if (count < window) 0 else next]
        val seconds = (newest - oldest) / 1e9
        return if (seconds > 0.0) ((count - 1) / seconds).toFloat() else Float.NaN
    }

    fun reset() {
        count = 0
        next = 0
    }
}
