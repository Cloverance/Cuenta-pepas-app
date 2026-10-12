package app.cuentapepas.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityAdvisorTest {

    private val good = QualitySample(
        sharpness = 100.0,
        meanBrightness = 120.0,
        brightFraction = 0.0,
        frameWidthsPerSecond = 0.3,
    )
    private val blurry = good.copy(sharpness = 5.0)

    @Test
    fun defaultThresholdsMatchTheSpecification() {
        val t = QualityThresholds()
        assertEquals(25.0, t.minSharpness, 0.0)
        assertEquals(45.0, t.minBrightness, 0.0)
        assertEquals(215.0, t.maxBrightness, 0.0)
        assertEquals(0.25, t.maxBrightFraction, 0.0)
        assertEquals(1.0, t.maxFrameWidthsPerSecond, 0.0)
        assertEquals(5, t.onFrames)
        assertEquals(10, t.offFrames)
    }

    @Test
    fun goodFramesNeverWarn() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(50) { assertTrue(advisor.update(good).isEmpty()) }
    }

    @Test
    fun fourBlurryFramesDoNotWarnAndTheFifthDoes() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(4) { assertTrue("frame ${it + 1}", advisor.update(blurry).isEmpty()) }
        assertEquals(setOf(QualityWarning.BLURRY), advisor.update(blurry))
    }

    @Test
    fun tenSharpFramesAreNeededToClearTheWarning() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(5) { advisor.update(blurry) }
        repeat(9) {
            assertEquals("sharp frame ${it + 1}", setOf(QualityWarning.BLURRY), advisor.update(good))
        }
        assertTrue(advisor.update(good).isEmpty())
        assertTrue(advisor.update(good).isEmpty())
    }

    @Test
    fun anOkFrameInterruptsTheOnStreak() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(4) { advisor.update(blurry) }
        advisor.update(good)
        repeat(4) { assertTrue(advisor.update(blurry).isEmpty()) }
        assertEquals(setOf(QualityWarning.BLURRY), advisor.update(blurry))
    }

    @Test
    fun aFaultyFrameInterruptsTheOffStreak() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(5) { advisor.update(blurry) }
        repeat(9) { advisor.update(good) }
        advisor.update(blurry)
        repeat(9) { assertEquals(setOf(QualityWarning.BLURRY), advisor.update(good)) }
        assertTrue(advisor.update(good).isEmpty())
    }

    @Test
    fun severalWarningsCanBeActiveAtOnce() {
        val advisor = QualityAdvisor(QualityThresholds())
        val bad = QualitySample(
            sharpness = 3.0,
            meanBrightness = 20.0,
            brightFraction = 0.0,
            frameWidthsPerSecond = 2.5,
        )
        repeat(4) { assertTrue(advisor.update(bad).isEmpty()) }
        assertEquals(
            setOf(QualityWarning.TOO_FAST, QualityWarning.BLURRY, QualityWarning.TOO_DARK),
            advisor.update(bad),
        )
    }

    @Test
    fun warningsAreTrackedIndependently() {
        val advisor = QualityAdvisor(QualityThresholds())
        val fastAndBlurry = good.copy(sharpness = 1.0, frameWidthsPerSecond = 3.0)
        val fastOnly = good.copy(frameWidthsPerSecond = 3.0)
        repeat(5) { advisor.update(fastAndBlurry) }
        // Blur clears after 10 frames, while speed remains faulty throughout.
        repeat(9) {
            assertEquals(
                setOf(QualityWarning.TOO_FAST, QualityWarning.BLURRY),
                advisor.update(fastOnly),
            )
        }
        assertEquals(setOf(QualityWarning.TOO_FAST), advisor.update(fastOnly))
    }

    @Test
    fun tooFastTooDarkAndBacklightFireOnTheirOwn() {
        fun firesOnly(sample: QualitySample, warning: QualityWarning) {
            val advisor = QualityAdvisor(QualityThresholds())
            var last: Set<QualityWarning> = emptySet()
            repeat(5) { last = advisor.update(sample) }
            assertEquals(setOf(warning), last)
        }
        firesOnly(good.copy(frameWidthsPerSecond = 1.5), QualityWarning.TOO_FAST)
        firesOnly(good.copy(meanBrightness = 30.0), QualityWarning.TOO_DARK)
        firesOnly(good.copy(meanBrightness = 230.0), QualityWarning.BACKLIGHT)
        firesOnly(good.copy(brightFraction = 0.4), QualityWarning.BACKLIGHT)
    }

    @Test
    fun valuesExactlyOnTheThresholdAreAcceptable() {
        val advisor = QualityAdvisor(QualityThresholds())
        val edge = QualitySample(
            sharpness = 25.0,
            meanBrightness = 45.0,
            brightFraction = 0.25,
            frameWidthsPerSecond = 1.0,
        )
        repeat(20) { assertTrue(advisor.update(edge).isEmpty()) }
        val edgeHigh = edge.copy(meanBrightness = 215.0)
        repeat(20) { assertTrue(advisor.update(edgeHigh).isEmpty()) }
    }

    @Test
    fun customFrameCountsAreHonoured() {
        val advisor = QualityAdvisor(QualityThresholds(onFrames = 2, offFrames = 3))
        assertTrue(advisor.update(blurry).isEmpty())
        assertEquals(setOf(QualityWarning.BLURRY), advisor.update(blurry))
        assertEquals(setOf(QualityWarning.BLURRY), advisor.update(good))
        assertEquals(setOf(QualityWarning.BLURRY), advisor.update(good))
        assertTrue(advisor.update(good).isEmpty())
    }

    @Test
    fun resetClearsStateAndStreaks() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(5) { advisor.update(blurry) }
        advisor.reset()
        repeat(4) { assertTrue(advisor.update(blurry).isEmpty()) }
        assertEquals(setOf(QualityWarning.BLURRY), advisor.update(blurry))
    }

    @Test
    fun returnedSetIsASnapshot() {
        val advisor = QualityAdvisor(QualityThresholds())
        repeat(5) { advisor.update(blurry) }
        val snapshot = advisor.update(blurry)
        repeat(10) { advisor.update(good) }
        assertEquals(setOf(QualityWarning.BLURRY), snapshot)
    }

    @Test
    fun rejectsNonPositiveFrameCounts() {
        assertThrows(IllegalArgumentException::class.java) { QualityThresholds(onFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { QualityThresholds(offFrames = 0) }
    }

    @Test
    fun nonFiniteValuesCountAsViolations() {
        fun warningsAfter5(sample: QualitySample): Set<QualityWarning> {
            val advisor = QualityAdvisor(QualityThresholds())
            var last: Set<QualityWarning> = emptySet()
            repeat(5) { last = advisor.update(sample) }
            return last
        }
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("sharpness $v", setOf(QualityWarning.BLURRY), warningsAfter5(good.copy(sharpness = v)))
            assertEquals("speed $v", setOf(QualityWarning.TOO_FAST), warningsAfter5(good.copy(frameWidthsPerSecond = v)))
            assertEquals("fraction $v", setOf(QualityWarning.BACKLIGHT), warningsAfter5(good.copy(brightFraction = v)))
            assertEquals(
                "brightness $v",
                setOf(QualityWarning.TOO_DARK, QualityWarning.BACKLIGHT),
                warningsAfter5(good.copy(meanBrightness = v)),
            )
        }
    }
}
