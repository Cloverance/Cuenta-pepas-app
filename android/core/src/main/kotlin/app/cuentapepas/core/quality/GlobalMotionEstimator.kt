package app.cuentapepas.core.quality

import kotlin.math.abs

/**
 * Estimates the global translation between two consecutive luma frames (camera motion) with a
 * coarse-to-fine block search that minimises the mean absolute difference over the overlap.
 *
 * Each pyramid level averages 2x2 of the previous one. The coarsest level is searched within
 * +-[searchRadius] of zero; each finer level within +-[searchRadius] of twice the previous
 * estimate. A candidate shift is only considered if the two images overlap by at least 50 %.
 *
 * Instances hold no mutable state, so one can be shared between threads. Each call allocates only
 * the few small pyramid levels (a few KB for 160x90), never anything per pixel.
 */
class GlobalMotionEstimator(val searchRadius: Int = 6, val levels: Int = 3) {

    init {
        require(searchRadius >= 0) { "searchRadius must be >= 0, got $searchRadius" }
        require(levels >= 1) { "levels must be >= 1, got $levels" }
    }

    /**
     * Returns the shift in pixels of the given images such that the content of [prev] at (x, y)
     * appears in [curr] at (x + dx, y + dy). For flat or featureless images, where every shift
     * matches equally well, the candidate closest to the prediction (ultimately zero) wins.
     */
    fun estimate(prev: LumaImage, curr: LumaImage): Shift {
        require(prev.width == curr.width && prev.height == curr.height) {
            "Frame sizes differ: ${prev.width}x${prev.height} vs ${curr.width}x${curr.height}"
        }
        val pyramidPrev = pyramid(prev)
        val pyramidCurr = pyramid(curr)
        var ex = 0
        var ey = 0
        val best = IntArray(2)
        for (level in pyramidPrev.indices.reversed()) {
            val isCoarsest = level == pyramidPrev.lastIndex
            val cx = if (isCoarsest) 0 else ex * 2
            val cy = if (isCoarsest) 0 else ey * 2
            search(pyramidPrev[level], pyramidCurr[level], cx, cy, best)
            ex = best[0]
            ey = best[1]
        }
        return Shift(ex.toFloat(), ey.toFloat())
    }

    private fun pyramid(base: LumaImage): Array<LumaImage> {
        var count = 1
        var w = base.width
        var h = base.height
        while (count < levels && w / 2 >= MIN_LEVEL_SIZE && h / 2 >= MIN_LEVEL_SIZE) {
            w /= 2
            h /= 2
            count++
        }
        val out = arrayOfNulls<LumaImage>(count)
        out[0] = base
        for (i in 1 until count) out[i] = halve(out[i - 1]!!)
        @Suppress("UNCHECKED_CAST")
        return out as Array<LumaImage>
    }

    private fun halve(img: LumaImage): LumaImage {
        val w = img.width / 2
        val h = img.height / 2
        val src = img.data
        val sw = img.width
        val out = ByteArray(w * h)
        var o = 0
        for (y in 0 until h) {
            var i = 2 * y * sw
            for (x in 0 until w) {
                val sum = (src[i].toInt() and 0xFF) + (src[i + 1].toInt() and 0xFF) +
                    (src[i + sw].toInt() and 0xFF) + (src[i + sw + 1].toInt() and 0xFF)
                out[o++] = ((sum + 2) shr 2).toByte()
                i += 2
            }
        }
        return LumaImage(w, h, out)
    }

    /** Searches +-[searchRadius] around ([cx], [cy]) and writes the winner into [result]. */
    private fun search(prev: LumaImage, curr: LumaImage, cx: Int, cy: Int, result: IntArray) {
        val w = prev.width
        val h = prev.height
        var bestCost = Double.POSITIVE_INFINITY
        var bestDist = Int.MAX_VALUE
        var bestX = cx
        var bestY = cy
        // The centre goes first so that the early exit in sad() has a good bound from the start.
        for (k in -1 until (2 * searchRadius + 1) * (2 * searchRadius + 1)) {
            val dx: Int
            val dy: Int
            if (k < 0) {
                dx = cx
                dy = cy
            } else {
                dx = cx - searchRadius + k % (2 * searchRadius + 1)
                dy = cy - searchRadius + k / (2 * searchRadius + 1)
                if (dx == cx && dy == cy) continue
            }
            val overlapW = w - abs(dx)
            val overlapH = h - abs(dy)
            if (overlapW <= 0 || overlapH <= 0) continue
            if (overlapW.toLong() * overlapH * 2 < w.toLong() * h) continue // < 50 % overlap
            val cost = meanAbsDiff(prev, curr, dx, dy, overlapW, overlapH, bestCost)
            val dist = (dx - cx) * (dx - cx) + (dy - cy) * (dy - cy)
            if (cost < bestCost || (cost == bestCost && dist < bestDist)) {
                bestCost = cost
                bestDist = dist
                bestX = dx
                bestY = dy
            }
        }
        result[0] = bestX
        result[1] = bestY
    }

    /**
     * Mean absolute difference between prev(x, y) and curr(x + dx, y + dy) over the overlap.
     * Gives up and returns +infinity as soon as the running sum proves the result exceeds [bound].
     */
    private fun meanAbsDiff(
        prev: LumaImage,
        curr: LumaImage,
        dx: Int,
        dy: Int,
        overlapW: Int,
        overlapH: Int,
        bound: Double,
    ): Double {
        val w = prev.width
        val p = prev.data
        val c = curr.data
        val x0 = if (dx < 0) -dx else 0
        val y0 = if (dy < 0) -dy else 0
        val area = overlapW * overlapH
        val maxSum = bound * area
        var sum = 0L // Long: 255 * area overflows Int beyond ~8.4 Mpx
        for (y in y0 until y0 + overlapH) {
            var pi = y * w + x0
            var ci = (y + dy) * w + x0 + dx
            for (x in 0 until overlapW) {
                val diff = (p[pi].toInt() and 0xFF) - (c[ci].toInt() and 0xFF)
                sum += if (diff < 0) -diff else diff // Int widened to Long
                pi++
                ci++
            }
            if (sum > maxSum) return Double.POSITIVE_INFINITY
        }
        return sum.toDouble() / area
    }

    private companion object {
        /** Pyramid levels stop shrinking once a side would drop below this. */
        const val MIN_LEVEL_SIZE = 16
    }
}
