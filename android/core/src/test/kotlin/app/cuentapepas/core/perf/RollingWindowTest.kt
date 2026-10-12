package app.cuentapepas.core.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingWindowTest {
    @Test fun emptyHasNoMedian() = assertNull(RollingWindow(5).median())

    @Test fun medianOddAndEven() {
        val w = RollingWindow(10)
        w.add(30.0); w.add(10.0); w.add(20.0)
        assertEquals(20.0, w.median()!!, 0.0)
        w.add(40.0)
        assertEquals(25.0, w.median()!!, 0.0)
    }

    @Test fun oldSamplesFallOutOfTheWindow() {
        val w = RollingWindow(5)
        repeat(100) { w.add(1000.0) } // a slow past
        repeat(5) { w.add(20.0) }     // a fast present
        assertEquals(5, w.count)
        assertEquals(20.0, w.median()!!, 0.0)
    }

    @Test fun partialWrapKeepsLatestSamples() {
        val w = RollingWindow(4)
        for (i in 1..6) w.add(i.toDouble()) // holds 3,4,5,6
        assertEquals(4.5, w.median()!!, 0.0)
    }

    @Test fun invalidSamplesAreIgnored() {
        val w = RollingWindow(3)
        w.add(Double.NaN); w.add(-1.0); w.add(Double.POSITIVE_INFINITY)
        assertEquals(0, w.count)
        assertNull(w.median())
    }

    @Test fun clearForgetsEverything() {
        val w = RollingWindow(3)
        w.add(5.0); w.add(6.0)
        w.clear()
        assertNull(w.median())
        w.add(9.0)
        assertEquals(9.0, w.median()!!, 0.0)
    }
}
