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

    /**
     * Re-expresses the shift in an image that is the original rotated clockwise by [degrees] (0, 90, 180
     * or 270, the `rotationDegrees` of a CameraX frame). Content moving by (dx, dy) in the original moves by
     * (-dy, dx) after a 90 degree clockwise turn (y axis pointing down).
     */
    fun rotatedClockwise(degrees: Int): Shift = when (((degrees % 360) + 360) % 360) {
        0 -> this
        90 -> Shift(-dy, dx)
        180 -> Shift(-dx, -dy)
        270 -> Shift(dy, -dx)
        else -> throw IllegalArgumentException("degrees must be a multiple of 90, got $degrees")
    }

    companion object {
        val ZERO = Shift(0f, 0f)
    }
}
