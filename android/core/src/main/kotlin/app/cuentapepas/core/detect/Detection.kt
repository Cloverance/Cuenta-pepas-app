package app.cuentapepas.core.detect

import app.cuentapepas.core.geometry.Box

/**
 * One post-processed detection.
 *
 * @property box corners in the coordinate space of the caller (model input pixels straight out of
 *   [YoloxPostprocessor]; source-frame pixels after [Letterbox.toSource]).
 * @property score `obj * max(cls)`.
 * @property cls argmax class index.
 * @property classProbs per-class probabilities of the winning anchor, kept for the maturity vote.
 */
data class Detection(val box: Box, val score: Float, val cls: Int, val classProbs: FloatArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Detection) return false
        return box == other.box && score.compareTo(other.score) == 0 && cls == other.cls &&
            classProbs.contentEquals(other.classProbs)
    }

    override fun hashCode(): Int {
        var result = box.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + cls
        result = 31 * result + classProbs.contentHashCode()
        return result
    }

    override fun toString(): String = "Detection(box=$box, score=$score, cls=$cls, classProbs=${classProbs.contentToString()})"
}
