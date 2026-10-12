package app.cuentapepas.core.perf

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class BenchmarkSummaryTest {
    private fun pts(vararg p: Pair<Double, Double>) = p.map { LatencyPoint(it.first, it.second) }

    @Test fun windowMedianBounds() {
        val p = pts(0.0 to 10.0, 5.0 to 20.0, 10.0 to 30.0)
        assertEquals(15.0, BenchmarkSummary.medianInWindow(p, 0.0, 10.0)!!, 1e-9)
        assertEquals(20.0, BenchmarkSummary.medianInWindow(p, 0.0, 10.0, inclusiveEnd = true)!!, 1e-9)
        assertNull(BenchmarkSummary.medianInWindow(p, 50.0, 60.0))
    }

    @Test fun trendDetectsSlowdown() {
        val p = (0 until 900).map { LatencyPoint(it.toDouble(), if (it < 60) 20.0 else if (it >= 840) 30.0 else 25.0) }
        val t = BenchmarkSummary.trend(p, 900.0)!!
        assertEquals(60.0, t.windowSec, 0.0)
        assertEquals(20.0, t.firstMedianMs, 1e-9)
        assertEquals(30.0, t.lastMedianMs, 1e-9)
        assertEquals(1.5, t.lastOverFirst, 1e-9)
    }

    @Test fun shortRunUsesHalfWindows() {
        val p = (0 until 60).map { LatencyPoint(it.toDouble(), if (it < 30) 10.0 else 12.0) }
        val t = BenchmarkSummary.trend(p, 60.0)!!
        assertEquals(30.0, t.windowSec, 0.0)
        assertEquals(10.0, t.firstMedianMs, 1e-9)
        assertEquals(12.0, t.lastMedianMs, 1e-9)
    }

    @Test fun trendNullWithoutData() {
        assertNull(BenchmarkSummary.trend(emptyList(), 60.0))
        assertNull(BenchmarkSummary.trend(pts(0.0 to 1.0), 0.0))
        assertNull(BenchmarkSummary.trend(pts(0.0 to 1.0), 60.0)) // no last-window data
    }

    @Test fun batteryRate() {
        assertEquals(12.0, BenchmarkSummary.batteryPctPerHour(80, 77, 900.0)!!, 1e-9)
        assertEquals(-4.0, BenchmarkSummary.batteryPctPerHour(50, 51, 900.0)!!, 1e-9) // charging
        assertNull(BenchmarkSummary.batteryPctPerHour(null, 77, 900.0))
        assertNull(BenchmarkSummary.batteryPctPerHour(80, 77, 0.0))
    }
}
