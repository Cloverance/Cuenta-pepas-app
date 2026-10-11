package app.cuentapepas.core.session

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of [Geo.possibleRepeats]: sessions that probably repeat a pass, and sessions that could not be checked. */
data class RepeatCheck(val repeats: List<String>, val unverifiable: List<String>)

/**
 * GPS cannot identify plants or neighbouring rows (accuracy exceeds row spacing); it is only used to warn about
 * a possible repeated pass. Invalid or inaccurate points are discarded, never corrected.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Minimum number of good points a track needs for an overlap to mean anything. */
    const val MIN_GOOD_POINTS = 5

    fun haversineM(a: GpsPoint, b: GpsPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Finite lat/lon within range, not the (0, 0) "no fix" placeholder, finite non-negative accuracy. */
    fun isValidPoint(p: GpsPoint): Boolean =
        p.lat.isFinite() && p.lon.isFinite() &&
            p.lat in -90.0..90.0 && p.lon in -180.0..180.0 &&
            !(p.lat == 0.0 && p.lon == 0.0) &&
            p.accuracyM.isFinite() && p.accuracyM >= 0f

    /** Valid points whose accuracy is at most [maxAccuracyM]. */
    fun goodPoints(points: List<GpsPoint>, maxAccuracyM: Float = 10f): List<GpsPoint> =
        points.filter { isValidPoint(it) && it.accuracyM <= maxAccuracyM }

    /**
     * Symmetric overlap of two tracks: min(fraction of new points near the old track, fraction of old points near the
     * new track), so a short session inside a long one does not score 1.0. Two points match when closer than
     * max([radiusMinM], accNew + accOld), capped at [radiusMaxM]. Returns null ("GPS insuficiente") when either track
     * has fewer than [MIN_GOOD_POINTS] good points. Expected O(n) (grid buckets).
     */
    fun overlapFraction(
        newTrack: List<GpsPoint>,
        oldTrack: List<GpsPoint>,
        radiusMinM: Double = 3.0,
        maxAccuracyM: Float = 10f,
        radiusMaxM: Double = 15.0,
    ): Double? {
        require(radiusMinM > 0.0 && radiusMaxM >= radiusMinM) { "invalid radii: $radiusMinM..$radiusMaxM" }
        val n = goodPoints(newTrack, maxAccuracyM)
        val o = goodPoints(oldTrack, maxAccuracyM)
        if (n.size < MIN_GOOD_POINTS || o.size < MIN_GOOD_POINTS) return null
        val lat0 = Math.toRadians(n[0].lat)
        val lon0 = Math.toRadians(n[0].lon)
        val cos0 = cos(lat0)
        val a = Planar(n, lat0, lon0, cos0)
        val b = Planar(o, lat0, lon0, cos0)
        return min(coveredFraction(a, b, radiusMinM, radiusMaxM), coveredFraction(b, a, radiusMinM, radiusMaxM))
    }

    /**
     * Sessions in [others] whose symmetric overlap with [newTrack] is at least [threshold] go to `repeats`;
     * those that cannot be assessed (GPS insufficient) go to `unverifiable`. Both are sorted by id.
     */
    fun possibleRepeats(
        newTrack: List<GpsPoint>,
        others: Map<String, List<GpsPoint>>,
        threshold: Double = 0.7,
        radiusMinM: Double = 3.0,
        maxAccuracyM: Float = 10f,
    ): RepeatCheck {
        val repeats = ArrayList<String>()
        val unverifiable = ArrayList<String>()
        for ((id, t) in others) {
            val f = overlapFraction(newTrack, t, radiusMinM, maxAccuracyM)
            if (f == null) unverifiable += id else if (f >= threshold) repeats += id
        }
        return RepeatCheck(repeats.sorted(), unverifiable.sorted())
    }

    /** Points projected (equirectangular, shared origin) to local metres. Computed once per track. */
    private class Planar(points: List<GpsPoint>, lat0: Double, lon0: Double, cos0: Double) {
        val size = points.size
        val x = DoubleArray(size)
        val y = DoubleArray(size)
        val acc = DoubleArray(size)

        init {
            for (i in 0 until size) {
                val p = points[i]
                var dl = Math.toRadians(p.lon) - lon0
                if (dl > PI) dl -= 2 * PI else if (dl < -PI) dl += 2 * PI
                x[i] = EARTH_RADIUS_M * dl * cos0
                y[i] = EARTH_RADIUS_M * (Math.toRadians(p.lat) - lat0)
                acc[i] = p.accuracyM.toDouble()
            }
        }
    }

    private fun cell(v: Double, size: Double): Long = floor(v / size).toLong()

    private fun cellKey(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)

    /** Fraction of [a] points that have some [b] point within their pair radius. */
    private fun coveredFraction(a: Planar, b: Planar, radiusMinM: Double, radiusMaxM: Double): Double {
        val grid = HashMap<Long, MutableList<Int>>()
        for (j in 0 until b.size) {
            grid.getOrPut(cellKey(cell(b.x[j], radiusMaxM), cell(b.y[j], radiusMaxM))) { ArrayList(2) }.add(j)
        }
        var covered = 0
        for (i in 0 until a.size) {
            val cx = cell(a.x[i], radiusMaxM)
            val cy = cell(a.y[i], radiusMaxM)
            var hit = false
            search@ for (dx in -1L..1L) for (dy in -1L..1L) {
                val bucket = grid[cellKey(cx + dx, cy + dy)] ?: continue
                for (j in bucket) {
                    val r = min(radiusMaxM, max(radiusMinM, a.acc[i] + b.acc[j]))
                    val ddx = a.x[i] - b.x[j]
                    val ddy = a.y[i] - b.y[j]
                    if (ddx * ddx + ddy * ddy <= r * r) {
                        hit = true
                        break@search
                    }
                }
            }
            if (hit) covered++
        }
        return covered.toDouble() / a.size
    }
}
