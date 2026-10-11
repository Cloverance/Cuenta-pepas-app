package app.cuentapepas.core.count

import app.cuentapepas.core.geometry.Box
import app.cuentapepas.core.track.TrackSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCounterTest {
    private val box = Box(0f, 0f, 10f, 10f)

    private fun snap(
        id: Int,
        hits: Int = 3,
        score: Float = 0.9f,
        probs: FloatArray = floatArrayOf(1f, 0f, 0f),
        frame: Int = 0,
    ) = TrackSnapshot(id, box, hits, score, probs, frame)

    @Test fun trackCountsOnThirdHitAndNeverAgainWhileStillVisible() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)

        counter.observe(listOf(snap(1, hits = 1)))
        assertEquals(0, counter.sessionCounts().total)
        counter.observe(listOf(snap(1, hits = 2)))
        assertEquals(0, counter.sessionCounts().total)
        counter.observe(listOf(snap(1, hits = 3)))
        assertEquals(1, counter.sessionCounts().total)

        for (frame in 4..103) counter.observe(listOf(snap(1, hits = frame, frame = frame)))
        assertEquals(1, counter.sessionCounts().total)
        assertEquals(1, counter.plantCounts(1).total)
        assertEquals(1, counter.countedTracks().size)
    }

    @Test fun trackWithLowMeanScoreIsNotCounted() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, hits = 10, score = 0.3f)))
        assertEquals(0, counter.sessionCounts().total)
        assertTrue(counter.countedTracks().isEmpty())
    }

    @Test fun meanScoreExactlyAtThresholdCounts() {
        val counter = TrackCounter(numClasses = 3, minMeanScore = 0.40f)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, hits = 3, score = 0.40f)))
        assertEquals(1, counter.sessionCounts().total)
    }

    @Test fun trackIsCountedOnceMeanScoreRisesAboveThreshold() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, hits = 5, score = 0.2f)))
        assertEquals(0, counter.sessionCounts().total)
        counter.observe(listOf(snap(1, hits = 6, score = 0.9f)))
        assertEquals(1, counter.sessionCounts().total)
    }

    @Test fun confirmedTrackStaysWithItsPlantAfterStartPlantTwo() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1)))
        counter.startPlant(2)
        counter.observe(listOf(snap(1, hits = 4, frame = 1), snap(2)))

        assertEquals(1, counter.plantCounts(1).total)
        assertEquals(1, counter.plantCounts(2).total)
        assertEquals(1, counter.currentPlantCounts().total)
        assertEquals(2, counter.sessionCounts().total)
    }

    @Test fun finalClassFollowsLatestProbabilitiesOfCountedTrack() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        // Green (index 0) when confirmed.
        counter.observe(listOf(snap(1, probs = floatArrayOf(0.9f, 0.1f, 0f))))
        assertArrayEquals(intArrayOf(1, 0, 0), counter.plantCounts(1).perClass)

        // Turns mature before the cherry leaves the frame.
        counter.observe(listOf(snap(1, hits = 4, probs = floatArrayOf(0.1f, 0.9f, 0f), frame = 1)))
        counter.observe(listOf(snap(1, hits = 5, probs = floatArrayOf(0.2f, 0.8f, 0f), frame = 2)))
        assertArrayEquals(intArrayOf(0, 1, 0), counter.plantCounts(1).perClass)
        assertArrayEquals(intArrayOf(0, 1, 0), counter.sessionCounts().perClass)
        assertEquals(1, counter.countedTracks().single().cls)
    }

    @Test fun sessionCountsEqualSumOfPlantCounts() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, probs = floatArrayOf(1f, 0f, 0f)), snap(2, probs = floatArrayOf(0f, 1f, 0f))))
        counter.startPlant(2)
        counter.observe(listOf(snap(3, probs = floatArrayOf(0f, 0f, 1f)), snap(1, hits = 4, frame = 1)))

        assertEquals(counter.plantCounts(1) + counter.plantCounts(2), counter.sessionCounts())
        assertArrayEquals(intArrayOf(1, 1, 1), counter.sessionCounts().perClass)
        assertArrayEquals(intArrayOf(0, 0, 1), counter.plantCounts(2).perClass)
    }

    @Test(expected = IllegalStateException::class)
    fun observeBeforeStartPlantThrows() {
        TrackCounter(numClasses = 3).observe(listOf(snap(1)))
    }

    @Test fun currentPlantCountsIsZeroBeforeAnyPlantStarted() {
        assertEquals(ClassCounts.zero(3), TrackCounter(numClasses = 3).currentPlantCounts())
    }

    @Test fun confirmedIdIsNeverCountedAgainWhenItReappears() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(7)))
        counter.observe(emptyList())
        counter.observe(emptyList())

        counter.startPlant(2)
        counter.observe(listOf(snap(7, hits = 20, frame = 30)))

        assertEquals(1, counter.sessionCounts().total)
        assertEquals(1, counter.plantCounts(1).total)
        assertEquals(0, counter.plantCounts(2).total)
        assertEquals(1, counter.countedTracks().size)
    }

    @Test fun tiesGoToLowestClassIndex() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, probs = floatArrayOf(0.5f, 0.5f, 0f))))
        counter.observe(listOf(snap(2, probs = floatArrayOf(0f, 0.5f, 0.5f))))
        counter.observe(listOf(snap(3, probs = floatArrayOf(0.4f, 0.4f, 0.2f))))
        assertArrayEquals(intArrayOf(2, 1, 0), counter.sessionCounts().perClass)
    }

    @Test fun countedTracksReportIdPlantClassAndProbabilities() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(4)
        val probs = floatArrayOf(0.1f, 0.2f, 0.7f)
        counter.observe(listOf(snap(9, probs = probs)))

        val counted = counter.countedTracks().single()
        assertEquals(9, counted.id)
        assertEquals(4, counted.plantNumber)
        assertEquals(2, counted.cls)
        assertArrayEquals(probs, counted.classProbs, 0f)
    }

    @Test fun unconfirmedTracksAreNotListed() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, hits = 1), snap(2, score = 0.1f, hits = 9)))
        assertTrue(counter.countedTracks().isEmpty())
    }

    @Test fun plantNumbersListsStartedPlantsInOrder() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(3)
        counter.startPlant(1)
        counter.startPlant(3)
        assertEquals(listOf(3, 1), counter.plantNumbers())
    }

    @Test fun plantCountsForUnknownPlantAreZero() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        assertEquals(ClassCounts.zero(3), counter.plantCounts(99))
    }

    @Test(expected = IllegalArgumentException::class)
    fun probabilityVectorOfWrongSizeIsRejected() {
        val counter = TrackCounter(numClasses = 3)
        counter.startPlant(1)
        counter.observe(listOf(snap(1, probs = floatArrayOf(1f, 0f))))
    }
}
