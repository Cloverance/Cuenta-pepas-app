package app.cuentapepas.core.track

import app.cuentapepas.core.detect.Detection
import app.cuentapepas.core.geometry.Box
import app.cuentapepas.core.quality.Shift

/** Thresholds of [ByteTracker]; defaults follow ByteTrack, with `maxTimeLost` in frames. */
data class TrackerConfig(
    val highThresh: Float = 0.5f,
    val lowThresh: Float = 0.1f,
    val newTrackThresh: Float = 0.6f,
    val matchThresh: Double = 0.8,
    val secondMatchThresh: Double = 0.5,
    val unconfirmedMatchThresh: Double = 0.7,
    val maxTimeLost: Int = 30,
    val fuseScore: Boolean = true,
)

/**
 * ByteTrack (MIT, FoundationVision/ByteTrack) with Kalman prediction, Hungarian association and a
 * camera translation applied to every predicted mean. Ids are assigned when a track is activated,
 * from a monotonic counter starting at 1, so tentative tracks that never get confirmed burn no ids.
 *
 * Not thread safe: call [update] once per frame, in order.
 */
class ByteTracker(private val config: TrackerConfig = TrackerConfig()) {
    private val kf = KalmanFilterXyah()
    private var frameId = 0
    private var nextId = 1
    private var tracked = ArrayList<STrack>() // state Tracked, activated or not
    private var lost = ArrayList<STrack>()

    /**
     * Advances the tracker by one frame.
     *
     * @param cameraShift global motion since the previous frame in source pixels: content at (x, y)
     *   in the previous frame appears at (x + dx, y + dy) in this one.
     *   Detections with a non-finite coordinate or a non-positive height are degenerate and are
     *   skipped up front (they cannot seed a meaningful Kalman state).
     * @return the confirmed tracks that were matched in this frame.
     */
    fun update(detections: List<Detection>, cameraShift: Shift = Shift.ZERO): List<TrackSnapshot> {
        frameId++
        val high = ArrayList<Detection>()
        val low = ArrayList<Detection>()
        for (d in detections) {
            if (isDegenerate(d)) continue
            if (d.score >= config.highThresh) high.add(d)
            else if (d.score >= config.lowThresh) low.add(d)
        }

        val unconfirmed = ArrayList<STrack>()
        val pool = ArrayList<STrack>(tracked.size + lost.size)
        for (t in tracked) if (t.isActivated) pool.add(t) else unconfirmed.add(t)
        pool.addAll(lost)

        val dx = cameraShift.dx.toDouble()
        val dy = cameraShift.dy.toDouble()
        for (t in pool) t.predict(kf, dx, dy)
        for (t in unconfirmed) t.predict(kf, dx, dy)

        // First association: pool vs high-score detections.
        val a1 = LinearAssignment.solve(iouCost(pool, high, config.fuseScore), config.matchThresh, high.size)
        for ((ti, di) in a1.matches) pool[ti].update(high[di], kf, frameId)

        // Second association: still-unmatched Tracked tracks vs low-score detections.
        val rTracked = ArrayList<STrack>()
        for (ti in a1.unmatchedRows) if (pool[ti].state == TrackState.Tracked) rTracked.add(pool[ti])
        val a2 = LinearAssignment.solve(iouCost(rTracked, low, false), config.secondMatchThresh, low.size)
        for ((ti, di) in a2.matches) rTracked[ti].update(low[di], kf, frameId)
        for (ti in a2.unmatchedRows) rTracked[ti].state = TrackState.Lost

        // Unconfirmed tracks vs the high-score detections left over.
        val remaining = ArrayList<Detection>(a1.unmatchedCols.size)
        for (di in a1.unmatchedCols) remaining.add(high[di])
        val a3 = LinearAssignment.solve(iouCost(unconfirmed, remaining, config.fuseScore), config.unconfirmedMatchThresh, remaining.size)
        for ((ti, di) in a3.matches) {
            val t = unconfirmed[ti]
            t.update(remaining[di], kf, frameId)
            t.activate(nextId++)
        }
        for (ti in a3.unmatchedRows) unconfirmed[ti].state = TrackState.Removed

        // New tracks from the high-score detections nobody claimed. Only frame 1 activates at once.
        val born = ArrayList<STrack>()
        for (di in a3.unmatchedCols) {
            val det = remaining[di]
            if (det.score < config.newTrackThresh) continue
            val t = STrack(det, kf, frameId)
            if (frameId == 1) t.activate(nextId++)
            born.add(t)
        }

        // Rebuild the pools from the new states and expire long-lost tracks.
        val newTracked = ArrayList<STrack>()
        val newLost = ArrayList<STrack>()
        for (list in arrayOf(tracked, lost, born)) {
            for (t in list) {
                when (t.state) {
                    TrackState.Tracked -> newTracked.add(t)
                    TrackState.Lost -> if (frameId - t.endFrame <= config.maxTimeLost) newLost.add(t)
                    TrackState.Removed -> Unit
                }
            }
        }
        removeDuplicateTracks(newTracked, newLost)
        tracked = newTracked
        lost = newLost

        val out = ArrayList<TrackSnapshot>(tracked.size)
        for (t in tracked) if (t.isActivated) out.add(t.snapshot(frameId))
        return out
    }

    /** `1 - IoU` (or `1 - IoU * score` when [fuse]) between predicted track boxes and detections. */
    private fun iouCost(tracks: List<STrack>, dets: List<Detection>, fuse: Boolean): Array<DoubleArray> {
        if (tracks.isEmpty() || dets.isEmpty()) return Array(tracks.size) { DoubleArray(dets.size) }
        val tb = Array<Box>(tracks.size) { tracks[it].box() }
        return Array(tracks.size) { i ->
            val t = tb[i]
            DoubleArray(dets.size) { j ->
                val d = dets[j]
                val iou = Box.iou(t.x1, t.y1, t.x2, t.y2, d.box.x1, d.box.y1, d.box.x2, d.box.y2).toDouble()
                if (fuse) 1.0 - iou * d.score else 1.0 - iou
            }
        }
    }

    internal companion object {
        private fun isDegenerate(d: Detection): Boolean {
            val b = d.box
            return !(b.x1.isFinite() && b.y1.isFinite() && b.x2.isFinite() && b.y2.isFinite()) ||
                b.y2 - b.y1 <= 0f
        }

        /**
         * ByteTrack's `remove_duplicate_stracks`: a tracked and a lost track whose IoU exceeds 0.85
         * (distance < 0.15) are taken to be the same object; the one with the shorter lifetime
         * (`endFrame - startFrame`, last-seen minus first-seen) is dropped from its list, the tracked
         * one on a tie. This mainly stops a newborn duplicate of a lost track from being activated
         * later (the common case); it cannot un-count ids that were already emitted.
         * Mutates both lists.
         */
        internal fun removeDuplicateTracks(tracked: MutableList<STrack>, lost: MutableList<STrack>) {
            if (tracked.isEmpty() || lost.isEmpty()) return
            val dropTracked = HashSet<STrack>()
            val dropLost = HashSet<STrack>()
            val lostBoxes = lost.map { it.box() }
            for (p in tracked) {
                val pb = p.box()
                for ((j, q) in lost.withIndex()) {
                    val qb = lostBoxes[j]
                    val iou = Box.iou(pb.x1, pb.y1, pb.x2, pb.y2, qb.x1, qb.y1, qb.x2, qb.y2).toDouble()
                    if (1.0 - iou < DUPLICATE_DIST) {
                        if (p.endFrame - p.startFrame > q.endFrame - q.startFrame) dropLost.add(q) else dropTracked.add(p)
                    }
                }
            }
            tracked.removeAll(dropTracked)
            lost.removeAll(dropLost)
        }

        private const val DUPLICATE_DIST = 0.15
    }
}
