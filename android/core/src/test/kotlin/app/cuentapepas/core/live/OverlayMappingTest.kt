package app.cuentapepas.core.live

import app.cuentapepas.core.geometry.Box
import app.cuentapepas.core.track.TrackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayMappingTest {
    private fun track(id: Int, hits: Int, score: Float) =
        TrackSnapshot(id, Box(0f, 0f, 1f, 1f), hits, score, floatArrayOf(1f, 0f), 1)

    @Test fun sameAspectIsPlainScaling() {
        val m = FillCenter.of(1280, 720, 1920f, 1080f)!!
        assertEquals(1.5f, m.scale, 1e-6f)
        assertEquals(0f, m.offsetX, 1e-3f)
        assertEquals(0f, m.offsetY, 1e-3f)
        assertEquals(Box(150f, 75f, 300f, 150f), m.map(Box(100f, 50f, 200f, 100f)))
    }

    @Test fun tallerViewCropsSidesSymmetrically() {
        // 1280x720 into 800x600: height decides (0.8333), the 1066.7 px wide image loses 133.3 px per side.
        val m = FillCenter.of(1280, 720, 800f, 600f)!!
        assertEquals(600f / 720f, m.scale, 1e-6f)
        assertEquals(-(1280f * m.scale - 800f) / 2f, m.offsetX, 1e-3f)
        assertEquals(0f, m.offsetY, 1e-3f)
        // The source centre stays at the view centre.
        assertEquals(400f, m.x(640f), 1e-3f)
        assertEquals(300f, m.y(360f), 1e-3f)
    }

    @Test fun widerViewCropsTopAndBottom() {
        // 1280x720 into 1000x400 (2.5:1, wider than 16:9): width decides, 0.78125 -> 562.5 high, crop 81.25 per side.
        val m = FillCenter.of(1280, 720, 1000f, 400f)!!
        assertEquals(1000f / 1280f, m.scale, 1e-6f)
        assertEquals(0f, m.offsetX, 1e-3f)
        assertEquals(-81.25f, m.offsetY, 1e-3f)
        assertEquals(200f, m.y(360f), 1e-3f)
    }

    @Test fun noSizeNoMapping() {
        assertNull(FillCenter.of(0, 720, 800f, 600f))
        assertNull(FillCenter.of(1280, 720, 0f, 600f))
        assertNull(FillCenter.of(1280, 720, Float.NaN, 600f))
        assertNotNull(FillCenter.of(1, 1, 1f, 1f))
    }

    @Test fun classIsArgmaxWithTiesToLowest() {
        assertEquals(2, OverlayRules.classOf(floatArrayOf(0.1f, 0.2f, 0.7f)))
        assertEquals(0, OverlayRules.classOf(floatArrayOf(0.5f, 0.5f)))
    }

    @Test fun countedFromSetOrFromRuleWhenNotPaused() {
        assertTrue(OverlayRules.isCounted(track(1, 1, 0.1f), setOf(1), paused = true, minHits = 3, minMeanScore = 0.4f))
        assertTrue(OverlayRules.isCounted(track(2, 3, 0.5f), emptySet(), paused = false, minHits = 3, minMeanScore = 0.4f))
        assertFalse(OverlayRules.isCounted(track(2, 3, 0.5f), emptySet(), paused = true, minHits = 3, minMeanScore = 0.4f))
        assertFalse(OverlayRules.isCounted(track(3, 2, 0.9f), emptySet(), paused = false, minHits = 3, minMeanScore = 0.4f))
        assertFalse(OverlayRules.isCounted(track(3, 5, 0.3f), emptySet(), paused = false, minHits = 3, minMeanScore = 0.4f))
    }
}
