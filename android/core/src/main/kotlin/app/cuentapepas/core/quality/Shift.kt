package app.cuentapepas.core.quality

/**
 * Global (camera) motion between two consecutive frames, in pixels.
 *
 * Convention: content that sits at (x, y) in the previous frame appears at (x + dx, y + dy)
 * in the current one.
 */
data class Shift(val dx: Float, val dy: Float) {

    /** Converts the shift to another pixel grid, e.g. from the 160x90 luma image to the source frame. */
    fun scaled(sx: Float, sy: Float): Shift = Shift(dx * sx, dy * sy)

    companion object {
        val ZERO = Shift(0f, 0f)
    }
}
