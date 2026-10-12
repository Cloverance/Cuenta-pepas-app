package app.cuentapepas.core.perf

/**
 * Fixed-size ring buffer of the most recent samples, for live figures that must reflect recent performance.
 * Memory and cost are bounded by [capacity] no matter how long the session runs. Invalid samples (non-finite or
 * negative) are ignored. Not thread-safe.
 */
class RollingWindow(val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity >= 1) { "capacity must be >= 1: $capacity" }
    }

    private val ring = DoubleArray(capacity)
    private val scratch = DoubleArray(capacity)
    private var next = 0

    /** Number of samples currently held (at most [capacity]). */
    var count: Int = 0
        private set

    fun add(v: Double) {
        if (!v.isFinite() || v < 0.0) return
        ring[next] = v
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        next = 0
        count = 0
    }

    /** Median of the held samples (mean of the two middle ones for an even count); null when empty. */
    fun median(): Double? {
        if (count == 0) return null
        System.arraycopy(ring, 0, scratch, 0, count) // before the ring wraps the first [count] slots are the data
        java.util.Arrays.sort(scratch, 0, count)
        return if (count % 2 == 1) scratch[count / 2] else (scratch[count / 2 - 1] + scratch[count / 2]) / 2.0
    }

    companion object {
        const val DEFAULT_CAPACITY = 60
    }
}
