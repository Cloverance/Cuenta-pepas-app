package app.cuentapepas.core.session

import app.cuentapepas.core.calibration.CalibrationSample
import kotlin.math.roundToInt

data class PlantKey(val lotId: String, val row: Int, val plantNumber: Int)

/**
 * Resolves repeated passes. Records of the same (lot, row, side, plant) are NEVER summed: the most recent
 * COMPLETED session wins; REPLACED and ACTIVE sessions are ignored.
 */
object PassResolver {
    /** The COMPLETED session with the same lot/row/side, if any. More than one is an inconsistent store and throws. */
    fun conflictingSession(sessions: List<SessionMeta>, lotId: String, row: Int, side: Side): SessionMeta? {
        val matches = sessions.filter {
            it.status == SessionStatus.COMPLETED && it.lotId == lotId && it.row == row && it.side == side
        }
        check(matches.size <= 1) {
            "inconsistent store: ${matches.size} COMPLETED sessions for lot=$lotId row=$row side=$side (${matches.joinToString { it.id }})"
        }
        return matches.firstOrNull()
    }

    /**
     * Effective record per (lot, row, side, plant), grouped by [PlantKey] (one per side, so at most 2).
     * Throws on a record whose session is unknown, on duplicate plant numbers within a session, and on two
     * competing sessions with identical start time.
     */
    fun effectivePlants(sessions: List<SessionMeta>, plants: List<PlantRecord>): Map<PlantKey, List<PlantRecord>> =
        resolve(sessions, plants).mapValues { (_, v) -> v.map { it.second } }

    /**
     * Samples from effective records of calibration sessions that have a manual count. With two sides, visible is the
     * rounded mean and both sides must carry the same whole-plant manual count (otherwise throws).
     */
    fun calibrationSamples(sessions: List<SessionMeta>, plants: List<PlantRecord>): List<CalibrationSample> {
        val out = ArrayList<CalibrationSample>()
        for ((key, entries) in resolve(sessions, plants)) {
            val calib = entries.filter { it.first.isCalibrationSample }
            if (calib.isEmpty()) continue
            val manuals = calib.mapNotNull { it.second.manualCount }
            if (manuals.isEmpty()) continue
            require(manuals.size == calib.size) {
                "plant ${key.lotId}/${key.row}/${key.plantNumber}: manual count missing on one side"
            }
            require(manuals.distinct().size == 1) {
                "plant ${key.lotId}/${key.row}/${key.plantNumber}: sides disagree on manual count $manuals"
            }
            val visible = calib.map { it.second.counts.total }.average().roundToInt()
            out += CalibrationSample("${key.lotId}/${key.row}/${key.plantNumber}", visible, manuals[0])
        }
        return out
    }

    private data class SideKey(val key: PlantKey, val side: Side)

    private fun resolve(
        sessions: List<SessionMeta>,
        plants: List<PlantRecord>,
    ): Map<PlantKey, List<Pair<SessionMeta, PlantRecord>>> {
        val byId = HashMap<String, SessionMeta>()
        for (s in sessions) require(byId.put(s.id, s) == null) { "duplicate session id ${s.id}" }
        val seen = HashSet<Pair<String, Int>>()
        val candidates = HashMap<SideKey, MutableList<Pair<SessionMeta, PlantRecord>>>()
        for (p in plants) {
            val s = requireNotNull(byId[p.sessionId]) { "plant record ${p.plantNumber} references unknown session ${p.sessionId}" }
            require(seen.add(p.sessionId to p.plantNumber)) { "duplicate plant ${p.plantNumber} in session ${p.sessionId}" }
            if (s.status != SessionStatus.COMPLETED) continue
            candidates.getOrPut(SideKey(PlantKey(s.lotId, s.row, p.plantNumber), s.side)) { ArrayList() }.add(s to p)
        }
        val grouped = LinkedHashMap<PlantKey, MutableList<Pair<SessionMeta, PlantRecord>>>()
        val ordered = candidates.entries.sortedWith(
            compareBy({ it.key.key.lotId }, { it.key.key.row }, { it.key.key.plantNumber }, { it.key.side }),
        )
        for ((k, list) in ordered) {
            val latest = list.maxOf { it.first.startedAtMs }
            val top = list.filter { it.first.startedAtMs == latest }
            check(top.size == 1) {
                "ambiguous: sessions ${top.joinToString { it.first.id }} start at the same time for plant ${k.key.plantNumber}"
            }
            grouped.getOrPut(k.key) { ArrayList() }.add(top[0])
        }
        return grouped
    }
}
