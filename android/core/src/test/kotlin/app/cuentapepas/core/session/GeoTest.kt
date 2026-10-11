package app.cuentapepas.core.session

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    private fun track(n: Int, acc: Float = 5f, lonOffsetDeg: Double = 0.0) =
        List(n) { GpsPoint(it * 1000L, 4.0 + it * 0.00001, -75.0 + lonOffsetDeg, acc) }

    @Test fun haversineOneMilliDegreeLon() {
        assertEquals(111.0, Geo.haversineM(GpsPoint(0, 4.0, -75.0, 1f), GpsPoint(0, 4.0, -75.001, 1f)), 1.0)
    }

    @Test fun selfOverlapIsOne() = assertEquals(1.0, Geo.overlapFraction(track(20), track(20)), 0.0)

    @Test fun shiftedTwentyMetersIsZero() {
        // 0.00018 deg of longitude at lat 4 is about 20 m
        assertEquals(0.0, Geo.overlapFraction(track(20, lonOffsetDeg = 0.00018), track(20)), 0.0)
    }

    @Test fun poorAccuracyFilteredToZero() {
        assertEquals(0.0, Geo.overlapFraction(track(20, acc = 30f), track(20)), 0.0)
        assertEquals(0, Geo.goodPoints(track(5, acc = 30f)).size)
    }

    @Test fun possibleRepeatsByThreshold() {
        val others = mapOf("same" to track(20), "far" to track(20, lonOffsetDeg = 0.001))
        assertEquals(listOf("same"), Geo.possibleRepeats(track(20), others))
    }
}
