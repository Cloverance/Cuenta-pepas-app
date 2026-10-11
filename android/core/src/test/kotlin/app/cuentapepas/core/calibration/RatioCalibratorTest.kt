package app.cuentapepas.core.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatioCalibratorTest {
    private val synthetic = SyntheticSamples.generate(n = 30, seed = 42L)

    @Test fun syntheticCalibrationRecoversTheFactor() {
        val r = RatioCalibrator.fit(synthetic)
        println("synthetic calibration: $r")
        assertTrue(r.usable)
        assertNull(r.reason)
        assertEquals(30, r.n)
        val k = r.k!!
        assertTrue("k = $k", k in 2.8..3.2)
        assertTrue("kCi = ${r.kCi}", r.kCi!!.contains(3.0))
        assertTrue("kCi = ${r.kCi}", r.kCi!!.contains(k))
    }

    @Test fun plantFactorIntervalIsWiderThanTheCiOfK() {
        val r = RatioCalibrator.fit(synthetic)
        assertTrue(
            "plant ${r.plantFactorInterval} vs k ${r.kCi}",
            r.plantFactorInterval!!.width > r.kCi!!.width,
        )
    }

    @Test fun loocvErrorIsPlausibleForSigma015() {
        val loocv = RatioCalibrator.fit(synthetic).loocvMeanAbsPctError!!
        assertTrue("LOOCV = $loocv", loocv in 0.05..0.25)
    }

    @Test fun nineSamplesAreNotUsable() {
        val r = RatioCalibrator.fit(synthetic.take(9))
        assertFalse(r.usable)
        assertEquals(9, r.n)
        assertEquals("Se necesitan al menos 10 plantas de muestra", r.reason)
        assertNull(r.k); assertNull(r.kCi); assertNull(r.plantFactorInterval); assertNull(r.loocvMeanAbsPctError)
    }

    @Test fun reasonFollowsMinSamples() {
        val r = RatioCalibrator.fit(synthetic.take(9), minSamples = 12)
        assertEquals("Se necesitan al menos 12 plantas de muestra", r.reason)
    }

    @Test fun noVisibleCherriesIsNotUsable() {
        val samples = List(12) { CalibrationSample("p$it", visible = 0, manual = 40) }
        val r = RatioCalibrator.fit(samples)
        assertFalse(r.usable)
        assertEquals("Las plantas de muestra no tienen pepas visibles; no se puede calibrar", r.reason)
        assertNull(r.k); assertNull(r.kCi)
    }

    @Test fun zeroManualTotalIsNotUsable() {
        val samples = List(12) { CalibrationSample("p$it", visible = 30, manual = 0) }
        val r = RatioCalibrator.fit(samples)
        assertFalse(r.usable)
        assertEquals("El conteo manual de las plantas de muestra suma 0; no se puede calibrar", r.reason)
        assertNull(r.k)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeManualThrows() {
        RatioCalibrator.fit(synthetic + CalibrationSample("bad", visible = 10, manual = -1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeVisibleThrows() {
        RatioCalibrator.fit(synthetic + CalibrationSample("bad", visible = -3, manual = 10))
    }

    @Test fun pointEstimateAndLoocvByHand() {
        // Nine plants (v=10, m=30) and one (v=10, m=40): k = 310/100 = 3.1.
        // LOOCV: for m=30, k_-i = 280/90 -> |280/9 - 30|/30 = 1/27; for m=40, k_-i = 3 -> |30-40|/40 = 0.25.
        // A (v=0, m=0) plant changes no sum and is excluded from LOOCV (m = 0), so nothing turns into NaN.
        val samples = List(9) { CalibrationSample("a$it", 10, 30) } +
            CalibrationSample("b", 10, 40) + CalibrationSample("z", 0, 0)
        val r = RatioCalibrator.fit(samples)
        assertTrue(r.usable)
        assertEquals(11, r.n)
        assertEquals(3.1, r.k!!, 1e-12)
        assertEquals((9.0 / 27.0 + 0.25) / 10.0, r.loocvMeanAbsPctError!!, 1e-12)
    }

    @Test fun replicatesWithoutVisibleCherriesAreSkippedNotNaN() {
        // 9 of 10 plants have no visible cherries: about 0.9^10 = 35 % of the resamples have Σvisible* = 0.
        val samples = List(9) { CalibrationSample("z$it", visible = 0, manual = 5) } + CalibrationSample("v", 20, 60)
        val r = RatioCalibrator.fit(samples)
        assertTrue(r.usable)
        assertEquals(5.25, r.k!!, 1e-12)
        assertTrue("skipped = ${r.skippedReplicates}", r.skippedReplicates in 400..1100)
        for (x in listOf(r.kCi!!.low, r.kCi!!.high, r.plantFactorInterval!!.low, r.plantFactorInterval!!.high)) {
            assertTrue("bound $x", x.isFinite())
        }
        // LOOCV: leaving out the only visible plant leaves Σvisible = 0, so that term is excluded;
        // each of the other 9 gives k_-i = 100/20 = 5 and error |5·0 − 5| / 5 = 1.
        assertEquals(1.0, r.loocvMeanAbsPctError!!, 1e-12)
    }

    @Test fun noReplicateIsSkippedWhenEveryPlantHasVisibleCherries() {
        assertEquals(0, RatioCalibrator.fit(synthetic).skippedReplicates)
    }

    @Test fun sameSeedGivesSameResult() {
        assertEquals(RatioCalibrator.fit(synthetic, seed = 7L), RatioCalibrator.fit(synthetic, seed = 7L))
        assertEquals(RatioCalibrator.fit(synthetic), RatioCalibrator.fit(synthetic))
        assertNotEquals(RatioCalibrator.fit(synthetic, seed = 7L).kCi, RatioCalibrator.fit(synthetic, seed = 8L).kCi)
    }

    @Test fun ciCoverageIsCloseToNominal() {
        // 200 synthetic calibrations of 15 plants each; the 90 % percentile CI of k should contain the true k ≈ 90 %.
        val sims = 200
        var hits = 0
        val start = System.nanoTime()
        for (s in 0 until sims) {
            val samples = SyntheticSamples.generate(n = 15, seed = 10_000L + s)
            val r = RatioCalibrator.fit(samples, seed = 500L + s)
            assertNotNull(r.kCi)
            if (r.kCi!!.contains(3.0)) hits++
        }
        val coverage = hits.toDouble() / sims
        val ms = (System.nanoTime() - start) / 1_000_000
        println("kCi coverage over $sims simulations: $coverage ($ms ms)")
        assertTrue("coverage = $coverage", coverage in 0.80..0.97)
    }
}
