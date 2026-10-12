package app.cuentapepas.data

import android.content.Context
import app.cuentapepas.core.session.GpsPoint
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.ManualCount
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStats
import app.cuentapepas.core.store.CorruptDataException
import app.cuentapepas.core.store.FileSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Suspend facade over [FileSessionStore] (all rules, file layout and exceptions are documented there):
 * every call runs on Dispatchers.IO. Opening the store (lazily, on the first call) runs the startup sweep that
 * marks sessions left ACTIVE by a killed process as ABANDONED. Errors propagate: [java.io.IOException] for failed
 * writes, [CorruptDataException] for unreadable files, IllegalArgument/IllegalStateException for invalid writes.
 */
class SessionStore(private val root: File) {
    constructor(context: Context) : this(context.filesDir)

    private val store: FileSessionStore by lazy { FileSessionStore(root) }

    private suspend fun <T> io(block: FileSessionStore.() -> T): T = withContext(Dispatchers.IO) { store.block() }

    /** The underlying blocking store, for components that already run off the main thread (e.g. the exporter). */
    internal fun blocking(): FileSessionStore = store

    suspend fun lots(): List<Lot> = io { lots() }
    suspend fun saveLot(lot: Lot) = io { saveLot(lot) }

    /** @throws app.cuentapepas.core.store.LotHasSessionsException when the lot still has sessions. */
    suspend fun deleteLot(lotId: String): Boolean = io { deleteLot(lotId) }

    suspend fun sessions(lotId: String? = null): List<SessionMeta> = io { sessions(lotId) }
    suspend fun session(sessionId: String): SessionMeta = io { session(sessionId) }

    /** Creates an ACTIVE session; returns it with the store-assigned `seq`. */
    suspend fun createSession(meta: SessionMeta): SessionMeta = io { createSession(meta) }
    suspend fun updateSession(meta: SessionMeta): SessionMeta = io { updateSession(meta) }

    /** Replaces any COMPLETED session of the same lot/row/side, then completes this one. */
    suspend fun completeSession(sessionId: String, endedAtMs: Long): SessionMeta =
        io { completeSession(sessionId, endedAtMs) }

    suspend fun markReplaced(sessionId: String): SessionMeta = io { markReplaced(sessionId) }

    suspend fun plants(sessionId: String): List<PlantRecord> = io { plants(sessionId) }
    suspend fun savePlants(sessionId: String, plants: List<PlantRecord>) = io { savePlants(sessionId, plants) }

    suspend fun appendGps(sessionId: String, points: List<GpsPoint>) = io { appendGps(sessionId, points) }
    suspend fun gps(sessionId: String): List<GpsPoint> = io { gps(sessionId) }

    suspend fun saveStats(sessionId: String, stats: SessionStats) = io { saveStats(sessionId, stats) }
    suspend fun stats(sessionId: String): SessionStats? = io { stats(sessionId) }

    suspend fun manualCounts(lotId: String): List<ManualCount> = io { manualCounts(lotId) }
    suspend fun allManualCounts(): List<ManualCount> = io { allManualCounts() }
    suspend fun setManualCount(lotId: String, row: Int, plantNumber: Int, count: Int): ManualCount =
        io { setManualCount(lotId, row, plantNumber, count) }

    /** Where the evaluation logger must write frames.jsonl for the session. */
    suspend fun framesFile(sessionId: String): File = io { framesFile(sessionId) }

    /** Sessions the startup sweep marked ABANDONED (ids), so the UI can inform the user once. */
    suspend fun recoveredSessions(): List<String> = io { recoveredSessions }

    /** Sessions the startup sweep could not read (corrupt meta); show them to the user. */
    suspend fun startupIssues(): List<CorruptDataException> = io { startupIssues }
}
