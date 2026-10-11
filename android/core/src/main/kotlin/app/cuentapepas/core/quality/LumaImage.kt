package app.cuentapepas.core.quality

import java.nio.ByteBuffer

/**
 * Small 8-bit luma (brightness) image. [data] holds unsigned values 0-255, row-major, tightly packed.
 *
 * The array is shared, not copied: callers must not mutate it after handing it over.
 */
class LumaImage(val width: Int, val height: Int, val data: ByteArray) {

    init {
        require(width > 0 && height > 0) { "Image size must be positive: ${width}x$height" }
        require(data.size == width * height) {
            "Expected ${width * height} bytes for ${width}x$height, got ${data.size}"
        }
    }

    /** Pixel value 0-255. */
    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    companion object {
        const val DEFAULT_WIDTH = 160
        const val DEFAULT_HEIGHT = 90

        /**
         * Shrinks a camera Y plane to [dstWidth] x [dstHeight].
         *
         * Each destination pixel is the mean of a 2x2 subsample of its source block (the centres
         * of the block's four quadrants), which costs 4 reads per output pixel instead of the whole
         * block. Rows are [rowStride] bytes apart and pixels [pixelStride] bytes apart, as in a
         * CameraX `ImageProxy.PlaneProxy`. Only absolute reads are used, so the position and limit
         * of [src] are left untouched.
         */
        fun downscale(
            src: ByteBuffer,
            srcWidth: Int,
            srcHeight: Int,
            rowStride: Int,
            pixelStride: Int,
            dstWidth: Int = DEFAULT_WIDTH,
            dstHeight: Int = DEFAULT_HEIGHT,
        ): LumaImage {
            require(srcWidth > 0 && srcHeight > 0) { "Source size must be positive: ${srcWidth}x$srcHeight" }
            require(dstWidth > 0 && dstHeight > 0) { "Destination size must be positive: ${dstWidth}x$dstHeight" }
            require(pixelStride >= 1) { "pixelStride must be >= 1, got $pixelStride" }
            require(rowStride >= (srcWidth - 1) * pixelStride + 1) {
                "rowStride $rowStride is too small for width $srcWidth with pixelStride $pixelStride"
            }
            val lastIndex = (srcHeight - 1) * rowStride + (srcWidth - 1) * pixelStride
            require(src.limit() > lastIndex) {
                "Buffer limit ${src.limit()} is too small: the last pixel is at byte $lastIndex"
            }

            // Sample offsets (already multiplied by the strides), computed once per call.
            val colA = IntArray(dstWidth)
            val colB = IntArray(dstWidth)
            for (i in 0 until dstWidth) {
                val start = i * srcWidth / dstWidth
                val size = maxOf(1, (i + 1) * srcWidth / dstWidth - start)
                colA[i] = (start + size / 4) * pixelStride
                colB[i] = (start + 3 * size / 4) * pixelStride
            }
            val rowA = IntArray(dstHeight)
            val rowB = IntArray(dstHeight)
            for (j in 0 until dstHeight) {
                val start = j * srcHeight / dstHeight
                val size = maxOf(1, (j + 1) * srcHeight / dstHeight - start)
                rowA[j] = (start + size / 4) * rowStride
                rowB[j] = (start + 3 * size / 4) * rowStride
            }

            val out = ByteArray(dstWidth * dstHeight)
            var o = 0
            for (j in 0 until dstHeight) {
                val ra = rowA[j]
                val rb = rowB[j]
                for (i in 0 until dstWidth) {
                    val ca = colA[i]
                    val cb = colB[i]
                    val sum = (src.get(ra + ca).toInt() and 0xFF) +
                        (src.get(ra + cb).toInt() and 0xFF) +
                        (src.get(rb + ca).toInt() and 0xFF) +
                        (src.get(rb + cb).toInt() and 0xFF)
                    out[o++] = ((sum + 2) shr 2).toByte()
                }
            }
            return LumaImage(dstWidth, dstHeight, out)
        }
    }
}
