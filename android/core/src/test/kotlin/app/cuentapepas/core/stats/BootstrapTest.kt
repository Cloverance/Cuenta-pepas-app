package app.cuentapepas.core.stats

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapTest {
    @Test fun percentileOf1To100At90MatchesNumpy() {
        // numpy.quantile(np.arange(1, 101), [0.05, 0.95]) -> [5.95, 95.05]
        val ci = Bootstrap.percentile(DoubleArray(100) { (it + 1).toDouble() }, 0.90)
        assertEquals(5.95, ci.low, 1e-9)
        assertEquals(95.05, ci.high, 1e-9)
    }

    @Test fun percentileSortsAndDoesNotMutateInput() {
        val values = doubleArrayOf(4.0, 1.0, 3.0, 2.0)
        // numpy.quantile([1, 2, 3, 4], [0.25, 0.75]) -> [1.75, 3.25]
        val ci = Bootstrap.percentile(values, 0.50)
        assertEquals(1.75, ci.low, 1e-12)
        assertEquals(3.25, ci.high, 1e-12)
        assertArrayEquals(doubleArrayOf(4.0, 1.0, 3.0, 2.0), values, 0.0)
    }

    @Test fun quantileMatchesNumpyLinearMethod() {
        val sorted = doubleArrayOf(10.0, 20.0, 40.0)
        assertEquals(10.0, Bootstrap.quantileOfSorted(sorted, 0.0), 0.0)
        assertEquals(40.0, Bootstrap.quantileOfSorted(sorted, 1.0), 0.0)
        assertEquals(20.0, Bootstrap.quantileOfSorted(sorted, 0.5), 0.0)
        // h = 0.9 * 2 = 1.8 -> 20 + 0.8 * 20 = 36
        assertEquals(36.0, Bootstrap.quantileOfSorted(sorted, 0.9), 1e-12)
    }

    @Test fun singleValueGivesDegenerateInterval() {
        val ci = Bootstrap.percentile(doubleArrayOf(7.5), 0.90)
        assertEquals(Interval(7.5, 7.5), ci)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyValuesFail() { Bootstrap.percentile(DoubleArray(0), 0.90) }

    @Test(expected = IllegalArgumentException::class)
    fun nanValuesFail() { Bootstrap.percentile(doubleArrayOf(1.0, Double.NaN), 0.90) }

    @Test(expected = IllegalArgumentException::class)
    fun confidenceMustBeInsideUnitInterval() { Bootstrap.percentile(doubleArrayOf(1.0, 2.0), 1.0) }

    @Test fun intervalContainsAndWidth() {
        val ci = Interval(2.0, 5.0)
        assertTrue(ci.contains(2.0)); assertTrue(ci.contains(5.0)); assertTrue(ci.contains(3.3))
        assertFalse(ci.contains(1.99)); assertFalse(ci.contains(5.01))
        assertEquals(3.0, ci.width, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun intervalRejectsInvertedBounds() { Interval(3.0, 2.0) }

    @Test(expected = IllegalArgumentException::class)
    fun intervalRejectsNaN() { Interval(Double.NaN, 2.0) }

    @Test(expected = IllegalArgumentException::class)
    fun estimateRejectsNaN() { Estimate(Double.NaN, Interval(0.0, 1.0)) }

    @Test fun estimateJsonRoundTrip() {
        val e = Estimate(3.0, Interval(2.5, 3.6))
        val json = Json.encodeToString(Estimate.serializer(), e)
        assertEquals(e, Json.decodeFromString(Estimate.serializer(), json))
    }
}
