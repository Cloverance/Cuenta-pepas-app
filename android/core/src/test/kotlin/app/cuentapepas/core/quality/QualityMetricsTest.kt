package app.cuentapepas.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityMetricsTest {

    private fun checkerboard(w: Int, h: Int, cell: Int, lo: Int = 0, hi: Int = 255): LumaImage {
        val d = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            d[y * w + x] = (if (((x / cell) + (y / cell)) % 2 == 0) lo else hi).toByte()
        }
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

    private fun filled(w: Int, h: Int, v: Int) = LumaImage(w, h, ByteArray(w * h) { v.toByte() })

    @Test
    fun constantImageHasZeroSharpness() {
        assertEquals(0.0, QualityMetrics.sharpness(filled(160, 90, 128)), 0.0)
    }

    @Test
    fun checkerboardIsMuchSharperThanItsBoxBlur() {
        val sharp = checkerboard(160, 90, cell = 2)
        val blurred = boxBlur(sharp, radius = 2) // 5x5 box
        val s = QualityMetrics.sharpness(sharp)
        val b = QualityMetrics.sharpness(blurred)
        assertTrue("sharp=$s blurred=$b", s > 25.0)
        assertTrue("sharp=$s blurred=$b", s > 20.0 * b)
    }

    @Test
    fun sharpnessIsTheVarianceOfTheFourNeighbourLaplacianOverTheInterior() {
        // One bright pixel in the middle of a 5x5 image: the interior is 3x3 and the
        // laplacian there is 400 at the centre, -100 at its four neighbours and 0 at the corners.
        val d = ByteArray(25)
        d[2 * 5 + 2] = 100
        val img = LumaImage(5, 5, d)
        // mean = 0, E[x^2] = (400^2 + 4 * 100^2) / 9
        assertEquals(200_000.0 / 9.0, QualityMetrics.sharpness(img), 1e-6)
    }

    @Test
    fun sharpnessIgnoresAUniformBrightnessOffset() {
        val a = checkerboard(64, 36, cell = 1, lo = 10, hi = 60)
        val b = checkerboard(64, 36, cell = 1, lo = 110, hi = 160)
        assertEquals(QualityMetrics.sharpness(a), QualityMetrics.sharpness(b), 1e-9)
    }

    @Test
    fun imagesSmallerThanTheKernelHaveZeroSharpness() {
        assertEquals(0.0, QualityMetrics.sharpness(filled(2, 2, 50)), 0.0)
        assertEquals(0.0, QualityMetrics.sharpness(filled(5, 2, 50)), 0.0)
    }

    @Test
    fun halfBlackHalfWhiteBrightness() {
        val w = 160
        val h = 90
        val d = ByteArray(w * h)
        for (y in 0 until h) for (x in w / 2 until w) d[y * w + x] = 255.toByte()
        val b = QualityMetrics.brightness(LumaImage(w, h, d))
        assertEquals(127.5, b.mean, 1e-9)
        assertEquals(0.5, b.darkFraction, 1e-9)
        assertEquals(0.5, b.brightFraction, 1e-9)
    }

    @Test
    fun darkAndBrightThresholdsAreStrict() {
        // dark is < 20, bright is > 235.
        val values = intArrayOf(19, 20, 235, 236)
        val img = LumaImage(4, 1, ByteArray(4) { values[it].toByte() })
        val b = QualityMetrics.brightness(img)
        assertEquals(0.25, b.darkFraction, 1e-9)
        assertEquals(0.25, b.brightFraction, 1e-9)
        assertEquals((19 + 20 + 235 + 236) / 4.0, b.mean, 1e-9)
    }

    @Test
    fun midGrayIsNeitherDarkNorBright() {
        val b = QualityMetrics.brightness(filled(32, 18, 128))
        assertEquals(128.0, b.mean, 1e-9)
        assertEquals(0.0, b.darkFraction, 0.0)
        assertEquals(0.0, b.brightFraction, 0.0)
    }
}
