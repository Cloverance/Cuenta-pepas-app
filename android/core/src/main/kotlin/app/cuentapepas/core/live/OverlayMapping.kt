package app.cuentapepas.core.live

import app.cuentapepas.core.geometry.Box
import app.cuentapepas.core.track.TrackSnapshot
import kotlin.math.max

/**
 * Maps source-frame pixels to view pixels the way `PreviewView.ScaleType.FILL_CENTER` shows the camera image:
 * uniform scale that covers the whole view ([scale] = max(viewW/srcW, viewH/srcH)), centred, so the overflowing
 * side is cropped symmetrically ([offsetX]/[offsetY] are <= 0 on the cropped axis).
 */
class FillCenter private constructor(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun x(srcX: Float): Float = srcX * scale + offsetX
    fun y(srcY: Float): Float = srcY * scale + offsetY

    /** [box] (source pixels) in view pixels. */
    fun map(box: Box): Box = Box(x(box.x1), y(box.y1), x(box.x2), y(box.y2))

    companion object {
        /** Null while the source or the view has no size yet (nothing can be drawn). */
        fun of(srcWidth: Int, srcHeight: Int, viewWidth: Float, viewHeight: Float): FillCenter? {
            if (srcWidth <= 0 || srcHeight <= 0 || !(viewWidth > 0f) || !(viewHeight > 0f)) return null
            val scale = max(viewWidth / srcWidth, viewHeight / srcHeight)
            return FillCenter(scale, (viewWidth - srcWidth * scale) / 2f, (viewHeight - srcHeight * scale) / 2f)
        }
    }
}

/** Overlay decisions that do not depend on Android. */
object OverlayRules {
    /** Index of the largest probability (ties to the lowest index), the class a track is drawn with. */
    fun classOf(probs: FloatArray): Int {
        var best = 0
        for (i in 1 until probs.size) if (probs[i] > probs[best]) best = i
        return best
    }

    /**
     * Whether a track is drawn as "already counted". [countedIds] is the (slightly delayed) set from the counter;
     * a track that already satisfies the counting rule on this very frame is shown as counted without waiting for
     * the next refresh of that set. While [paused] nothing new is counted, so only [countedIds] decides.
     */
    fun isCounted(
        track: TrackSnapshot,
        countedIds: Set<Int>,
        paused: Boolean,
        minHits: Int,
        minMeanScore: Float,
    ): Boolean {
        if (track.id in countedIds) return true
        return !paused && track.hits >= minHits && track.meanScore >= minMeanScore
    }
}
