package app.cuentapepas.core.session

import app.cuentapepas.core.calibration.CalibrationSample
import kotlin.math.roundToInt

data class PlantKey(val lotId: String, val row: Int, val plantNumber: Int)

/** An impossible store state (not ordinary user data), e.g. two sessions sharing one id. */
class DataIntegrityException(message: String) : RuntimeException(message)

enum class IssueKind { MULTIPLE_COMPLETED, UNKNOWN_SESSION, DUPLICATE_PLANT, NO_FRAMES_OBSERVED }

/** A non-fatal anomaly found while resolving passes; [message] is Spanish and can be shown to the user. */
data class ResolutionIssue(val kind: IssueKind, val message: String)

/**
 * [plants] holds at most one effective record per side of every plant; [issues] lists what was ignored or overridden.
 * Records known to have been observed in zero frames ([PlantRecord.hasData] false) are not in [plants]; [noData] lists
 * the plants that lost every side that way (they are "no data", not "0 cherries").
 */
data class Resolution(
    val plants: Map<PlantKey, Map<Side, PlantRecord>>,
    val issues: List<ResolutionIssue>,
    val noData: Set<PlantKey> = emptySet(),
)

data class SkippedPlant(val plantId: String, val reason: String)

/** [samples] feed the calibrator; [skipped] explains (in Spanish) which manually counted plants could not be used. */
data class CalibrationSet(val samples: List<CalibrationSample>, val skipped: List<SkippedPlant>)

/**
 * Resolves repeated passes. Records of the same (lot, row, side, plant) are NEVER summed. For each (lot, row, side)
 * the COMPLETED session with the greatest [SessionMeta.seq] wins as a whole; ACTIVE, REPLACED and ABANDONED
 * sessions are ignored (see [SessionStatus] for the transition rules).
 */
object PassResolver {
    private data class PassKey(val lotId: String, val row: Int, val side: Side)

    private val latestFirst = compareByDescending<SessionMeta> { it.seq }
        .thenByDescending { it.startedAtMs }
        .thenBy { it.id }

    /** The latest (greatest seq) COMPLETED session with the same lot/row/side, or null. Never throws. */
    fun conflictingSession(sessions: List<SessionMeta>, lotId: String, row: Int, side: Side): SessionMeta? =
        sessions
            .filter { it.status == SessionStatus.COMPLETED && it.lotId == lotId && it.row == row && it.side == side }
            .minWithOrNull(latestFirst)

    /**
     * Effective record per plant and side. Non-fatal anomalies become [Resolution.issues]:
     * several COMPLETED sessions for one lot/row/side (latest wins), records of an unknown session (ignored),
     * and a plant number repeated inside one session (the last by end time, then list order, is kept).
     * Records of ACTIVE/REPLACED/ABANDONED sessions are expected and are ignored silently.
     * @throws DataIntegrityException if two sessions share an id.
     */
    fun effectivePlants(sessions: List<SessionMeta>, plants: List<PlantRecord>): Resolution {
        val issues = ArrayList<ResolutionIssue>()
        val byId = HashMap<String, SessionMeta>()
        for (s in sessions) {
            if (byId.put(s.id, s) != null) throw DataIntegrityException("Sesión duplicada: ${s.id}")
        }

        val winners = HashMap<PassKey, SessionMeta>()
        val groups = sessions.filter { it.status == SessionStatus.COMPLETED }
            .groupBy { PassKey(it.lotId, it.row, it.side) }
        for ((k, list) in groups.entries.sortedWith(compareBy({ it.key.lotId }, { it.key.row }, { it.key.side }))) {
            val ordered = list.sortedWith(latestFirst)
            winners[k] = ordered[0]
            if (ordered.size > 1) {
                issues += ResolutionIssue(
                    IssueKind.MULTIPLE_COMPLETED,
                    "Surco ${k.row} lado ${k.side} del lote ${k.lotId}: hay ${ordered.size} recorridos completados " +
                        "(${ordered.joinToString { it.id }}). Se usa el más reciente (${ordered[0].id}) y los demás se ignoran.",
                )
            }
        }

        val unknown = LinkedHashMap<String, Int>()
        val chosen = HashMap<Pair<String, Int>, PlantRecord>() // (sessionId, plant) -> record
        val duplicated = LinkedHashSet<Pair<String, Int>>()
        for (p in plants) {
            val s = byId[p.sessionId]
            if (s == null) {
                unknown.merge(p.sessionId, 1, Int::plus)
                continue
            }
            if (s.status != SessionStatus.COMPLETED) continue
            if (winners[PassKey(s.lotId, s.row, s.side)]?.id != s.id) continue
            val k = s.id to p.plantNumber
            val prev = chosen[k]
            if (prev == null) {
                chosen[k] = p
            } else {
                duplicated += s.id to p.plantNumber
                if ((p.endedAtMs ?: p.startedAtMs) >= (prev.endedAtMs ?: prev.startedAtMs)) chosen[k] = p
            }
        }
        for ((id, n) in unknown) {
            issues += ResolutionIssue(
                IssueKind.UNKNOWN_SESSION,
                "$n registro(s) de plantas pertenecen a la sesión desconocida $id y se ignoran.",
            )
        }
        for ((id, n) in duplicated) {
            issues += ResolutionIssue(
                IssueKind.DUPLICATE_PLANT,
                "La planta $n aparece varias veces en la sesión $id. Se conserva el último registro.",
            )
        }

        val keyOrder = compareBy<PlantKey>({ it.lotId }, { it.row }, { it.plantNumber })
        val out = java.util.TreeMap<PlantKey, MutableMap<Side, PlantRecord>>(keyOrder)
        val empty = java.util.TreeSet<PlantKey>(keyOrder)
        for ((k, rec) in chosen) {
            val s = byId.getValue(k.first)
            val pk = PlantKey(s.lotId, s.row, rec.plantNumber)
            if (!rec.hasData) {
                empty += pk
                issues += ResolutionIssue(
                    IssueKind.NO_FRAMES_OBSERVED,
                    "La planta ${rec.plantNumber} del surco ${s.row} lado ${s.side} no tiene cuadros observados " +
                        "(sin dato); se excluye.",
                )
                continue
            }
            out.getOrPut(pk) { java.util.EnumMap(Side::class.java) }[s.side] = rec
        }
        return Resolution(out, issues, empty.filterTo(java.util.TreeSet(keyOrder)) { it !in out })
    }

    /**
     * One sample per plant that has a [ManualCount] (the latest by enteredAtMs if several), whatever the session's
     * isCalibrationSample flag says (that flag is UI metadata only). `visible` is the rounded mean of the effective
     * sides' `counts.total`; sides observed in zero frames are excluded (see [Resolution.noData]). Plants that cannot be used go to [CalibrationSet.skipped] with a Spanish reason.
     * @throws DataIntegrityException only for impossible store states (see [effectivePlants]).
     */
    fun calibrationSamples(
        sessions: List<SessionMeta>,
        plants: List<PlantRecord>,
        manualCounts: List<ManualCount>,
    ): CalibrationSet {
        val resolution = effectivePlants(sessions, plants)
        val resolved = resolution.plants
        val noData = resolution.noData
        val latest = LinkedHashMap<PlantKey, ManualCount>()
        for (m in manualCounts) {
            val prev = latest[m.key]
            if (prev == null || m.enteredAtMs >= prev.enteredAtMs) latest[m.key] = m
        }
        val samples = ArrayList<CalibrationSample>()
        val skipped = ArrayList<SkippedPlant>()
        val keys = latest.keys.sortedWith(compareBy({ it.lotId }, { it.row }, { it.plantNumber }))
        for (key in keys) {
            val m = latest.getValue(key)
            val id = "${key.lotId}/${key.row}/${key.plantNumber}"
            val sides = resolved[key]
            when {
                m.count < 0 -> skipped += SkippedPlant(id, "Conteo manual inválido")
                sides.isNullOrEmpty() && key in noData ->
                    skipped += SkippedPlant(id, "Sin cuadros observados en esta planta (sin dato)")
                sides.isNullOrEmpty() -> skipped += SkippedPlant(id, "Sin recorrido para esta planta")
                else -> {
                    val visible = sides.values.map { it.counts.total }.average().roundToInt()
                    samples += CalibrationSample(id, visible, m.count)
                }
            }
        }
        return CalibrationSet(samples, skipped)
    }
}
