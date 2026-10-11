package app.cuentapepas.core.detect

import app.cuentapepas.core.geometry.Box
import kotlin.math.min

/**
 * YOLOX decoded-output postprocessing. This is a CONTRACT shared with the Python reference
 * (`ml/src/cuentapepas/postprocess.py`); keep both in lock-step:
 *
 * - per anchor: `score = obj * max(cls)`, `cls = argmax(cls)` (first index wins ties);
 * - keep anchors with `score >= scoreThreshold`;
 * - sort by score descending, stable (ties keep anchor-index order);
 * - greedy class-agnostic NMS: a candidate is suppressed when IoU with an already kept box is
 *   strictly greater than `nmsIou` (IoU with continuous areas, no +1);
 * - at most `maxDetections` detections;
 * - boxes are `x1 = cx - w/2`, `y1 = cy - h/2`, `x2 = cx + w/2`, `y2 = cy + h/2`, in model-input pixels.
 *
 * The hot path (one call per frame, ~5040 anchors) allocates nothing per anchor: scratch buffers are
 * reused between calls and only the returned detections are allocated. Because of that an instance is
 * NOT thread-safe; use one per processing thread.
 */
class YoloxPostprocessor(val numClasses: Int) {
    init {
        require(numClasses > 0) { "numClasses must be positive: $numClasses" }
    }

    /** Values per anchor: cx, cy, w, h, obj + one probability per class. */
    private val channels = 5 + numClasses

    // Scratch buffers, grown on demand and reused across frames.
    private var sortKeys = LongArray(0)
    private var scoreByAnchor = FloatArray(0)
    private var clsByAnchor = IntArray(0)
    private var keptAnchor = IntArray(0)
    private var keptBox = FloatArray(0)

    /**
     * @param raw model output, either `[numAnchors, 5 + numClasses]` row-major or, when [transposed],
     *   `[5 + numClasses, numAnchors]`; its size must be exactly `numAnchors * (5 + numClasses)`.
     * @return detections ordered by score descending, with boxes in model-input pixels.
     */
    fun process(
        raw: FloatArray,
        numAnchors: Int,
        transposed: Boolean,
        scoreThreshold: Float,
        nmsIou: Float,
        maxDetections: Int,
    ): List<Detection> {
        require(numAnchors >= 0) { "numAnchors must not be negative: $numAnchors" }
        require(raw.size.toLong() == numAnchors.toLong() * channels) {
            "raw has ${raw.size} values, expected $numAnchors anchors x $channels channels (numClasses=$numClasses)"
        }
        if (numAnchors == 0 || maxDetections <= 0) return emptyList()
        ensureAnchorCapacity(numAnchors)

        // Element (anchor i, channel j) lives at i * anchorStep + j * channelStep.
        val anchorStep = if (transposed) 1 else channels
        val channelStep = if (transposed) numAnchors else 1

        // Pass 1: score, argmax and threshold filter. Candidates go into sortKeys as packed longs.
        var candidates = 0
        for (i in 0 until numAnchors) {
            val base = i * anchorStep
            val obj = raw[base + 4 * channelStep]
            var best = raw[base + 5 * channelStep]
            var bestCls = 0
            for (c in 1 until numClasses) {
                val p = raw[base + (5 + c) * channelStep]
                if (p > best) {
                    best = p
                    bestCls = c
                }
            }
            // "+ 0f" turns -0.0 into +0.0 so that signed zeros tie, as they do in the Python reference.
            val score = obj * best + 0f
            if (score >= scoreThreshold) {
                scoreByAnchor[i] = score
                clsByAnchor[i] = bestCls
                sortKeys[candidates++] = sortKey(score, i)
            }
        }
        if (candidates == 0) return emptyList()

        // Pass 2: descending score, ties by ascending anchor index. Keys are unique, so a plain
        // primitive sort gives the same order as a stable sort on score alone.
        java.util.Arrays.sort(sortKeys, 0, candidates)

        // Pass 3: greedy class-agnostic NMS against the boxes kept so far.
        val cap = min(candidates, maxDetections)
        ensureKeptCapacity(cap)
        var kept = 0
        var k = 0
        while (k < candidates && kept < cap) {
            val i = sortKeys[k++].toInt() // low 32 bits = anchor index
            val base = i * anchorStep
            val cx = raw[base]
            val cy = raw[base + channelStep]
            val halfW = raw[base + 2 * channelStep] / 2f
            val halfH = raw[base + 3 * channelStep] / 2f
            val x1 = cx - halfW
            val y1 = cy - halfH
            val x2 = cx + halfW
            val y2 = cy + halfH
            var suppressed = false
            var q = 0
            while (q < kept) {
                val o = q * 4
                if (Box.iou(x1, y1, x2, y2, keptBox[o], keptBox[o + 1], keptBox[o + 2], keptBox[o + 3]) > nmsIou) {
                    suppressed = true
                    break
                }
                q++
            }
            if (!suppressed) {
                val o = kept * 4
                keptBox[o] = x1; keptBox[o + 1] = y1; keptBox[o + 2] = x2; keptBox[o + 3] = y2
                keptAnchor[kept] = i
                kept++
            }
        }

        // Only the survivors are materialised.
        val out = ArrayList<Detection>(kept)
        for (q in 0 until kept) {
            val i = keptAnchor[q]
            val base = i * anchorStep
            val o = q * 4
            val probs = FloatArray(numClasses)
            for (c in 0 until numClasses) probs[c] = raw[base + (5 + c) * channelStep]
            out.add(Detection(Box(keptBox[o], keptBox[o + 1], keptBox[o + 2], keptBox[o + 3]), scoreByAnchor[i], clsByAnchor[i], probs))
        }
        return out
    }

    private fun ensureAnchorCapacity(n: Int) {
        if (sortKeys.size < n) {
            sortKeys = LongArray(n)
            scoreByAnchor = FloatArray(n)
            clsByAnchor = IntArray(n)
        }
    }

    private fun ensureKeptCapacity(n: Int) {
        if (keptAnchor.size < n) {
            keptAnchor = IntArray(n)
            keptBox = FloatArray(n * 4)
        }
    }

    private companion object {
        /**
         * Packs (score, anchor index) so that ascending order of the long is descending score, then
         * ascending anchor index. The score bits are mapped to an int that increases with the float
         * value, inverted (descending), and put in the high word; the index (>= 0) fills the low word.
         */
        fun sortKey(score: Float, index: Int): Long {
            val bits = score.toRawBits()
            val ascending = if (bits < 0) bits xor Int.MAX_VALUE else bits
            return (ascending.inv().toLong() shl 32) or index.toLong()
        }
    }
}
