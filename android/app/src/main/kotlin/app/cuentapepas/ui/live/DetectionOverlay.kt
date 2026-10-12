package app.cuentapepas.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.cuentapepas.core.live.FillCenter
import app.cuentapepas.core.live.OverlayRules
import app.cuentapepas.pipeline.FrameResult
import kotlinx.coroutines.flow.StateFlow

/** Colours per maturity class, by class index: verde, pintón, maduro, sobremaduro, seco. */
internal val ClassColors = listOf(
    Color(0xFF2E7D32),
    Color(0xFFF9A825),
    Color(0xFFC62828),
    Color(0xFF6A1B9A),
    Color(0xFF4E342E),
)

internal fun classColor(index: Int): Color = ClassColors[index.mod(ClassColors.size)]

/**
 * Draws the tracks of the latest frame over the camera preview. The Canvas must fill the same area as the
 * `PreviewView` (FILL_CENTER), because boxes are mapped with the same cover-and-centre transform.
 *
 * The flows are read inside the draw block, so a new frame only redraws this Canvas; nothing recomposes and no
 * UI-thread work happens other than drawing the rectangles.
 */
@Composable
fun DetectionOverlay(
    frame: StateFlow<FrameResult?>,
    countedIds: StateFlow<Set<Int>>,
    minHits: Int,
    minMeanScore: Float,
    modifier: Modifier = Modifier,
) {
    val frameState = frame.collectAsState()
    val countedState = countedIds.collectAsState()
    Canvas(modifier) {
        val r = frameState.value ?: return@Canvas
        val map = FillCenter.of(r.srcWidth, r.srcHeight, size.width, size.height) ?: return@Canvas
        val counted = countedState.value
        val thin = 2.dp.toPx()
        val thick = 5.dp.toPx()
        val halo = 2.dp.toPx()
        for (t in r.tracks) {
            val box = map.map(t.box)
            val w = box.width
            val h = box.height
            if (!(w > 0f) || !(h > 0f)) continue
            val isCounted = OverlayRules.isCounted(t, counted, r.paused, minHits, minMeanScore)
            val stroke = if (isCounted) thick else thin
            val topLeft = Offset(box.x1, box.y1)
            val sz = Size(w, h)
            // White halo underneath keeps the dark classes (seco, sobremaduro) visible on dark leaves.
            drawRect(Color.White, topLeft, sz, style = Stroke(stroke + halo))
            drawRect(classColor(OverlayRules.classOf(t.classProbs)), topLeft, sz, style = Stroke(stroke))
        }
    }
}
