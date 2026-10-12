package app.cuentapepas.core.store

import app.cuentapepas.core.session.GpsPoint
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.ManualCount
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.SessionJson
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStats
import app.cuentapepas.core.session.SessionStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** True when a session may move from [this] status to [to] (same status = no change). */
fun SessionStatus.canTransitionTo(to: SessionStatus): Boolean = this == to ||
    (this == SessionStatus.ACTIVE && (to == SessionStatus.COMPLETED || to == SessionStatus.ABANDONED)) ||
    (this == SessionStatus.COMPLETED && to == SessionStatus.REPLACED)

enum class SessionFileKind { META, PLANTS, GPS, STATS, FRAMES }

/**
 * JSON files under [root] (blocking, thread-safe within one process; the Android wrapper runs it on Dispatchers.IO):
 * ```
 * lots.json                        List<Lot>
 * meta/seq.txt                     last assigned session sequence number
 * lots/<lotId>/manual_counts.json  List<ManualCount>  (every entry kept; the latest enteredAtMs per plant wins)
 * sessions/<id>/meta.json plants.json gps.jsonl stats.json frames.jsonl
 * ```
 * Rules:
 * - Every write is atomic (tmp + rename) and throws [IOException] on failure.
 * - An undecodable file throws [CorruptDataException]; nothing is recreated or skipped to hide it.
 * - A missing optional file (plants, gps, stats, manual counts, lots) reads as "no data": empty list / null.
 * - Write-path validation throws [IllegalArgumentException] (bad arguments) or [IllegalStateException] (illegal
 *   state or status transition).
 *
 * Startup sweep: opening the store marks every session still ACTIVE as ABANDONED, because ACTIVE can only be left
 * over by a process that was killed mid-recording. Their plants, GPS and stats files are kept untouched, and
 * [recoveredSessions] lists them so the UI can tell the user. A session whose meta cannot be read during the sweep
 * is not touched and is reported in [startupIssues]; later reads of it throw [CorruptDataException].
 */
class FileSessionStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    /** Ids of the sessions the startup sweep changed from ACTIVE to ABANDONED. */
    val recoveredSessions: List<String>

    /** Sessions that could not be examined by the startup sweep. */
    val startupIssues: List<CorruptDataException>

    init {
        ensureDir(root)
        val recovered = ArrayList<String>()
        val issues = ArrayList<CorruptDataException>()
        for (dir in sessionDirs()) {
            try {
                val meta = readMetaOrNull(dir) ?: continue
                if (meta.status == SessionStatus.ACTIVE) {
                    writeMeta(meta.copy(status = SessionStatus.ABANDONED))
                    recovered += meta.id
                }
            } catch (e: CorruptDataException) {
                issues += e
            }
        }
        recoveredSessions = recovered
        startupIssues = issues
    }

    // ---- paths ----

    private val lotsFile get() = File(root, "lots.json")
    private val seqFile get() = File(root, "meta/seq.txt")
    private fun sessionsDir() = File(root, "sessions")
    fun sessionDir(sessionId: String): File = File(sessionsDir(), checkId(sessionId))
    private fun metaFile(id: String) = File(sessionDir(id), "meta.json")
    private fun plantsFile(id: String) = File(sessionDir(id), "plants.json")
    private fun gpsFile(id: String) = File(sessionDir(id), "gps.jsonl")
    private fun statsFile(id: String) = File(sessionDir(id), "stats.json")
    private fun manualFile(lotId: String) = File(root, "lots/${checkId(lotId)}/manual_counts.json")

    /** Where the evaluation logger writes frames.jsonl (the file may not exist). */
    fun framesFile(sessionId: String): File = File(sessionDir(sessionId), "frames.jsonl")

    /** A session file as stored on disk, or null when it does not exist. */
    fun existingFile(sessionId: String, kind: SessionFileKind): File? {
        val f = when (kind) {
            SessionFileKind.META -> metaFile(sessionId)
            SessionFileKind.PLANTS -> plantsFile(sessionId)
            SessionFileKind.GPS -> gpsFile(sessionId)
            SessionFileKind.STATS -> statsFile(sessionId)
            SessionFileKind.FRAMES -> framesFile(sessionId)
        }
        return f.takeIf { it.isFile }
    }

    // ---- lots ----

    fun lots(): List<Lot> = synchronized(lock) { readLots() }

    /** Inserts or replaces the lot with the same id. */
    fun saveLot(lot: Lot) = synchronized(lock) {
        checkId(lot.id)
        require(lot.name.isNotBlank()) { "El nombre del lote no puede estar vacío" }
        lot.totalPlants?.let { require(it > 0) { "totalPlants must be positive: $it" } }
        lot.cherriesPerKg?.let { require(it.isFinite() && it > 0.0) { "cherriesPerKg must be positive: $it" } }
        val all = readLots().toMutableList()
        val i = all.indexOfFirst { it.id == lot.id }
        if (i >= 0) all[i] = lot else all += lot
        writeJson(lotsFile, ListSerializer(Lot.serializer()), all)
    }

    /**
     * Deletes a lot and its manual counts. @throws LotHasSessionsException if any session (any status) belongs to it.
     * @return false when no such lot exists.
     */
    fun deleteLot(lotId: String): Boolean = synchronized(lock) {
        checkId(lotId)
        val all = readLots()
        if (all.none { it.id == lotId }) return@synchronized false
        val n = readAllMetas().count { it.lotId == lotId }
        if (n > 0) throw LotHasSessionsException(lotId, n)
        writeJson(lotsFile, ListSerializer(Lot.serializer()), all.filter { it.id != lotId })
        val dir = manualFile(lotId).parentFile
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Cannot delete $dir")
        true
    }

    // ---- sessions ----

    /** All sessions (of [lotId] when given), oldest first by [SessionMeta.seq]. */
    fun sessions(lotId: String? = null): List<SessionMeta> = synchronized(lock) {
        readAllMetas().filter { lotId == null || it.lotId == lotId }
    }

    /** @throws SessionNotFoundException */
    fun session(sessionId: String): SessionMeta = synchronized(lock) { readMeta(sessionId) }

    /**
     * Creates an ACTIVE session. The store assigns [SessionMeta.seq] (the value in [meta] is ignored) and returns
     * the stored meta. @throws IllegalArgumentException if the lot is unknown, the id is taken/unsafe or the status
     * is not ACTIVE.
     */
    fun createSession(meta: SessionMeta): SessionMeta = synchronized(lock) {
        checkId(meta.id)
        require(meta.status == SessionStatus.ACTIVE) { "A new session must be ACTIVE, got ${meta.status}" }
        require(readLots().any { it.id == meta.lotId }) { "Unknown lot: ${meta.lotId}" }
        require(!metaFile(meta.id).exists()) { "Session id already exists: ${meta.id}" }
        require(meta.plantStep != 0) { "plantStep must not be 0" }
        val stored = meta.copy(seq = nextSeq())
        writeMeta(stored)
        stored
    }

    /**
     * Updates a session. lotId, row, side and seq never change. From ACTIVE any other field may change and the
     * status may become COMPLETED or ABANDONED; from COMPLETED only the status may change (to REPLACED); every other
     * session is immutable. Moving to COMPLETED while another COMPLETED session has the same (lot, row, side) is
     * rejected: use [completeSession], which replaces the old one first.
     */
    fun updateSession(meta: SessionMeta): SessionMeta = synchronized(lock) {
        val old = readMeta(meta.id)
        check(meta.lotId == old.lotId && meta.row == old.row && meta.side == old.side && meta.seq == old.seq) {
            "lotId, row, side and seq of a session cannot change"
        }
        check(old.status.canTransitionTo(meta.status)) { "Illegal status transition ${old.status} -> ${meta.status}" }
        if (old.status != SessionStatus.ACTIVE) {
            check(meta.copy(status = old.status) == old) { "Session ${old.id} is ${old.status} and cannot be edited" }
        }
        if (meta.status == SessionStatus.COMPLETED && old.status != SessionStatus.COMPLETED) {
            val other = completedFor(old, excludeId = old.id)
            check(other.isEmpty()) {
                "Ya hay un recorrido completado para surco ${old.row} lado ${old.side} " +
                    "(${other.joinToString { it.id }}); use completeSession"
            }
        }
        writeMeta(meta)
        meta
    }

    /**
     * Finishes an ACTIVE session as COMPLETED, in this order: any COMPLETED session of the same (lot, row, side)
     * becomes REPLACED, then this one becomes COMPLETED (with [endedAtMs]). So at most one COMPLETED session exists
     * per pass at every instant; a crash between the two steps leaves the new session ACTIVE (it becomes ABANDONED at
     * the next start) and the old one REPLACED, with all data still on disk.
     */
    fun completeSession(sessionId: String, endedAtMs: Long): SessionMeta = synchronized(lock) {
        val s = readMeta(sessionId)
        check(s.status == SessionStatus.ACTIVE) { "Only an ACTIVE session can be completed, ${s.id} is ${s.status}" }
        for (old in completedFor(s, excludeId = s.id)) writeMeta(old.copy(status = SessionStatus.REPLACED))
        val done = s.copy(status = SessionStatus.COMPLETED, endedAtMs = endedAtMs)
        writeMeta(done)
        done
    }

    /** COMPLETED -> REPLACED. @throws IllegalStateException from any other status. */
    fun markReplaced(sessionId: String): SessionMeta = synchronized(lock) {
        val s = readMeta(sessionId)
        check(s.status == SessionStatus.COMPLETED) { "Only a COMPLETED session can be replaced, $sessionId is ${s.status}" }
        s.copy(status = SessionStatus.REPLACED).also { writeMeta(it) }
    }

    // ---- plants ----

    /** Empty when plants.json does not exist yet. */
    fun plants(sessionId: String): List<PlantRecord> = synchronized(lock) {
        readMeta(sessionId)
        readJson(plantsFile(sessionId), ListSerializer(PlantRecord.serializer())) ?: emptyList()
    }

    /**
     * Replaces the plant list of an ACTIVE session. @throws IllegalArgumentException on a duplicate plant number or a
     * record of another session; @throws IllegalStateException when the session is not ACTIVE.
     */
    fun savePlants(sessionId: String, plants: List<PlantRecord>) = synchronized(lock) {
        val s = readMeta(sessionId)
        check(s.status == SessionStatus.ACTIVE) { "Plants can only be saved while the session is ACTIVE (${s.status})" }
        require(plants.all { it.sessionId == sessionId }) { "Every plant record must belong to session $sessionId" }
        val dup = plants.groupingBy { it.plantNumber }.eachCount().filterValues { it > 1 }.keys.sorted()
        require(dup.isEmpty()) { "Número(s) de planta repetido(s): ${dup.joinToString()}" }
        writeJson(plantsFile(sessionId), ListSerializer(PlantRecord.serializer()), plants)
    }

    // ---- gps ----

    /** Appends to gps.jsonl (one JSON object per line) of an ACTIVE session. */
    fun appendGps(sessionId: String, points: List<GpsPoint>) = synchronized(lock) {
        val s = readMeta(sessionId)
        check(s.status == SessionStatus.ACTIVE) { "GPS points can only be appended while the session is ACTIVE (${s.status})" }
        if (points.isEmpty()) return@synchronized
        val text = buildString {
            for (p in points) append(SessionJson.encodeToString(GpsPoint.serializer(), p)).append('\n')
        }
        val file = gpsFile(sessionId)
        ensureDir(file.parentFile)
        FileOutputStream(file, true).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /**
     * All recorded points. An append killed mid-write can leave one unterminated, truncated last line; only that
     * line is ignored. Any other undecodable line throws [CorruptDataException].
     */
    fun gps(sessionId: String): List<GpsPoint> = synchronized(lock) {
        readMeta(sessionId)
        val file = gpsFile(sessionId)
        if (!file.isFile) return@synchronized emptyList()
        val lines = file.readText(Charsets.UTF_8).split('\n')
        val out = ArrayList<GpsPoint>(lines.size)
        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val unterminated = i == lines.lastIndex // text after the last newline
            try {
                out += SessionJson.decodeFromString(GpsPoint.serializer(), line)
            } catch (e: SerializationException) {
                if (unterminated) continue
                throw CorruptDataException(file.path, "línea ${i + 1} ilegible", e)
            } catch (e: IllegalArgumentException) {
                if (unterminated) continue
                throw CorruptDataException(file.path, "línea ${i + 1} ilegible", e)
            }
        }
        out
    }

    // ---- stats ----

    fun saveStats(sessionId: String, stats: SessionStats) = synchronized(lock) {
        readMeta(sessionId)
        writeJson(statsFile(sessionId), SessionStats.serializer(), stats)
    }

    /** Null when no stats were saved (never a fabricated zero). */
    fun stats(sessionId: String): SessionStats? = synchronized(lock) {
        readMeta(sessionId)
        readJson(statsFile(sessionId), SessionStats.serializer())
    }

    // ---- manual counts ----

    /** Every manual count entry of the lot, oldest first. */
    fun manualCounts(lotId: String): List<ManualCount> = synchronized(lock) {
        requireLot(lotId)
        readJson(manualFile(lotId), ListSerializer(ManualCount.serializer())) ?: emptyList()
    }

    /** Manual counts of all lots (input of `PassResolver.calibrationSamples`). */
    fun allManualCounts(): List<ManualCount> = synchronized(lock) {
        readLots().flatMap { readJson(manualFile(it.id), ListSerializer(ManualCount.serializer())) ?: emptyList() }
    }

    /**
     * Records the hand count of a whole plant. The entry is appended with `enteredAtMs` = now, forced to be later than
     * any earlier entry of the same plant so the latest always wins. Re-recording a pass never erases it.
     * @throws LotNotFoundException, IllegalArgumentException for a negative count.
     */
    fun setManualCount(lotId: String, row: Int, plantNumber: Int, count: Int): ManualCount = synchronized(lock) {
        requireLot(lotId)
        require(count >= 0) { "El conteo manual no puede ser negativo: $count" }
        val file = manualFile(lotId)
        val all = (readJson(file, ListSerializer(ManualCount.serializer())) ?: emptyList()).toMutableList()
        val last = all.filter { it.row == row && it.plantNumber == plantNumber }.maxOfOrNull { it.enteredAtMs }
        val entry = ManualCount(lotId, row, plantNumber, count, maxOf(clock(), (last ?: Long.MIN_VALUE) + 1))
        all += entry
        writeJson(file, ListSerializer(ManualCount.serializer()), all)
        entry
    }

    // ---- internals ----

    private fun requireLot(lotId: String) {
        checkId(lotId)
        if (readLots().none { it.id == lotId }) throw LotNotFoundException(lotId)
    }

    private fun readLots(): List<Lot> = readJson(lotsFile, ListSerializer(Lot.serializer())) ?: emptyList()

    private fun completedFor(s: SessionMeta, excludeId: String): List<SessionMeta> = readAllMetas().filter {
        it.status == SessionStatus.COMPLETED && it.id != excludeId &&
            it.lotId == s.lotId && it.row == s.row && it.side == s.side
    }

    private fun sessionDirs(): List<File> =
        sessionsDir().listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    private fun readAllMetas(): List<SessionMeta> =
        sessionDirs().mapNotNull { readMetaOrNull(it) }.sortedBy { it.seq }

    private fun readMeta(id: String): SessionMeta = readMetaOrNull(sessionDir(id)) ?: throw SessionNotFoundException(id)

    /** Null for a directory that never got a meta (e.g. a crash right after mkdir) and holds nothing else. */
    private fun readMetaOrNull(dir: File): SessionMeta? {
        val f = File(dir, "meta.json")
        val meta = readJson(f, SessionMeta.serializer())
        if (meta == null) {
            if (dir.list().isNullOrEmpty()) return null
            throw CorruptDataException(dir.path, "la carpeta de la sesión no tiene meta.json")
        }
        if (meta.id != dir.name) {
            throw CorruptDataException(f.path, "el id '${meta.id}' no coincide con la carpeta '${dir.name}'")
        }
        return meta
    }

    private fun writeMeta(meta: SessionMeta) = writeJson(metaFile(meta.id), SessionMeta.serializer(), meta)

    private fun nextSeq(): Long {
        val stored = if (seqFile.isFile) {
            seqFile.readText().trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: throw CorruptDataException(seqFile.path, "no es un número")
        } else {
            0L
        }
        val maxMeta = readAllMetas().maxOfOrNull { it.seq } ?: 0L
        val next = maxOf(stored, maxMeta) + 1
        writeAtomic(seqFile, next.toString().toByteArray())
        return next
    }

    private fun <T> readJson(file: File, serializer: KSerializer<T>): T? {
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        return try {
            SessionJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw CorruptDataException(file.path, e.message ?: "JSON inválido", e)
        } catch (e: IllegalArgumentException) { // init-block validation (e.g. non-finite stats)
            throw CorruptDataException(file.path, e.message ?: "valores inválidos", e)
        }
    }

    private fun <T> writeJson(file: File, serializer: KSerializer<T>, value: T) =
        writeAtomic(file, SessionJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))

    private fun checkId(id: String): String {
        require(ID_REGEX.matches(id) && id != "." && id != "..") { "Identificador no válido: '$id'" }
        return id
    }

    private companion object {
        val ID_REGEX = Regex("[A-Za-z0-9._-]{1,80}")
    }
}
