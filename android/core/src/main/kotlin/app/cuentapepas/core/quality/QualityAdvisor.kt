package app.cuentapepas.core.quality

import java.util.EnumSet

enum class QualityWarning { TOO_FAST, BLURRY, TOO_DARK, BACKLIGHT }

/**
 * Warning thresholds. The defaults are provisional and meant to be tuned with field footage.
 *
 * @property minSharpness below this Laplacian variance the frame is [QualityWarning.BLURRY].
 * @property minBrightness below this mean luma the frame is [QualityWarning.TOO_DARK].
 * @property maxBrightness above this mean luma the frame is [QualityWarning.BACKLIGHT].
 * @property maxBrightFraction above this share of very bright pixels the frame is [QualityWarning.BACKLIGHT].
 * @property maxFrameWidthsPerSecond above this camera speed the user is [QualityWarning.TOO_FAST].
 * @property onFrames consecutive faulty frames needed to raise a warning.
 * @property offFrames consecutive good frames needed to clear it.
 */
data class QualityThresholds(
    val minSharpness: Double = 25.0,
    val minBrightness: Double = 45.0,
    val maxBrightness: Double = 215.0,
    val maxBrightFraction: Double = 0.25,
    val maxFrameWidthsPerSecond: Double = 1.0,
    val onFrames: Int = 5,
    val offFrames: Int = 10,
) {
    init {
        require(onFrames >= 1) { "onFrames must be >= 1, got $onFrames" }
        require(offFrames >= 1) { "offFrames must be >= 1, got $offFrames" }
    }
}

/**
 * Measurements of one frame.
 *
 * A NaN or infinite field means the measurement failed and is never treated as good: it counts as
 * a violation of every check that uses it ([sharpness] -> BLURRY, [meanBrightness] -> TOO_DARK and
 * BACKLIGHT, [brightFraction] -> BACKLIGHT, [frameWidthsPerSecond] -> TOO_FAST).
 *
 * @property frameWidthsPerSecond camera speed expressed as frame widths travelled per second.
 */
data class QualitySample(
    val sharpness: Double,
    val meanBrightness: Double,
    val brightFraction: Double,
    val frameWidthsPerSecond: Double,
)

/**
 * Turns per-frame measurements into stable on-screen warnings.
 *
 * Each warning has its own hysteresis: it is raised after [QualityThresholds.onFrames] consecutive
 * faulty frames and cleared after [QualityThresholds.offFrames] consecutive good ones, so a single
 * odd frame never makes the interface flicker. Not thread-safe: call it from the frame-processing thread.
 */
class QualityAdvisor(private val thresholds: QualityThresholds = QualityThresholds()) {

    private val warnings = QualityWarning.values()
    private val active = BooleanArray(warnings.size)
    private val faultyStreak = IntArray(warnings.size)
    private val goodStreak = IntArray(warnings.size)
    private val faulty = BooleanArray(warnings.size)

    /** Feeds one frame and returns a snapshot of the warnings that are active afterwards. */
    fun update(s: QualitySample): Set<QualityWarning> {
        val badSpeed = !s.frameWidthsPerSecond.isFinite()
        val badSharp = !s.sharpness.isFinite()
        val badBrightness = !s.meanBrightness.isFinite()
        val badFraction = !s.brightFraction.isFinite()
        faulty[QualityWarning.TOO_FAST.ordinal] = badSpeed || s.frameWidthsPerSecond > thresholds.maxFrameWidthsPerSecond
        faulty[QualityWarning.BLURRY.ordinal] = badSharp || s.sharpness < thresholds.minSharpness
        faulty[QualityWarning.TOO_DARK.ordinal] = badBrightness || s.meanBrightness < thresholds.minBrightness
        faulty[QualityWarning.BACKLIGHT.ordinal] = badBrightness || badFraction ||
            s.meanBrightness > thresholds.maxBrightness || s.brightFraction > thresholds.maxBrightFraction

        val result = EnumSet.noneOf(QualityWarning::class.java)
        for (i in warnings.indices) {
            if (faulty[i]) {
                faultyStreak[i]++
                goodStreak[i] = 0
                if (!active[i] && faultyStreak[i] >= thresholds.onFrames) active[i] = true
            } else {
                goodStreak[i]++
                faultyStreak[i] = 0
                if (active[i] && goodStreak[i] >= thresholds.offFrames) active[i] = false
            }
            if (active[i]) result.add(warnings[i])
        }
        return result
    }

    /** Forgets all streaks and clears every warning, e.g. when a new session starts. */
    fun reset() {
        active.fill(false)
        faultyStreak.fill(0)
        goodStreak.fill(0)
    }
}
