package app.cuentapepas.inference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import app.cuentapepas.core.detect.InputPacker
import app.cuentapepas.core.detect.Letterbox
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.quality.LumaImage
import app.cuentapepas.core.quality.Shift
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One prepared camera frame.
 *
 * @property input model input tensor (float32 NHWC BGR). It is a buffer owned by the [FramePreprocessor] and
 *   overwritten by the next [FramePreprocessor.prepare]; consume it before preparing another frame.
 * @property letterbox maps model-input pixels back to the upright source frame of size [srcWidth] x [srcHeight].
 * @property luma 160x90 luma of the camera Y plane (sensor orientation, i.e. not rotated).
 */
class Prepared(
    val input: ByteBuffer,
    val letterbox: Letterbox,
    val luma: LumaImage,
    val srcWidth: Int,
    val srcHeight: Int,
    private val shiftScaleX: Float,
    private val shiftScaleY: Float,
    private val rotationDegrees: Int,
    private val rotatedScaleX: Float,
    private val rotatedScaleY: Float,
) {
    /**
     * Converts a shift measured between two [luma] images (160x90 grid, sensor orientation) to source-frame
     * pixels (upright frame the detections live in).
     */
    fun lumaShiftToSource(shift: Shift): Shift =
        shift.scaled(shiftScaleX, shiftScaleY).rotatedClockwise(rotationDegrees).scaled(rotatedScaleX, rotatedScaleY)
}

/**
 * Turns a CameraX frame into the model input. Not thread-safe; use it from the frame-processing thread only.
 *
 * Pipeline: `ImageProxy.toBitmap()` -> one draw of that bitmap into a reused bitmap of the letterbox size with
 * the rotation (`imageInfo.rotationDegrees`, normally 0 because the activity is landscape) and bilinear scaling
 * folded into a single `Matrix` -> `getPixels` into a reused `IntArray` -> [InputPacker] into a reused
 * `FloatArray` -> reused direct `ByteBuffer`. The only per-frame allocations are the unavoidable full-size
 * bitmap from `toBitmap()` (recycled right away) and the 14 KB luma image.
 */
class FramePreprocessor(private val spec: ModelSpec) {

    private val dstW = spec.inputWidth
    private val dstH = spec.inputHeight
    private val floats = FloatArray(InputPacker.outputSize(dstW, dstH))
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
    private val inputView = inputBuffer.asFloatBuffer()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    private var scaled: Bitmap? = null
    private var scaledCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private var letterbox: Letterbox? = null

    fun prepare(image: ImageProxy): Prepared {
        val rotation = image.imageInfo.rotationDegrees
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
            "unsupported rotationDegrees: $rotation"
        }
        val plane = image.planes[0]
        val luma = LumaImage.downscale(
            src = plane.buffer,
            srcWidth = image.width,
            srcHeight = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )

        val bitmap = image.toBitmap()
        try {
            val bw = bitmap.width
            val bh = bitmap.height
            val swap = rotation == 90 || rotation == 270
            val srcW = if (swap) bh else bw
            val srcH = if (swap) bw else bh

            var lb = letterbox
            if (lb == null || lb.srcWidth != srcW || lb.srcHeight != srcH) {
                lb = Letterbox.fit(srcW, srcH, dstW, dstH)
                letterbox = lb
            }
            val rw = lb.resizedWidth
            val rh = lb.resizedHeight
            val target = ensureTarget(rw, rh)

            matrix.reset()
            matrix.postTranslate(-bw / 2f, -bh / 2f)
            matrix.postRotate(rotation.toFloat())
            matrix.postTranslate(srcW / 2f, srcH / 2f)
            matrix.postScale(rw.toFloat() / srcW, rh.toFloat() / srcH)
            checkNotNull(scaledCanvas).drawBitmap(bitmap, matrix, paint)

            target.getPixels(pixels, 0, rw, 0, 0, rw, rh)
            InputPacker.pack(pixels, rw, rh, dstW, dstH, floats, spec.input.padValue.toFloat())
            inputView.rewind()
            inputView.put(floats)
            inputBuffer.rewind()

            // Luma dims are the plane's (sensor orientation); express the rotated plane in source pixels.
            val rotatedPlaneW = if (swap) image.height else image.width
            val rotatedPlaneH = if (swap) image.width else image.height
            return Prepared(
                input = inputBuffer,
                letterbox = lb,
                luma = luma,
                srcWidth = srcW,
                srcHeight = srcH,
                shiftScaleX = image.width.toFloat() / luma.width,
                shiftScaleY = image.height.toFloat() / luma.height,
                rotationDegrees = rotation,
                rotatedScaleX = srcW.toFloat() / rotatedPlaneW,
                rotatedScaleY = srcH.toFloat() / rotatedPlaneH,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun ensureTarget(w: Int, h: Int): Bitmap {
        val current = scaled
        if (current != null && current.width == w && current.height == h) return current
        current?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        scaled = bmp
        scaledCanvas = Canvas(bmp)
        pixels = IntArray(w * h)
        return bmp
    }

    /** Releases the reusable bitmap. */
    fun release() {
        scaled?.recycle()
        scaled = null
        scaledCanvas = null
    }
}
