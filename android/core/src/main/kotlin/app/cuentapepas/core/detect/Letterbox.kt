package app.cuentapepas.core.detect

import app.cuentapepas.core.geometry.Box
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Top-left letterbox of a source frame into the model input: the frame is scaled uniformly by
 * [scale] and placed at (0, 0); padding fills the right and/or bottom edge. Matches the
 * `"letterbox": "top_left"` entry of `model.json`.
 */
class Letterbox private constructor(
    val srcWidth: Int,
    val srcHeight: Int,
    val dstWidth: Int,
    val dstHeight: Int,
    val scale: Float,
    val resizedWidth: Int,
    val resizedHeight: Int,
) {
    /** Maps a box from model-input pixels back to source-frame pixels, clipped to the frame. */
    fun toSource(b: Box): Box =
        Box(b.x1 / scale, b.y1 / scale, b.x2 / scale, b.y2 / scale).clip(srcWidth.toFloat(), srcHeight.toFloat())

    /** Inverse of [toSource] (without clipping): source-frame pixels to model-input pixels. */
    fun toModel(b: Box): Box = Box(b.x1 * scale, b.y1 * scale, b.x2 * scale, b.y2 * scale)

    companion object {
        fun fit(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Letterbox {
            require(srcWidth > 0 && srcHeight > 0) { "source size must be positive: ${srcWidth}x$srcHeight" }
            require(dstWidth > 0 && dstHeight > 0) { "destination size must be positive: ${dstWidth}x$dstHeight" }
            val scale = min(dstHeight.toFloat() / srcHeight, dstWidth.toFloat() / srcWidth)
            // coerceAtMost guards against float rounding pushing the limiting side one pixel past the target.
            val rw = (srcWidth * scale).roundToInt().coerceAtMost(dstWidth)
            val rh = (srcHeight * scale).roundToInt().coerceAtMost(dstHeight)
            return Letterbox(srcWidth, srcHeight, dstWidth, dstHeight, scale, rw, rh)
        }
    }
}
