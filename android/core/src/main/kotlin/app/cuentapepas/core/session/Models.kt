package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import kotlinx.serialization.Serializable

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

@Serializable
enum class SessionStatus { ACTIVE, COMPLETED, REPLACED }

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
)

@Serializable
data class PlantRecord(
    val sessionId: String,
    val plantNumber: Int,
    val counts: ClassCounts,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val manualCount: Int? = null,
)

@Serializable
data class GpsPoint(val tMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

/** Null means "no data" (never 0). */
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
)
