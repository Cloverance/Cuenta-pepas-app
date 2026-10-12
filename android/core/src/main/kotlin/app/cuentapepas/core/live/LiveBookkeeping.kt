package app.cuentapepas.core.live

import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.perf.PerfStats
import app.cuentapepas.core.perf.percentileOf
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.session.GpsPoint
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.SessionStats

/**
 * Frames seen while one plant was active: [observed] were processed and counted, [paused] were processed while counting
 * was paused, [errored] failed. A plant with no [observed] frame has no data (its counts are not "0 cherries").
 */
data class PlantCoverage(val observed: Int = 0, val paused: Int = 0, val errored: Int = 0) {
    init {
        require(observed >= 0 && paused >= 0 && errored >= 0) { "coverage counters must be >= 0" }
    }

    /** Frames the detector processed for this plant, counted or not. */
    val processed: Int get() = observed + paused

    /** True when at least one frame was counted for the plant. */
    val hasData: Boolean get() = observed > 0
}

/**
 * Plant bookkeeping of a live session: which plant is current, and the records of the plants already closed.
 * Plant numbers advance by [plantStep] (non-zero, may be negative) and never go below 1 ([canAdvance]).
 * A plant that was never observed ([PlantCoverage.hasData] false) is not recorded: no ghost plants.
 * Not thread-safe; use from one place.
 */
class PlantLog(
    private val sessionId: String,
    firstPlant: Int,
    private val plantStep: Int,
    startedAtMs: Long,
) {
    init {
        require(plantStep != 0) { "plantStep must not be 0" }
        require(firstPlant >= 1) { "firstPlant must be >= 1: $firstPlant" }
    }

    var currentPlant: Int = firstPlant
        private set

    /** The plant [advance] will switch to (may be < 1 for a descending pass; check [canAdvance]). */
    val nextPlant: Int get() = currentPlant + plantStep

    /** False when [nextPlant] would be plant 0 or lower (a descending pass reached plant 1). */
    val canAdvance: Boolean get() = nextPlant >= 1

    private var currentStartedAtMs = startedAtMs
    private val closed = ArrayList<PlantRecord>()

    /**
     * Closes the current plant with its final [counts] and [coverage] and moves on to the next one. Returns the
     * closed record, or null when the plant had no observed frame (then nothing is recorded for it).
     * @throws IllegalStateException when [canAdvance] is false.
     */
    fun advance(counts: ClassCounts, coverage: PlantCoverage, nowMs: Long): PlantRecord? {
        check(canAdvance) { "No hay una planta siguiente: la planta $nextPlant no es válida" }
        val record = record(counts, coverage, nowMs, closeIt = true)
        if (record != null) closed += record
        currentPlant += plantStep
        currentStartedAtMs = nowMs
        return record
    }

    /**
     * Every plant record so far: the closed ones plus the current one when it has data. With [closeCurrent] the
     * current record gets `endedAtMs = nowMs` (session finished); otherwise it stays open (`null`), as saved while the
     * session runs.
     */
    fun records(currentCounts: ClassCounts, coverage: PlantCoverage, nowMs: Long, closeCurrent: Boolean): List<PlantRecord> =
        closed + listOfNotNull(record(currentCounts, coverage, nowMs, closeCurrent))

    private fun record(counts: ClassCounts, coverage: PlantCoverage, nowMs: Long, closeIt: Boolean): PlantRecord? {
        if (!coverage.hasData) return null
        return PlantRecord(
            sessionId = sessionId,
            plantNumber = currentPlant,
            counts = counts,
            startedAtMs = currentStartedAtMs,
            endedAtMs = if (closeIt) maxOf(nowMs, currentStartedAtMs) else null,
            framesObserved = coverage.observed,
            framesPaused = coverage.paused,
            framesErrored = coverage.errored,
        )
    }
}

/**
 * Share of processed frames that carried each capture-quality warning. Frames are weighted by the distance between
 * the frame indices actually observed, so frames the UI did not see (the result flow is conflated, throttled frames
 * are skipped) take the state of the observed frame that follows them. Paused frames are ignored.
 */
class WarningTally {
    // Not thread-safe: add() and fractions() must be called from the same thread (the UI/main thread).
    private var total = 0L
    private val counts = LongArray(QualityWarning.values().size)
    private var lastIndex = 0L

    fun add(frameIndex: Long, warnings: Set<QualityWarning>, paused: Boolean) {
        val weight = if (lastIndex == 0L) 1L else (frameIndex - lastIndex).coerceAtLeast(0L)
        if (frameIndex > lastIndex) lastIndex = frameIndex
        if (paused || weight == 0L) return
        total += weight
        for (w in warnings) counts[w.ordinal] += weight
    }

    /** Warning name -> fraction in 0..1 for every warning kind; empty when no frame was observed ("no data"). */
    fun fractions(): Map<String, Double> {
        if (total == 0L) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (w in QualityWarning.values()) out[w.name] = counts[w.ordinal].toDouble() / total
        return out
    }
}

/** Builds the [SessionStats] saved when a live session ends. Missing data stays null, never 0. */
object SessionStatsAssembler {
    /** A battery drop over less than this is too noisy (1 % resolution) to turn into a %/h figure. */
    const val MIN_BATTERY_RATE_S = 300.0

    /** % per hour from the start/end readings; null when unknown, too short, or when the battery charged. */
    fun batteryPctPerHour(startPct: Int?, endPct: Int?, durationS: Double): Double? {
        if (startPct == null || endPct == null || !durationS.isFinite() || durationS < MIN_BATTERY_RATE_S) return null
        if (endPct > startPct) return null
        return (startPct - endPct) / (durationS / 3600.0)
    }

    /** Median and p90 of the finite, non-negative accuracies; (null, null) without points. */
    fun gpsAccuracy(points: List<GpsPoint>): Pair<Double?, Double?> {
        val acc = points.mapNotNull { p -> p.accuracyM.takeIf { it.isFinite() && it >= 0f }?.toDouble() }
        if (acc.isEmpty()) return null to null
        return percentileOf(acc, 0.5) to percentileOf(acc, 0.9)
    }

    /**
     * @param perf latencies/intervals of the frames the detector processed; [SessionStats.framesProcessed] is its
     *   latency count.
     * @param delegate "GPU" or "CPU"; null when the detector never became ready (stored as "none").
     */
    fun build(
        perf: PerfStats,
        durationS: Double,
        delegate: String?,
        maxThermalStatus: Int,
        batteryStartPct: Int?,
        batteryEndPct: Int?,
        warningFractions: Map<String, Double>,
        gps: List<GpsPoint>,
        framesErrored: Int? = null,
        framesDroppedByThrottle: Int? = null,
        evalLogError: String? = null,
        gpsSaveError: String? = null,
        videoIssue: String? = null,
    ): SessionStats {
        val duration = if (durationS.isFinite()) durationS.coerceAtLeast(0.0) else 0.0
        val (gpsMedian, gpsP90) = gpsAccuracy(gps)
        return SessionStats(
            framesProcessed = perf.latencyCount,
            durationS = duration,
            fpsMedian = perf.fpsMedian,
            fpsP10 = perf.fpsP10,
            inferenceMsMedian = perf.inferenceMsMedian,
            inferenceMsP90 = perf.inferenceMsP90,
            delegate = delegate ?: "none",
            maxThermalStatus = maxThermalStatus,
            batteryStartPct = batteryStartPct,
            batteryEndPct = batteryEndPct,
            batteryPctPerHour = batteryPctPerHour(batteryStartPct, batteryEndPct, duration),
            warningFraction = warningFractions,
            gpsAccuracyMedianM = gpsMedian,
            gpsAccuracyP90M = gpsP90,
            framesErrored = framesErrored,
            framesDroppedByThrottle = framesDroppedByThrottle,
            droppedPerfSamples = perf.droppedSamples,
            evalLogError = evalLogError,
            gpsSaveError = gpsSaveError,
            videoIssue = videoIssue,
        )
    }
}

/** Display severity of `PowerManager.THERMAL_STATUS_*` (and -1 = no data), used for the status bar colour. */
enum class ThermalSeverity {
    NO_DATA, NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL;

    companion object {
        fun of(status: Int): ThermalSeverity = when {
            status < 0 -> NO_DATA
            status == 0 -> NORMAL
            status == 1 -> LIGHT
            status == 2 -> MODERATE
            status == 3 -> SEVERE
            else -> CRITICAL // CRITICAL, EMERGENCY, SHUTDOWN
        }
    }
}
