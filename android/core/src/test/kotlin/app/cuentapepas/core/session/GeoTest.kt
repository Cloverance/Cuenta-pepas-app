package app.cuentapepas.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private fun track(n: Int, acc: Float = 5f, lonOffsetDeg: Double = 0.0) =
        List(n) { GpsPoint(it * 1000L, 4.0 + it * 0.00001, -75.0 + lonOffsetDeg, acc) }

    /** Points 11 m apart (0.0001 deg lat); the first [matching] lie on the base line, the rest are ~111 m east. */
    private fun sparse(n: Int, matching: Int = n) =
        List(n) { GpsPoint(it * 1000L, 4.0 + it * 0.0001, -75.0 + (if (it < matching) 0.0 else 0.001), 1f) }

    @Test fun haversineOneMilliDegreeLon() {
        assertEquals(111.0, Geo.haversineM(GpsPoint(0, 4.0, -75.0, 1f), GpsPoint(0, 4.0, -75.001, 1f)), 1.0)
    }

    @Test fun selfOverlapIsOne() = assertEquals(1.0, Geo.overlapFraction(track(20), track(20))!!, 0.0)

    @Test fun shiftedTwentyMetersIsZero() {
        // 0.00018 deg of longitude at lat 4 is about 20 m; pair radius is 10 m
        assertEquals(0.0, Geo.overlapFraction(track(20, lonOffsetDeg = 0.00018), track(20))!!, 0.0)
    }

    @Test fun radiusGrowsWithAccuracyButIsCapped() {
        // 0.00009 deg lon is about 10 m: inside 5+5, outside 1+1 (min radius 3)
        assertEquals(1.0, Geo.overlapFraction(track(20, 5f, 0.00009), track(20, 5f))!!, 0.0)
        assertEquals(0.0, Geo.overlapFraction(track(20, 1f, 0.00009), track(20, 1f))!!, 0.0)
        // ~20 m stays outside even though acc 10 + 10 = 20 (capped at 15 m)
        assertEquals(0.0, Geo.overlapFraction(track(20, 10f, 0.00018), track(20, 10f))!!, 0.0)
    }

    @Test fun poorAccuracyFilteredToInsufficientGps() {
        assertNull(Geo.overlapFraction(track(20, acc = 30f), track(20)))
        assertEquals(0, Geo.goodPoints(track(5, acc = 30f)).size)
    }

    @Test fun fewerThanFivePointsIsNull() {
        assertNull(Geo.overlapFraction(track(4), track(20)))
        assertNull(Geo.overlapFraction(track(20), track(4)))
        assertEquals(1.0, Geo.overlapFraction(track(5), track(5))!!, 0.0)
    }

    @Test fun invalidPointsAreDropped() {
        val bad = listOf(
            GpsPoint(0, 0.0, 0.0, 1f),
            GpsPoint(0, Double.NaN, -75.0, 1f),
            GpsPoint(0, 4.0, Double.POSITIVE_INFINITY, 1f),
            GpsPoint(0, 91.0, -75.0, 1f),
            GpsPoint(0, 4.0, 181.0, 1f),
            GpsPoint(0, 4.0, -75.0, -1f),
            GpsPoint(0, 4.0, -75.0, Float.NaN),
        )
        assertTrue(bad.none { Geo.isValidPoint(it) })
        assertEquals(0, Geo.goodPoints(bad).size)
        assertTrue(Geo.isValidPoint(GpsPoint(0, 4.0, -75.0, 0f)))
        assertTrue(Geo.isValidPoint(GpsPoint(0, 0.0, 1.0, 1f)))
        assertNull(Geo.overlapFraction(track(20) + bad, bad + track(3)))
        assertEquals(20, Geo.goodPoints(track(20) + bad).size)
    }

    @Test fun shortSessionInsideLongOneIsNotOne() {
        val longT = sparse(100)
        val shortT = sparse(10)
        assertEquals(0.1, Geo.overlapFraction(shortT, longT)!!, 1e-12)
        assertEquals(0.1, Geo.overlapFraction(longT, shortT)!!, 1e-12)
    }

    @Test fun thresholdBoundary() {
        val others = mapOf("seventy" to sparse(100, matching = 70), "sixtynine" to sparse(100, matching = 69))
        assertEquals(0.7, Geo.overlapFraction(sparse(100), others.getValue("seventy"))!!, 1e-12)
        assertEquals(0.69, Geo.overlapFraction(sparse(100), others.getValue("sixtynine"))!!, 1e-12)
        val r = Geo.possibleRepeats(sparse(100), others)
        assertEquals(listOf("seventy"), r.repeats)
        assertTrue(r.unverifiable.isEmpty())
    }

    @Test fun possibleRepeatsSeparatesUnverifiable() {
        val others = mapOf(
            "same" to track(20),
            "far" to track(20, lonOffsetDeg = 0.001),
            "noGps" to track(20, acc = 40f),
            "short" to track(3),
        )
        val r = Geo.possibleRepeats(track(20), others)
        assertEquals(listOf("same"), r.repeats)
        assertEquals(listOf("noGps", "short"), r.unverifiable)
        assertEquals(listOf("a", "b"), Geo.possibleRepeats(track(2), mapOf("b" to track(20), "a" to track(20))).unverifiable)
    }

    @Test fun largeTracksAreFast() {
        val n = 20_000
        val a = List(n) { GpsPoint(it.toLong(), 4.0 + it * 0.000005, -75.0, 3f) }
        val b = List(n) { GpsPoint(it.toLong(), 4.0 + it * 0.000005, -75.0 + 0.00002, 3f) }
        val t0 = System.nanoTime()
        assertEquals(1.0, Geo.overlapFraction(a, b)!!, 0.0)
        assertFalse("took too long", (System.nanoTime() - t0) > 5_000_000_000L)
    }
}
