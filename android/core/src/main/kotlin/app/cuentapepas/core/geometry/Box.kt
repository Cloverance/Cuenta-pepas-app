package app.cuentapepas.core.geometry

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in pixels, corner format (x1, y1) top-left, (x2, y2) bottom-right. */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val width: Float get() = x2 - x1
    val height: Float get() = y2 - y1

    /** Area, 0 when the box is degenerate (negative width or height). */
    val area: Float get() = max(0f, width) * max(0f, height)
    val cx: Float get() = (x1 + x2) / 2f
    val cy: Float get() = (y1 + y2) / 2f

    /** Intersection over union, with continuous areas (no +1 pixel convention). 0 when the union is empty. */
    fun iou(other: Box): Float = iou(x1, y1, x2, y2, other.x1, other.y1, other.x2, other.y2)

    fun translate(dx: Float, dy: Float): Box = Box(x1 + dx, y1 + dy, x2 + dx, y2 + dy)

    /** Clamps the corners into the `[0, w] x [0, h]` frame. */
    fun clip(w: Float, h: Float): Box = Box(
        x1.coerceIn(0f, w), y1.coerceIn(0f, h),
        x2.coerceIn(0f, w), y2.coerceIn(0f, h),
    )

    companion object {
        /**
         * Allocation-free IoU on raw corners. Single source of truth for the NMS contract shared with
         * the Python reference: intersection / (areaA + areaB - intersection), no +1, 0 if no overlap.
         */
        internal fun iou(
            ax1: Float, ay1: Float, ax2: Float, ay2: Float,
            bx1: Float, by1: Float, bx2: Float, by2: Float,
        ): Float {
            val iw = min(ax2, bx2) - max(ax1, bx1)
            val ih = min(ay2, by2) - max(ay1, by1)
            if (iw <= 0f || ih <= 0f) return 0f
            val inter = iw * ih
            val areaA = max(0f, ax2 - ax1) * max(0f, ay2 - ay1)
            val areaB = max(0f, bx2 - bx1) * max(0f, by2 - by1)
            val union = areaA + areaB - inter
            return if (union > 0f) inter / union else 0f
        }
    }
}
