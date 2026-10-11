package app.cuentapepas.core.track

import app.cuentapepas.core.detect.Detection
import app.cuentapepas.core.geometry.Box

internal enum class TrackState { Tracked, Lost, Removed }

/** Public view of a confirmed track at one frame. [classProbs] is the score-weighted class vote, summing to 1. */
data class TrackSnapshot(
    val id: Int,
    val box: Box,
    val hits: Int,
    val meanScore: Float,
    val classProbs: FloatArray,
    val frameId: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackSnapshot) return false
        return id == other.id && box == other.box && hits == other.hits &&
            meanScore.compareTo(other.meanScore) == 0 && classProbs.contentEquals(other.classProbs) &&
            frameId == other.frameId
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + box.hashCode()
        result = 31 * result + hits
        result = 31 * result + meanScore.hashCode()
        result = 31 * result + classProbs.contentHashCode()
        result = 31 * result + frameId
        return result
    }
}

/** Internal mutable track. [id] stays 0 until the track is activated. */
internal class STrack(det: Detection, kf: KalmanFilterXyah, frameId: Int) {
    var id = 0
        private set
    var state = TrackState.Tracked
    var isActivated = false
        private set
    val mean = DoubleArray(8)
    val cov = DoubleArray(64)

    /** Number of updates, counting the creation. */
    var hits = 0
        private set
    var scoreSum = 0.0
        private set
    val classProbSum = DoubleArray(det.classProbs.size)
    val startFrame = frameId
    var endFrame = frameId

    init {
        kf.initiate(measurement(det), mean, cov)
        accumulate(det)
    }

    fun activate(newId: Int) {
        id = newId
        isActivated = true
    }

    /** Kalman prediction followed by the camera translation. Lost tracks stop extrapolating height. */
    fun predict(kf: KalmanFilterXyah, dx: Double, dy: Double) {
        if (state != TrackState.Tracked) mean[7] = 0.0
        kf.predict(mean, cov)
        mean[0] += dx
        mean[1] += dy
    }

    /** Correction with a matched detection; also re-activates a Lost track (its id is kept). */
    fun update(det: Detection, kf: KalmanFilterXyah, frameId: Int) {
        kf.update(mean, cov, measurement(det))
        accumulate(det)
        state = TrackState.Tracked
        endFrame = frameId
    }

    private fun accumulate(det: Detection) {
        hits++
        scoreSum += det.score
        val p = det.classProbs
        for (i in 0 until minOf(p.size, classProbSum.size)) classProbSum[i] += p[i] * det.score.toDouble()
    }

    fun snapshot(frameId: Int): TrackSnapshot {
        var sum = 0.0
        for (v in classProbSum) sum += v
        val probs = FloatArray(classProbSum.size) { if (sum > 0.0) (classProbSum[it] / sum).toFloat() else 0f }
        return TrackSnapshot(id, box(), hits, (scoreSum / hits).toFloat(), probs, frameId)
    }

    fun box(): Box {
        val h = mean[3]
        val w = mean[2] * h
        return Box(
            (mean[0] - w / 2).toFloat(), (mean[1] - h / 2).toFloat(),
            (mean[0] + w / 2).toFloat(), (mean[1] + h / 2).toFloat(),
        )
    }

    private companion object {
        fun measurement(det: Detection): DoubleArray {
            val b = det.box
            val h = maxOf(b.height.toDouble(), 1e-3)
            val w = maxOf(b.width.toDouble(), 0.0)
            return doubleArrayOf(b.cx.toDouble(), b.cy.toDouble(), w / h, h)
        }
    }
}
