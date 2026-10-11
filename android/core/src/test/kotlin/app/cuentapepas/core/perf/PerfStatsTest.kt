package app.cuentapepas.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerfStatsTest {
    @Test fun fpsFromIntervals() {
        val p = PerfStats()
        repeat(50) { p.addFrameIntervalMs(66.6) }
        assertEquals(15.0, p.fpsMedian!!, 0.05)
        assertEquals(15.0, p.fpsP10!!, 0.05)
    }

    @Test fun fpsP10UsesIntervalP90() {
        val p = PerfStats()
        for (i in 1..10) p.addFrameIntervalMs(i * 10.0)
        assertEquals(1000.0 / percentileOf((1..10).map { it * 10.0 }, 0.9), p.fpsP10!!, 1e-9)
    }

    @Test fun latencyPercentiles() {
        val p = PerfStats()
        for (i in 1..100) p.addLatencyMs(i.toDouble())
        assertEquals(50.5, p.inferenceMsMedian!!, 1e-9)
        assertEquals(90.1, p.inferenceMsP90!!, 1e-9)
    }

    @Test fun emptyIsNullNotZero() {
        val p = PerfStats()
        assertNull(p.fpsMedian)
        assertNull(p.fpsP10)
        assertNull(p.inferenceMsMedian)
        assertNull(p.inferenceMsP90)
    }

    @Test(expected = IllegalArgumentException::class) fun nonPositiveIntervalThrows() = PerfStats().addFrameIntervalMs(0.0)
}
