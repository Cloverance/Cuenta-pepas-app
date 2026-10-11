package app.cuentapepas.core.session

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS cannot identify plants or neighbouring rows (accuracy exceeds row spacing); it is only used to warn about
 * a possible repeated pass. Points with poor accuracy are discarded, never corrected.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(a: GpsPoint, b: GpsPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun goodPoints(points: List<GpsPoint>, maxAccuracyM: Float = 10f): List<GpsPoint> =
        points.filter { it.accuracyM <= maxAccuracyM }

    /** Fraction of good points of [newTrack] within [radiusM] of some good point of [oldTrack]; 0.0 if no good points. */
    fun overlapFraction(
        newTrack: List<GpsPoint>,
        oldTrack: List<GpsPoint>,
        radiusM: Double = 3.0,
        maxAccuracyM: Float = 10f,
    ): Double {
        val n = goodPoints(newTrack, maxAccuracyM)
        val o = goodPoints(oldTrack, maxAccuracyM)
        if (n.isEmpty() || o.isEmpty()) return 0.0
        return n.count { p -> o.any { haversineM(p, it) <= radiusM } }.toDouble() / n.size
    }

    /** Ids of sessions whose track overlaps [newTrack] by at least [threshold]. */
    fun possibleRepeats(
        newTrack: List<GpsPoint>,
        others: Map<String, List<GpsPoint>>,
        threshold: Double = 0.7,
        radiusM: Double = 3.0,
        maxAccuracyM: Float = 10f,
    ): List<String> =
        others.filter { (_, t) -> overlapFraction(newTrack, t, radiusM, maxAccuracyM) >= threshold }.keys.sorted()
}
