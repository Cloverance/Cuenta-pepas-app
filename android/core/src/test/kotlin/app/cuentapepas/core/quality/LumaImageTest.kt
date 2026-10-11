package app.cuentapepas.core.quality

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LumaImageTest {

    private fun assertAll(img: LumaImage, expected: Int) {
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                assertEquals("pixel ($x, $y)", expected, img.get(x, y))
            }
        }
    }

    @Test
    fun getReadsUnsignedBytes() {
        val img = LumaImage(2, 1, byteArrayOf(200.toByte(), 7))
        assertEquals(200, img.get(0, 0))
        assertEquals(7, img.get(1, 0))
        assertEquals(200, img[0, 0])
    }

    @Test
    fun rejectsInconsistentDataSize() {
        assertThrows(IllegalArgumentException::class.java) { LumaImage(4, 4, ByteArray(15)) }
    }

    @Test
    fun constantPlaneStaysConstant() {
        val w = 1280
        val h = 720
        val buf = ByteBuffer.allocate(w * h)
        for (i in 0 until w * h) buf.put(i, 100)
        val img = LumaImage.downscale(buf, w, h, rowStride = w, pixelStride = 1)
        assertEquals(160, img.width)
        assertEquals(90, img.height)
        assertAll(img, 100)
    }

    @Test
    fun constantPlaneAbove127IsReadUnsigned() {
        val w = 640
        val h = 360
        val buf = ByteBuffer.allocateDirect(w * h)
        for (i in 0 until w * h) buf.put(i, 250.toByte())
        val img = LumaImage.downscale(buf, w, h, rowStride = w, pixelStride = 1)
        assertAll(img, 250)
    }

    /** Left half 0, right half 200, with row padding filled with 255 that must never be read. */
    @Test
    fun rowPaddingIsSkipped() {
        val w = 1280
        val h = 720
        val rowStride = 1344
        val buf = ByteBuffer.allocateDirect(rowStride * h)
        for (y in 0 until h) {
            for (x in 0 until rowStride) {
                val v = when {
                    x >= w -> 255
                    x < w / 2 -> 0
                    else -> 200
                }
                buf.put(y * rowStride + x, v.toByte())
            }
        }
        val img = LumaImage.downscale(buf, w, h, rowStride = rowStride, pixelStride = 1)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                assertEquals("pixel ($x, $y)", if (x < 80) 0 else 200, img.get(x, y))
            }
        }
    }

    /** Interleaved layout (pixelStride 2) with 255 in the odd bytes. */
    @Test
    fun pixelStrideIsHonoured() {
        val w = 320
        val h = 180
        val pixelStride = 2
        val rowStride = w * pixelStride + 16
        val buf = ByteBuffer.allocate(rowStride * h)
        for (i in 0 until rowStride * h) buf.put(i, 255.toByte())
        for (y in 0 until h) {
            for (x in 0 until w) {
                buf.put(y * rowStride + x * pixelStride, (if (y < h / 2) 40 else 90).toByte())
            }
        }
        val img = LumaImage.downscale(buf, w, h, rowStride = rowStride, pixelStride = pixelStride)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                assertEquals("pixel ($x, $y)", if (y < 45) 40 else 90, img.get(x, y))
            }
        }
    }

    @Test
    fun lastRowMayBeShorterThanRowStride() {
        // CameraX planes often end right after the last pixel, without trailing padding.
        val w = 320
        val h = 180
        val rowStride = 352
        val size = rowStride * (h - 1) + w
        val buf = ByteBuffer.allocateDirect(size)
        for (i in 0 until size) buf.put(i, 60)
        assertAll(LumaImage.downscale(buf, w, h, rowStride, 1), 60)
    }

    @Test
    fun doesNotMoveTheBufferPosition() {
        val w = 320
        val h = 180
        val buf = ByteBuffer.allocateDirect(w * h + 10)
        for (i in 0 until w * h + 10) buf.put(i, 10)
        buf.limit(w * h + 5)
        buf.position(37)
        val img = LumaImage.downscale(buf, w, h, w, 1, dstWidth = 32, dstHeight = 18)
        assertAll(img, 10)
        assertEquals(37, buf.position())
        assertEquals(w * h + 5, buf.limit())
    }

    @Test
    fun averagesTwoByTwoSubsamplePerBlock() {
        // 8x8 source -> 2x2 destination: blocks are 4x4, sub-samples sit at offsets 1 and 3.
        val w = 8
        val h = 8
        val buf = ByteBuffer.allocate(w * h)
        // Mark only the four sampled pixels of block (0, 0) with 10/20/30/40; the rest is 0.
        buf.put(1 * w + 1, 10)
        buf.put(1 * w + 3, 20)
        buf.put(3 * w + 1, 30)
        buf.put(3 * w + 3, 40)
        val img = LumaImage.downscale(buf, w, h, w, 1, dstWidth = 2, dstHeight = 2)
        assertEquals(25, img.get(0, 0))
        assertEquals(0, img.get(1, 0))
        assertEquals(0, img.get(0, 1))
        assertEquals(0, img.get(1, 1))
    }

    @Test
    fun handlesNonIntegerRatiosAndTinySources() {
        val buf = ByteBuffer.allocate(100 * 50)
        for (i in 0 until 100 * 50) buf.put(i, 77)
        assertAll(LumaImage.downscale(buf, 100, 50, 100, 1, dstWidth = 30, dstHeight = 17), 77)
        // Source smaller than the destination: every source pixel is just repeated.
        val tiny = ByteBuffer.allocate(10 * 6)
        for (i in 0 until 10 * 6) tiny.put(i, 5)
        assertAll(LumaImage.downscale(tiny, 10, 6, 10, 1), 5)
    }

    @Test
    fun rejectsABufferThatIsTooSmall() {
        val buf = ByteBuffer.allocate(100)
        assertThrows(IllegalArgumentException::class.java) {
            LumaImage.downscale(buf, 320, 180, 320, 1)
        }
    }
}
