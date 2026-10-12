package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON configuration for every persisted session model: tolerant of unknown keys written by newer versions. */
val SessionJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Lot(
    val id: String,
    val name: String,
    val totalPlants: Int?,
    val cherriesPerKg: Double?,
    val createdAtMs: Long,
)

@Serializable
enum class Side { A, B }

/**
 * Lifecycle of a recording session. Allowed transitions:
 * - ACTIVE -> COMPLETED (finished normally) or ACTIVE -> ABANDONED (app killed or closed mid-recording);
 * - COMPLETED -> REPLACED (a newer pass over the same lot/row/side superseded it).
 * COMPLETED, REPLACED and ABANDONED are terminal for new data. Only COMPLETED sessions count for results;
 * ACTIVE, REPLACED and ABANDONED are ignored by [PassResolver].
 */
@Serializable
enum class SessionStatus { ACTIVE, COMPLETED, REPLACED, ABANDONED }

@Serializable
data class SessionMeta(
    val id: String,
    val lotId: String,
    val row: Int,
    val side: Side,
    val firstPlant: Int,
    val plantStep: Int,
    val isCalibrationSample: Boolean,
    val evaluationMode: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val status: SessionStatus,
    val appVersion: String,
    val modelId: String,
    val settings: Map<String, String>,
    /** Store-assigned, strictly increasing across all sessions. Defines "latest" (max seq); [startedAtMs] is display only. */
    val seq: Long,
)

/**
 * Counts of one plant of one pass.
 *
 * Coverage fields: how many processed camera frames were seen while this plant was active. `null` means "unknown"
 * (a record written by an older version); it is NOT 0. [framesObserved] counts frames that were fully processed and
 * counted (not paused, not failed); [framesPaused] frames processed while counting was paused; [framesErrored]
 * frames that failed. A plant with `framesObserved == 0` has no data: its counts are meaningless zeros and every
 * consumer (estimate, calibration) must ignore it ([hasData]).
 */
@Serializable
data class PlantRecord(
    val sessionId: String,
    val plantNumber: Int,
    val counts: ClassCounts,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val framesObserved: Int? = null,
    val framesPaused: Int? = null,
    val framesErrored: Int? = null,
) {
    init {
        require(framesObserved == null || framesObserved >= 0) { "framesObserved must be >= 0: $framesObserved" }
        require(framesPaused == null || framesPaused >= 0) { "framesPaused must be >= 0: $framesPaused" }
        require(framesErrored == null || framesErrored >= 0) { "framesErrored must be >= 0: $framesErrored" }
    }

    /** False only when the plant is known to have been observed in zero frames; unknown (legacy) counts as data. */
    val hasData: Boolean get() = framesObserved != 0
}

/**
 * Hand count of one whole plant (both sides), kept apart from [PlantRecord] so re-recording a pass never erases it.
 * If several exist for the same plant, the one with the greatest [enteredAtMs] is the current one.
 */
@Serializable
data class ManualCount(
    val lotId: String,
    val row: Int,
    val plantNumber: Int,
    val count: Int,
    val enteredAtMs: Long,
) {
    val key: PlantKey get() = PlantKey(lotId, row, plantNumber)
}

@Serializable
data class GpsPoint(val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

/** Null means "no data" (never 0). Every non-null number must be finite. */
@Serializable
data class SessionStats(
    val framesProcessed: Int,
    val durationS: Double,
    val fpsMedian: Double?,
    val fpsP10: Double?,
    val inferenceMsMedian: Double?,
    val inferenceMsP90: Double?,
    val delegate: String,
    val maxThermalStatus: Int,
    val batteryStartPct: Int?,
    val batteryEndPct: Int?,
    val batteryPctPerHour: Double?,
    val warningFraction: Map<String, Double>,
    val gpsAccuracyMedianM: Double?,
    val gpsAccuracyP90M: Double?,
    /** Frames that failed (detector, postprocessing or counting). Null = unknown (older file), never 0. */
    val framesErrored: Int? = null,
    /** Frames skipped because the device was too hot (one in two is dropped). Null = unknown. */
    val framesDroppedByThrottle: Int? = null,
    /** Latency/interval samples rejected as invalid. Null = unknown. */
    val droppedPerfSamples: Int? = null,
    /** Technical text of an evaluation-log failure (frames.jsonl incomplete), or null. */
    val evalLogError: String? = null,
    /** Technical text of GPS save failures that happened during the session (even if retried), or null. */
    val gpsSaveError: String? = null,
    /** Problem with the evaluation video (finalisation timeout, failed segment), or null. */
    val videoIssue: String? = null,
) {
    init {
        require(framesProcessed >= 0) { "framesProcessed must be >= 0: $framesProcessed" }
        require(framesErrored == null || framesErrored >= 0) { "framesErrored must be >= 0: $framesErrored" }
        require(framesDroppedByThrottle == null || framesDroppedByThrottle >= 0) { "framesDroppedByThrottle must be >= 0" }
        require(droppedPerfSamples == null || droppedPerfSamples >= 0) { "droppedPerfSamples must be >= 0" }
        requireFinite("durationS", durationS)
        require(durationS >= 0.0) { "durationS must be >= 0: $durationS" }
        requireFinite("fpsMedian", fpsMedian)
        requireFinite("fpsP10", fpsP10)
        requireFinite("inferenceMsMedian", inferenceMsMedian)
        requireFinite("inferenceMsP90", inferenceMsP90)
        requireFinite("batteryPctPerHour", batteryPctPerHour)
        requireFinite("gpsAccuracyMedianM", gpsAccuracyMedianM)
        requireFinite("gpsAccuracyP90M", gpsAccuracyP90M)
        for ((k, v) in warningFraction) requireFinite("warningFraction[$k]", v)
    }
}

private fun requireFinite(name: String, v: Double?) {
    require(v == null || v.isFinite()) { "$name must be finite: $v" }
}
