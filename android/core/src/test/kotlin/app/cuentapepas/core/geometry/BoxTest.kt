package app.cuentapepas.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxTest {
    @Test fun iouOfIdenticalIsOne() = assertEquals(1f, Box(0f, 0f, 10f, 10f).iou(Box(0f, 0f, 10f, 10f)), 1e-6f)
    @Test fun iouHalfOverlap() = assertEquals(1f / 3f, Box(0f, 0f, 10f, 10f).iou(Box(5f, 0f, 15f, 10f)), 1e-6f)
    @Test fun iouDisjointIsZero() = assertEquals(0f, Box(0f, 0f, 1f, 1f).iou(Box(2f, 2f, 3f, 3f)), 0f)
    @Test fun translate() = assertEquals(Box(1f, 2f, 11f, 12f), Box(0f, 0f, 10f, 10f).translate(1f, 2f))

    @Test fun derivedGeometry() {
        val b = Box(10f, 20f, 30f, 60f)
        assertEquals(20f, b.width, 0f); assertEquals(40f, b.height, 0f); assertEquals(800f, b.area, 0f)
        assertEquals(20f, b.cx, 0f); assertEquals(40f, b.cy, 0f)
    }

    @Test fun degenerateBoxHasZeroAreaAndZeroIou() {
        val inverted = Box(10f, 10f, 5f, 5f)
        assertEquals(0f, inverted.area, 0f)
        assertEquals(0f, inverted.iou(Box(0f, 0f, 20f, 20f)), 0f)
        assertEquals(0f, Box(0f, 0f, 0f, 0f).iou(Box(0f, 0f, 0f, 0f)), 0f)
    }

    @Test fun iouIsWithoutPlusOne() {
        // Touching boxes share an edge but no area: IoU must be exactly 0 (no +1 pixel convention).
        assertEquals(0f, Box(0f, 0f, 10f, 10f).iou(Box(10f, 0f, 20f, 10f)), 0f)
    }

    @Test fun clipLimitsToFrame() {
        assertEquals(Box(0f, 5f, 100f, 50f), Box(-5f, 5f, 120f, 70f).clip(100f, 50f))
    }
}
