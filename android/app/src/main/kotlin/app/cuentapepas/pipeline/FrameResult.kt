package app.cuentapepas.pipeline

import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.live.PlantCoverage
import app.cuentapepas.core.quality.QualitySample
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.quality.Shift
import app.cuentapepas.core.track.TrackSnapshot
import app.cuentapepas.inference.DelegateFallback

/**
 * Everything the UI needs about the last processed frame. Immutable snapshot, published through
 * `FrameProcessor.results`.
 *
 * Floats that are NaN mean "no data" (not enough frames yet), never zero.
 *
 * @property frameIndex 1-based count of camera frames received in this session (throttled frames count).
 * @property tsNs camera timestamp of the frame in nanoseconds.
 * @property srcWidth width of the upright source frame the boxes live in; 0 before the first frame.
 * @property srcHeight height of the upright source frame; 0 before the first frame.
 * @property plantNumber plant cherries are being attributed to, or null before the first `startPlant`.
 * @property tracks confirmed tracks matched in this frame, boxes in source-frame pixels.
 * @property currentPlantCounts counts of the active plant (zeros when no plant is active).
 * @property sessionCounts counts of the whole session.
 * @property warnings active capture-quality warnings (stable, with hysteresis).
 * @property quality raw measurements of this frame; null before the first processed frame.
 * @property cameraShift estimated camera motion since the previous processed frame, in source pixels.
 * @property rawDetections detections this frame after NMS, before tracking.
 * @property inferenceMs detector time of this frame.
 * @property medianInferenceMs median detector time over the last ~60 processed frames (NaN = no data).
 * @property fps processed frames per second over the last ~60 frames, from the median frame interval (NaN = no data).
 * @property delegate `"GPU"`, `"CPU"` or null while the detector is still initialising.
 * @property delegateFallback why the CPU is used when the GPU was preferred; null otherwise.
 * @property throttled true when the thermal state is SEVERE or worse and every other frame is skipped.
 * @property thermalStatus `PowerManager.THERMAL_STATUS_*` or -1 for "no data".
 * @property paused true while the session is paused (tracking continues, counting does not).
 * @property errorMessage most recent per-frame (or initialisation) error, kept until the next session; non-null
 *   means something went wrong at least once, [errorCount] says how many times.
 * @property errorCount frames that failed since the session started.
 * @property loggingError technical description of an evaluation-log failure, or null.
 * @property plantCoverage frames seen for the active plant (counted / paused / failed); observed == 0 means the
 *   plant's counts are "no data", not zero cherries.
 * @property framesDroppedByThrottle frames skipped so far because the device was too hot.
 */
data class FrameResult(
    val frameIndex: Long,
    val tsNs: Long,
    val srcWidth: Int,
    val srcHeight: Int,
    val plantNumber: Int?,
    val tracks: List<TrackSnapshot>,
    val currentPlantCounts: ClassCounts,
    val sessionCounts: ClassCounts,
    val warnings: Set<QualityWarning>,
    val quality: QualitySample?,
    val cameraShift: Shift,
    val rawDetections: Int,
    val inferenceMs: Float,
    val medianInferenceMs: Float,
    val fps: Float,
    val delegate: String?,
    val delegateFallback: DelegateFallback?,
    val throttled: Boolean,
    val thermalStatus: Int,
    val paused: Boolean,
    val errorMessage: String?,
    val errorCount: Int,
    val loggingError: String?,
    val plantCoverage: PlantCoverage = PlantCoverage(),
    val framesDroppedByThrottle: Int = 0,
) {
    companion object {
        /** State before any frame: no tracks, zero counts, no data. */
        fun initial(numClasses: Int): FrameResult = FrameResult(
            frameIndex = 0, tsNs = 0, srcWidth = 0, srcHeight = 0, plantNumber = null,
            tracks = emptyList(),
            currentPlantCounts = ClassCounts.zero(numClasses), sessionCounts = ClassCounts.zero(numClasses),
            warnings = emptySet(), quality = null, cameraShift = Shift.ZERO, rawDetections = 0,
            inferenceMs = Float.NaN, medianInferenceMs = Float.NaN, fps = Float.NaN,
            delegate = null, delegateFallback = null, throttled = false, thermalStatus = -1, paused = false,
            errorMessage = null, errorCount = 0, loggingError = null,
        )
    }
}
