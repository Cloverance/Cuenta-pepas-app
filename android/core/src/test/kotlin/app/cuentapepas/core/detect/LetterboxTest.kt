package app.cuentapepas.core.detect

import app.cuentapepas.core.geometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxTest {
    @Test fun frame1280x720Into640x384() {
        val lb = Letterbox.fit(srcWidth = 1280, srcHeight = 720, dstWidth = 640, dstHeight = 384)
        assertEquals(0.5f, lb.scale, 1e-6f); assertEquals(640, lb.resizedWidth); assertEquals(360, lb.resizedHeight)
        assertEquals(Box(200f, 100f, 400f, 300f), lb.toSource(Box(100f, 50f, 200f, 150f)))
    }

    @Test fun toSourceClipsToFrame() {
        val lb = Letterbox.fit(1280, 720, 640, 384)
        assertEquals(Box(0f, 600f, 1280f, 720f), lb.toSource(Box(-10f, 300f, 700f, 380f)))
    }

    @Test fun toModelIsInverseOfToSourceInsideFrame() {
        val lb = Letterbox.fit(1280, 720, 640, 384)
        val src = Box(200f, 100f, 400f, 300f)
        assertEquals(Box(100f, 50f, 200f, 150f), lb.toModel(src))
        assertEquals(src, lb.toSource(lb.toModel(src)))
    }

    @Test fun portraitFrameIsLimitedByHeight() {
        val lb = Letterbox.fit(srcWidth = 720, srcHeight = 1280, dstWidth = 640, dstHeight = 384)
        assertEquals(0.3f, lb.scale, 1e-6f); assertEquals(216, lb.resizedWidth); assertEquals(384, lb.resizedHeight)
    }

    @Test fun resizedSizeNeverExceedsDestination() {
        val lb = Letterbox.fit(1919, 1081, 640, 384)
        assertTrue("${lb.resizedWidth}x${lb.resizedHeight}", lb.resizedWidth <= 640 && lb.resizedHeight <= 384)
    }
}
