package app.cuentapepas.core.track

import app.cuentapepas.core.detect.Detection
import app.cuentapepas.core.geometry.Box
import app.cuentapepas.core.quality.Shift
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteTrackerPerfTest {

    @Test
    fun threeHundredDetectionsOverHundredFramesIsFast() {
        val rnd = Random(1)
        val bx = FloatArray(300) { rnd.nextFloat() * 1240f }
        val by = FloatArray(300) { rnd.nextFloat() * 680f }
        val probs = floatArrayOf(0.1f, 0.6f, 0.1f, 0.1f, 0.1f)
        val size = FloatArray(300) { 10f + rnd.nextFloat() * 20f }
        fun frame(f: Int) = List(300) { k ->
            val cx = bx[k] + 2f * f + rnd.nextFloat() * 2f
            val cy = by[k] + rnd.nextFloat() * 2f
            val s = size[k] + rnd.nextFloat() * 2f
            Detection(Box(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), 0.4f + rnd.nextFloat() * 0.6f, 1, probs)
        }
        val frames = List(100) { frame(it) }
        val t0 = System.nanoTime()
        val tracker = ByteTracker()
        var total = 0
        for (f in frames) total += tracker.update(f, Shift(2f, 0f)).size
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("ByteTracker 300 dets x 100 frames: $ms ms, $total snapshots")
        assertTrue("took $ms ms", ms < 3000)
        assertTrue(total > 0)
    }

    @Test
    fun denseClusterStaysFast() {
        // 300 boxes of 30 px in a 300x200 region: many neighbours overlap, so the IoU matrix is far from sparse
        // and the assignment problem does not split into small components.
        val rnd = Random(2)
        val probs = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        val bx = FloatArray(300) { 500f + rnd.nextFloat() * 300f }
        val by = FloatArray(300) { 250f + rnd.nextFloat() * 200f }
        val tracker = ByteTracker()
        val t0 = System.nanoTime()
        var last = 0
        repeat(100) {
            val dets = List(300) { k ->
                val cx = bx[k] + rnd.nextFloat() * 2f
                val cy = by[k] + rnd.nextFloat() * 2f
                Detection(Box(cx - 15f, cy - 15f, cx + 15f, cy + 15f), 0.9f, 0, probs)
            }
            last = tracker.update(dets).size
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("ByteTracker dense cluster 300 x 100: $ms ms, $last confirmed in last frame")
        assertTrue("took $ms ms", ms < 3000)
    }
}
