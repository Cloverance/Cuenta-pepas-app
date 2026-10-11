package app.cuentapepas.core.track

import app.cuentapepas.core.detect.Detection
import app.cuentapepas.core.geometry.Box
import app.cuentapepas.core.quality.Shift
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackerTest {

    private val probs = floatArrayOf(1f, 0f, 0f, 0f, 0f)

    private fun det(cx: Float, cy: Float, size: Float, score: Float = 0.9f, p: FloatArray = probs) =
        Detection(Box(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), score, 0, p)

    /** Runs [frames] frames; [detsAt] builds each frame's detections. */
    private fun run(
        frames: Int,
        shift: (Int) -> Shift = { Shift.ZERO },
        detsAt: (Int) -> List<Detection>,
    ): List<List<TrackSnapshot>> {
        val tracker = ByteTracker()
        return (0 until frames).map { tracker.update(detsAt(it), shift(it)) }
    }

    @Test
    fun objectsMovingLinearlyKeepIds() {
        val out = run(40) { f -> (0 until 5).map { k -> det(50f + 30f * k + 4f * f, 100f, 12f) } }
        assertEquals(5, out.flatten().map { it.id }.toSet().size)
        for (k in 0 until 5) {
            // The id owning the k-th object must be the same from frame 2 on (index 1 = second frame).
            val idsOfObject = out.withIndex().drop(1).map { (f, snaps) ->
                snaps.single { abs(it.box.cx - (50f + 30f * k + 4f * f)) < 3f }.id
            }.toSet()
            assertEquals("object $k", 1, idsOfObject.size)
        }
        out.forEach { assertEquals(5, it.size) }
    }

    private fun occlusionIds(gap: IntRange, frames: Int): Set<Int> {
        val out = run(frames) { f ->
            if (f in gap) emptyList() else listOf(det(100f + 4f * f, 100f, 12f))
        }
        return out.flatten().map { it.id }.toSet()
    }

    @Test
    fun occlusionShorterThanBufferKeepsId() {
        assertEquals(setOf(1), occlusionIds(10..20, 40))
    }

    @Test
    fun occlusionLongerThanBufferGetsNewId() {
        assertEquals(setOf(1, 2), occlusionIds(10..49, 70))
    }

    private fun cameraShiftIds(compensate: Boolean): Set<Int> {
        // Static world seen by a camera panning 15 px per frame: detections slide 15 px per frame.
        val out = run(40, shift = { if (compensate) Shift(15f, 0f) else Shift.ZERO }) { f ->
            (0 until 8).map { k -> det(30f + 15f * k + 15f * f, 100f, 10f) }
        }
        return out.flatten().map { it.id }.toSet()
    }

    @Test
    fun cameraShiftCompensated() {
        assertEquals(8, cameraShiftIds(compensate = true).size)
        val idsWithout = cameraShiftIds(compensate = false).size
        assertTrue("without shift: $idsWithout ids", idsWithout > 8)
    }

    @Test
    fun cameraShiftIsAppliedToUnconfirmedTracks() {
        val tracker = ByteTracker()
        tracker.update(emptyList())
        tracker.update(listOf(det(100f, 100f, 10f))) // frame 2: unconfirmed
        // Frame 3: the object slid 15 px with the camera; only a shifted unconfirmed track can confirm.
        val out = tracker.update(listOf(det(115f, 100f, 10f)), Shift(15f, 0f))
        assertEquals(1, out.single().id)
    }

    @Test
    fun cameraShiftIsAppliedToLostTracks() {
        val tracker = ByteTracker()
        tracker.update(listOf(det(100f, 100f, 10f)))
        tracker.update(listOf(det(100f, 100f, 10f)))
        tracker.update(emptyList()) // lost, no motion
        // Two frames later the camera has moved 15 + 15 px; only a shifted lost track can be re-found.
        tracker.update(emptyList(), Shift(15f, 0f))
        val out = tracker.update(listOf(det(130f, 100f, 10f)), Shift(15f, 0f))
        assertEquals(1, out.single().id)
    }

    @Test
    fun lowScoreDetectionsExtendButDontCreate() {
        val tracker = ByteTracker()
        val ids = mutableListOf<Int>()
        var last = emptyList<TrackSnapshot>()
        for (f in 0 until 20) {
            val dets = listOf(
                det(100f + 3f * f, 100f, 12f, score = if (f < 5) 0.9f else 0.3f),
                det(400f, 300f, 12f, score = 0.3f), // a low-score blob that must never start a track
            )
            last = tracker.update(dets)
            ids += last.map { it.id }
        }
        assertEquals(setOf(1), ids.toSet())
        assertEquals(1, last.size) // frame 20 was matched by a 0.3-score detection only
        assertEquals(20, last.single().hits)
    }

    @Test
    fun lowScoreOnlyDetectionsNeverCreateTracks() {
        val tracker = ByteTracker()
        repeat(10) {
            assertTrue(tracker.update(listOf(det(50f, 50f, 12f, 0.3f), det(150f, 50f, 12f, 0.55f))).isEmpty())
        }
    }

    @Test
    fun idIsAssignedOnlyOnActivation() {
        val tracker = ByteTracker()
        tracker.update(listOf(det(50f, 50f, 12f)))                      // frame 1: id 1
        tracker.update(listOf(det(50f, 50f, 12f), det(300f, 50f, 12f))) // frame 2: second one unconfirmed
        tracker.update(listOf(det(50f, 50f, 12f)))                      // frame 3: unconfirmed vanishes, no id burnt
        tracker.update(listOf(det(50f, 50f, 12f), det(500f, 50f, 12f))) // frame 4: unconfirmed again
        val out = tracker.update(listOf(det(50f, 50f, 12f), det(500f, 50f, 12f)))
        assertEquals(listOf(1, 2), out.map { it.id }.sorted())
    }

    @Test
    fun unconfirmedTrackNotMatchedIsDropped() {
        val tracker = ByteTracker()
        tracker.update(emptyList())
        tracker.update(listOf(det(50f, 50f, 12f))) // unconfirmed
        tracker.update(emptyList())                // dropped
        assertTrue(tracker.update(listOf(det(50f, 50f, 12f))).isEmpty()) // starts over, unconfirmed
        assertEquals(1, tracker.update(listOf(det(50f, 50f, 12f))).single().id)
    }

    @Test
    fun newTrackBelowNewTrackThreshDoesNotStart() {
        val tracker = ByteTracker()
        repeat(5) { assertTrue(tracker.update(listOf(det(50f, 50f, 12f, 0.55f))).isEmpty()) }
    }

    @Test
    fun classProbsAccumulateWeighted() {
        val tracker = ByteTracker()
        tracker.update(listOf(det(50f, 50f, 12f, score = 1f, p = floatArrayOf(1f, 0f, 0f, 0f, 0f))))
        val out = tracker.update(listOf(det(50f, 50f, 12f, score = 0.5f, p = floatArrayOf(0f, 1f, 0f, 0f, 0f))))
        val s = out.single()
        assertArrayEquals(floatArrayOf(0.667f, 0.333f, 0f, 0f, 0f), s.classProbs, 1e-3f)
        assertEquals(1f, s.classProbs.sum(), 1e-5f)
        assertEquals(2, s.hits)
        assertEquals(0.75f, s.meanScore, 1e-6f)
        assertEquals(2, s.frameId)
    }

    @Test
    fun noDetectionsGivesNoSnapshots() {
        assertTrue(ByteTracker().update(emptyList()).isEmpty())
    }

    @Test
    fun snapshotBoxFollowsDetection() {
        val b = ByteTracker().update(listOf(det(50f, 60f, 12f))).single().box
        assertEquals(44f, b.x1, 1e-3f)
        assertEquals(54f, b.y1, 1e-3f)
        assertEquals(56f, b.x2, 1e-3f)
        assertEquals(66f, b.y2, 1e-3f)
    }

    @Test
    fun duplicateOfLostTrackIsRemovedKeepingTheOlder() {
        val kf = KalmanFilterXyah()
        val old = STrack(det(100f, 100f, 20f), kf, 1).also { it.activate(1); it.state = TrackState.Lost }
        val young = STrack(det(101f, 100f, 20f), kf, 5).also { it.activate(2) }
        val tracked = arrayListOf(young)
        val lost = arrayListOf(old)
        ByteTracker.removeDuplicateTracks(tracked, lost, 6)
        assertEquals(listOf(1), (tracked + lost).map { it.id })

        // The same, with the lost track the younger one: the tracked (older) survives.
        val t2 = arrayListOf(STrack(det(100f, 100f, 20f), kf, 1).also { it.activate(1) })
        val l2 = arrayListOf(STrack(det(101f, 100f, 20f), kf, 5).also { it.activate(2); it.state = TrackState.Lost })
        ByteTracker.removeDuplicateTracks(t2, l2, 6)
        assertEquals(listOf(1), (t2 + l2).map { it.id })
    }

    @Test
    fun nonOverlappingLostAndTrackedBothSurvive() {
        val kf = KalmanFilterXyah()
        val a = STrack(det(100f, 100f, 20f), kf, 1).also { it.activate(1); it.state = TrackState.Lost }
        val b = STrack(det(130f, 100f, 20f), kf, 5).also { it.activate(2) }
        val tracked = arrayListOf(b)
        val lost = arrayListOf(a)
        ByteTracker.removeDuplicateTracks(tracked, lost, 6)
        assertEquals(1, tracked.size)
        assertEquals(1, lost.size)
    }
}
