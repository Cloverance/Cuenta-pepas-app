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
        // interval p90 = 90 + 0.1 * (100 - 90) = 91 ms
        assertEquals(1000.0 / 91.0, p.fpsP10!!, 1e-12)
        // interval median = 55 ms
        assertEquals(1000.0 / 55.0, p.fpsMedian!!, 1e-12)
    }

    @Test fun latencyPercentiles() {
        val p = PerfStats()
        for (i in 1..100) p.addLatencyMs(i.toDouble())
        assertEquals(50.5, p.inferenceMsMedian!!, 1e-9)
        assertEquals(90.1, p.inferenceMsP90!!, 1e-9)
    }

    @Test fun cacheIsInvalidatedByNewSamples() {
        val p = PerfStats()
        for (i in 1..3) p.addLatencyMs(i.toDouble())
        assertEquals(2.0, p.inferenceMsMedian!!, 1e-12)
        p.addLatencyMs(100.0)
        p.addLatencyMs(200.0)
        assertEquals(3.0, p.inferenceMsMedian!!, 1e-12)
        assertEquals(5, p.latencyCount)
    }

    @Test fun growsPastInitialCapacity() {
        val p = PerfStats()
        for (i in 1..1000) p.addLatencyMs(i.toDouble())
        assertEquals(1000, p.latencyCount)
        assertEquals(500.5, p.inferenceMsMedian!!, 1e-9)
    }

    @Test fun emptyIsNullNotZero() {
        val p = PerfStats()
        assertNull(p.fpsMedian)
        assertNull(p.fpsP10)
        assertNull(p.inferenceMsMedian)
        assertNull(p.inferenceMsP90)
        assertEquals(0, p.droppedSamples)
    }

    @Test fun invalidSamplesAreDroppedAndCounted() {
        val p = PerfStats()
        p.addFrameIntervalMs(0.0)
        p.addFrameIntervalMs(-5.0)
        p.addFrameIntervalMs(Double.NaN)
        p.addFrameIntervalMs(Double.POSITIVE_INFINITY)
        p.addLatencyMs(-1.0)
        p.addLatencyMs(Double.NaN)
        p.addLatencyMs(Double.NEGATIVE_INFINITY)
        assertEquals(7, p.droppedSamples)
        assertNull(p.fpsMedian)
        assertNull(p.inferenceMsMedian)
        p.addLatencyMs(0.0)
        p.addFrameIntervalMs(50.0)
        assertEquals(0.0, p.inferenceMsMedian!!, 0.0)
        assertEquals(20.0, p.fpsMedian!!, 1e-12)
        assertEquals(7, p.droppedSamples)
    }
}
