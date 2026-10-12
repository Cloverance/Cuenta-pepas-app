package app.cuentapepas.core.quality

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalMotionEstimatorTest {

    private val texW = 200
    private val texH = 120
    private val winW = 160
    private val winH = 90

    private fun noise(w: Int, h: Int, seed: Long): LumaImage {
        val d = ByteArray(w * h)
        Random(seed).nextBytes(d)
        return LumaImage(w, h, d)
    }

    private fun crop(src: LumaImage, x0: Int, y0: Int, w: Int = winW, h: Int = winH): LumaImage {
        val d = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) d[y * w + x] = src.get(x0 + x, y0 + y).toByte()
        return LumaImage(w, h, d)
    }

    private fun boxBlur(img: LumaImage, radius: Int): LumaImage {
        val out = ByteArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var sum = 0
            var n = 0
            for (yy in (y - radius)..(y + radius)) for (xx in (x - radius)..(x + radius)) {
                if (xx in 0 until img.width && yy in 0 until img.height) {
                    sum += img.get(xx, yy)
                    n++
                }
            }
            out[y * img.width + x] = (sum / n).toByte()
        }
        return LumaImage(img.width, img.height, out)
    }

    private val shifts = listOf(0 to 0, 3 to -2, -7 to 5, 12 to 0, -15 to -9)

    private fun assertRecoversShifts(texture: LumaImage, estimator: GlobalMotionEstimator) {
        for ((dx, dy) in shifts) {
            val prev = crop(texture, 20, 15)
            val curr = crop(texture, 20 - dx, 15 - dy)
            val s = estimator.estimate(prev, curr)
            assertEquals("dx for ($dx, $dy)", dx.toFloat(), s.dx, 0f)
            assertEquals("dy for ($dx, $dy)", dy.toFloat(), s.dy, 0f)
        }
    }

    @Test
    fun recoversExactShiftsOnRandomTexture() {
        assertRecoversShifts(noise(texW, texH, seed = 12345L), GlobalMotionEstimator())
    }

    @Test
    fun recoversExactShiftsOnSmoothTexture() {
        // Closer to a real camera frame: low-pass filtered, so coarse levels are informative.
        assertRecoversShifts(boxBlur(noise(texW, texH, seed = 777L), radius = 2), GlobalMotionEstimator())
    }

    @Test
    fun contentOfPrevAtXyAppearsInCurrAtXPlusDxYPlusDy() {
        val tex = noise(texW, texH, seed = 99L)
        val prev = crop(tex, 20, 15)
        val curr = crop(tex, 17, 17) // the scene moved right by 3 and up by 2 in the image
        val s = GlobalMotionEstimator().estimate(prev, curr)
        assertEquals(3f, s.dx, 0f)
        assertEquals(-2f, s.dy, 0f)
        // prev(x, y) == curr(x + dx, y + dy) over the overlap
        assertEquals(prev.get(50, 40), curr.get(53, 38))
    }

    @Test
    fun toleratesModerateSensorNoise() {
        val tex = boxBlur(noise(texW, texH, seed = 5L), radius = 1)
        val rnd = Random(1L)
        fun noisy(img: LumaImage): LumaImage {
            val d = ByteArray(img.width * img.height)
            for (i in d.indices) {
                val v = (img.data[i].toInt() and 0xFF) + rnd.nextInt(9) - 4
                d[i] = v.coerceIn(0, 255).toByte()
            }
            return LumaImage(img.width, img.height, d)
        }
        val prev = noisy(crop(tex, 20, 15))
        val curr = noisy(crop(tex, 20 - (-7), 15 - 5))
        val s = GlobalMotionEstimator().estimate(prev, curr)
        assertEquals(-7f, s.dx, 0f)
        assertEquals(5f, s.dy, 0f)
    }

    @Test
    fun identicalFlatImagesGiveZeroShift() {
        val flat = LumaImage(winW, winH, ByteArray(winW * winH) { 90 })
        val s = GlobalMotionEstimator().estimate(flat, flat)
        assertEquals(Shift.ZERO, s)
    }

    @Test
    fun independentNoiseImagesDoNotThrow() {
        val a = noise(winW, winH, seed = 1L)
        val b = noise(winW, winH, seed = 2L)
        val s = GlobalMotionEstimator().estimate(a, b)
        assertTrue(s.dx.isFinite() && s.dy.isFinite())
        // Whatever it picks must be reachable: coarse +-6 then 2x +-6 twice, i.e. at most 42.
        assertTrue(kotlin.math.abs(s.dx) <= 42f && kotlin.math.abs(s.dy) <= 42f)
    }

    @Test
    fun worksWithOtherSizesAndSettings() {
        val tiny = noise(20, 12, seed = 3L)
        GlobalMotionEstimator().estimate(tiny, noise(20, 12, seed = 4L))
        GlobalMotionEstimator(searchRadius = 0, levels = 1).estimate(tiny, tiny)
        GlobalMotionEstimator(searchRadius = 3, levels = 6).estimate(noise(160, 90, 8L), noise(160, 90, 9L))
        val tex = noise(texW, texH, seed = 42L)
        val s = GlobalMotionEstimator(searchRadius = 4, levels = 2)
            .estimate(crop(tex, 20, 15), crop(tex, 20 - 5, 15 + 3))
        assertEquals(5f, s.dx, 0f)
        assertEquals(-3f, s.dy, 0f)
    }

    @Test
    fun mismatchedSizesAreRejected() {
        val e = GlobalMotionEstimator()
        assertThrows(IllegalArgumentException::class.java) {
            e.estimate(noise(160, 90, 1L), noise(80, 45, 1L))
        }
    }

    /**
     * Rough per-frame cost of the whole quality pipeline on the JVM (downscale excluded).
     * No tight assertion: just a generous guard against accidental quadratic blow-ups.
     */
    @Test
    fun perFrameCostIsSmall() {
        val tex = noise(texW, texH, seed = 2024L)
        val prev = crop(tex, 20, 15)
        val curr = crop(tex, 25, 12)
        val estimator = GlobalMotionEstimator()
        var sink = 0.0
        repeat(300) { // warm-up so the JIT has compiled the hot loops
            sink += estimator.estimate(prev, curr).dx + QualityMetrics.sharpness(curr)
        }
        val n = 500
        var estimateNs = 0L
        var sharpnessNs = 0L
        repeat(n) {
            val t0 = System.nanoTime()
            sink += estimator.estimate(prev, curr).dx
            val t1 = System.nanoTime()
            sink += QualityMetrics.sharpness(curr)
            val t2 = System.nanoTime()
            estimateNs += t1 - t0
            sharpnessNs += t2 - t1
        }
        val estimateMs = estimateNs / n / 1e6
        val sharpnessMs = sharpnessNs / n / 1e6
        println("PERF estimate=%.3f ms  sharpness=%.3f ms per frame (sink=%.1f)".format(estimateMs, sharpnessMs, sink))
        assertTrue("estimate took $estimateMs ms", estimateMs < 25.0)
        assertTrue("sharpness took $sharpnessMs ms", sharpnessMs < 10.0)
    }
}
