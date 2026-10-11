package app.cuentapepas.core.detect

/**
 * Packs a resized ARGB image into the model input tensor: float32, NHWC, channel order BGR,
 * raw 0-255 values (no normalisation), image at the top-left corner and every pixel to its right and
 * below filled with the pad value. Matches `input.channel_order = BGR`, `input.letterbox = top_left`
 * and `input.pad_value = 114` of `model.json`.
 *
 * Pure function so the byte-level contract can be tested on the JVM; the Android side only feeds it
 * the pixels of a `Bitmap` and copies [pack]'s output into a direct buffer.
 */
object InputPacker {

    const val CHANNELS = 3
    const val DEFAULT_PAD_VALUE = 114f

    /** Size of the output array [pack] needs for a [dstW] x [dstH] model input. */
    fun outputSize(dstW: Int, dstH: Int): Int = dstW * dstH * CHANNELS

    /**
     * @param argb [w] x [h] pixels, row-major, as returned by `Bitmap.getPixels` (`0xAARRGGBB`; alpha is ignored).
     * @param out receives `dstH` rows of `dstW` pixels of 3 floats (B, G, R); at least [outputSize] long.
     *   Only the first [outputSize] entries are written.
     * @param padValue value of every channel outside the image.
     */
    fun pack(
        argb: IntArray,
        w: Int,
        h: Int,
        dstW: Int,
        dstH: Int,
        out: FloatArray,
        padValue: Float = DEFAULT_PAD_VALUE,
    ) {
        require(w > 0 && h > 0) { "image size must be positive: ${w}x$h" }
        require(dstW >= w && dstH >= h) { "image ${w}x$h does not fit in the ${dstW}x$dstH input" }
        require(argb.size >= w * h) { "argb has ${argb.size} pixels, expected at least ${w * h} for ${w}x$h" }
        require(out.size >= outputSize(dstW, dstH)) {
            "out has ${out.size} floats, expected at least ${outputSize(dstW, dstH)} for ${dstW}x$dstH"
        }
        var o = 0
        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                val p = argb[i++]
                out[o++] = (p and 0xFF).toFloat() // B
                out[o++] = ((p ushr 8) and 0xFF).toFloat() // G
                out[o++] = ((p ushr 16) and 0xFF).toFloat() // R
            }
            // Right padding of this image row.
            val rowEnd = o + (dstW - w) * CHANNELS
            while (o < rowEnd) out[o++] = padValue
        }
        // Bottom padding.
        val end = outputSize(dstW, dstH)
        while (o < end) out[o++] = padValue
    }
}
