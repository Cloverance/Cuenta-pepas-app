package app.cuentapepas.core.store

import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.session.GpsPoint
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStats
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.core.session.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun open() = FileSessionStore(tmp.root) { now++ }
    private val lot = Lot("L1", "Lote El Cafetal", 100, null, 0)

    private fun meta(id: String, row: Int = 1, side: Side = Side.A, status: SessionStatus = SessionStatus.ACTIVE) =
        SessionMeta(id, "L1", row, side, 1, 1, false, false, 10, null, status, "1.0", "m", mapOf("k" to "v"), seq = 999)

    private fun plant(sid: String, n: Int) = PlantRecord(sid, n, ClassCounts(intArrayOf(1, 0, 0, 0, 0)), 0, 1)

    private fun store(): FileSessionStore = open().also { it.saveLot(lot) }

    @Test fun seqIsAssignedAndMonotonicAcrossReopen() {
        val s = store()
        assertEquals(1, s.createSession(meta("a")).seq)
        assertEquals(2, s.createSession(meta("b", row = 2)).seq)
        val s2 = open()
        assertEquals(3, s2.createSession(meta("c", row = 3)).seq)
        assertEquals(listOf("a", "b", "c"), s2.sessions().map { it.id })
    }

    @Test fun seqSurvivesDeletedSeqFileUsingMaxMeta() {
        val s = store()
        s.createSession(meta("a"))
        File(tmp.root, "meta/seq.txt").delete()
        assertEquals(2, open().createSession(meta("b", row = 2)).seq)
    }

    @Test fun startupSweepAbandonsActiveAndKeepsPlants() {
        val s = store()
        s.createSession(meta("a"))
        s.savePlants("a", listOf(plant("a", 1)))
        s.createSession(meta("done", row = 2))
        s.completeSession("done", 50)
        val s2 = open()
        assertEquals(listOf("a"), s2.recoveredSessions)
        assertEquals(SessionStatus.ABANDONED, s2.session("a").status)
        assertEquals(SessionStatus.COMPLETED, s2.session("done").status)
        assertEquals(1, s2.plants("a").size)
        assertTrue(open().recoveredSessions.isEmpty())
    }

    @Test fun duplicatePlantNumbersRejected() {
        val s = store()
        s.createSession(meta("a"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            s.savePlants("a", listOf(plant("a", 1), plant("a", 1)))
        }
        assertTrue(e.message!!.contains("1"))
        assertTrue(s.plants("a").isEmpty())
    }

    @Test fun plantsOfOtherSessionRejectedAndOnlyActiveWritable() {
        val s = store()
        s.createSession(meta("a"))
        assertThrows(IllegalArgumentException::class.java) { s.savePlants("a", listOf(plant("zzz", 1))) }
        s.completeSession("a", 5)
        assertThrows(IllegalStateException::class.java) { s.savePlants("a", listOf(plant("a", 1))) }
        assertThrows(IllegalStateException::class.java) { s.appendGps("a", listOf(GpsPoint(1, 1.0, 1.0, 3f))) }
    }

    @Test fun statusTransitionsValidated() {
        val s = store()
        val a = s.createSession(meta("a"))
        assertThrows(IllegalStateException::class.java) { s.updateSession(a.copy(status = SessionStatus.REPLACED)) }
        assertThrows(IllegalStateException::class.java) { s.markReplaced("a") }
        assertThrows(IllegalStateException::class.java) { s.updateSession(a.copy(seq = 77)) }
        val done = s.updateSession(a.copy(status = SessionStatus.COMPLETED, endedAtMs = 9))
        assertThrows(IllegalStateException::class.java) { s.updateSession(done.copy(status = SessionStatus.ABANDONED)) }
        assertThrows(IllegalStateException::class.java) { s.updateSession(done.copy(firstPlant = 5)) }
        s.markReplaced("a")
        assertThrows(IllegalStateException::class.java) { s.updateSession(done.copy(status = SessionStatus.COMPLETED)) }
        assertThrows(IllegalStateException::class.java) { s.completeSession("a", 1) }
    }

    @Test fun replacementKeepsAtMostOneCompleted() {
        val s = store()
        s.createSession(meta("old"))
        s.completeSession("old", 5)
        val n = s.createSession(meta("new"))
        // direct COMPLETED while another exists is refused
        assertThrows(IllegalStateException::class.java) { s.updateSession(n.copy(status = SessionStatus.COMPLETED)) }
        s.completeSession("new", 8)
        assertEquals(SessionStatus.REPLACED, s.session("old").status)
        assertEquals(SessionStatus.COMPLETED, s.session("new").status)
        assertEquals(1, s.sessions().count { it.status == SessionStatus.COMPLETED })
    }

    @Test fun differentSideDoesNotReplace() {
        val s = store()
        s.createSession(meta("a", side = Side.A)); s.completeSession("a", 1)
        s.createSession(meta("b", side = Side.B)); s.completeSession("b", 2)
        assertEquals(SessionStatus.COMPLETED, s.session("a").status)
    }

    @Test fun createValidation() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("x").copy(lotId = "nope")) }
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("x", status = SessionStatus.COMPLETED)) }
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("../evil")) }
        s.createSession(meta("x"))
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("x")) }
        assertThrows(SessionNotFoundException::class.java) { s.session("missing") }
    }

    @Test fun deleteLotOnlyWithoutSessions() {
        val s = store()
        s.createSession(meta("a"))
        assertThrows(LotHasSessionsException::class.java) { s.deleteLot("L1") }
        s.saveLot(Lot("L2", "Otro", null, null, 1))
        assertTrue(s.deleteLot("L2"))
        assertFalse(s.deleteLot("L2"))
        assertEquals(listOf("L1"), s.lots().map { it.id })
    }

    @Test fun gpsAppendOnlyAndTornLastLineTolerated() {
        val s = store()
        s.createSession(meta("a"))
        s.appendGps("a", listOf(GpsPoint(1, 1.5, 2.5, 4f)))
        s.appendGps("a", listOf(GpsPoint(2, 1.6, 2.6, 5f), GpsPoint(3, 1.7, 2.7, 6f)))
        assertEquals(listOf(1L, 2L, 3L), s.gps("a").map { it.tMs })
        val f = File(tmp.root, "sessions/a/gps.jsonl")
        f.appendText("{\"tMs\":4,\"la")
        assertEquals(3, s.gps("a").size)
        assertEquals(1, s.gpsTornLines("a"))
        // the REAL append after a torn write keeps the torn line isolated and every valid point readable
        s.appendGps("a", listOf(GpsPoint(5, 1.0, 1.0, 1f)))
        assertEquals(listOf(1L, 2L, 3L, 5L), s.gps("a").map { it.tMs })
        assertEquals(1, s.gpsTornLines("a"))
        // a complete-looking but undecodable middle line is real corruption
        f.appendText("{\"tMs\":\"x\"}\n")
        s.appendGps("a", listOf(GpsPoint(6, 1.0, 1.0, 1f)))
        assertThrows(CorruptDataException::class.java) { s.gps("a") }
    }

    @Test fun tmpOnlySessionDirIsIncompleteAndSwept() {
        val s = store()
        s.createSession(meta("a"))
        val d = File(tmp.root, "sessions/ghost").apply { mkdirs() }
        File(d, "meta.json.tmp").writeText("{}")
        File(tmp.root, "lots.json.tmp").writeText("[")
        val s2 = open()
        assertFalse(File(d, "meta.json.tmp").exists())
        assertFalse(File(tmp.root, "lots.json.tmp").exists())
        assertEquals(listOf("a"), s2.sessions().map { it.id })
        assertTrue(s2.startupIssues.isEmpty())
        // tmp present after startup: still incomplete, does not break listing or creation
        File(d, "meta.json.tmp").writeText("{}")
        assertEquals(1, s2.sessions().size)
        assertEquals(2, s2.createSession(meta("ghost", row = 2)).seq)
    }

    @Test fun corruptSessionIsIsolatedAndDiscardable() {
        val s = store()
        s.createSession(meta("a"))
        s.createSession(meta("b", row = 2))
        File(tmp.root, "sessions/a/meta.json").writeText("garbage")
        assertEquals(listOf("b"), s.sessions().map { it.id })
        assertEquals(3, s.createSession(meta("c", row = 3)).seq)
        assertEquals(1, s.corruptSessions().size)
        assertFalse(s.discardCorruptSession("b")) // readable: refused
        assertTrue(File(tmp.root, "sessions/b").exists())
        assertTrue(s.discardCorruptSession("a"))
        assertFalse(File(tmp.root, "sessions/a").exists())
        assertTrue(s.corruptSessions().isEmpty())
    }

    @Test fun missingLotsJsonWithSessionsIsCorrupt() {
        val s = store()
        s.createSession(meta("a"))
        File(tmp.root, "lots.json").delete()
        val e = assertThrows(CorruptDataException::class.java) { s.lots() }
        assertTrue(e.message!!.contains("lots.json missing while sessions exist"))
    }

    @Test fun invalidValuesRejectedAtWriteTime() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("x", row = 0)) }
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("x").copy(firstPlant = 0)) }
        assertThrows(IllegalArgumentException::class.java) { s.createSession(meta("x").copy(plantStep = 2)) }
        s.createSession(meta("a").copy(plantStep = -1))
        assertThrows(IllegalArgumentException::class.java) { s.savePlants("a", listOf(plant("a", 0))) }
        assertThrows(IllegalArgumentException::class.java) { s.appendGps("a", listOf(GpsPoint(1, 91.0, 0.0, 1f))) }
        assertThrows(IllegalArgumentException::class.java) { s.appendGps("a", listOf(GpsPoint(1, 0.0, Double.NaN, 1f))) }
        assertThrows(IllegalArgumentException::class.java) { s.appendGps("a", listOf(GpsPoint(1, 0.0, 181.0, 1f))) }
        assertThrows(IllegalArgumentException::class.java) { s.setManualCount("L1", 0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { s.setManualCount("L1", 1, 0, 1) }
        assertTrue(s.gps("a").isEmpty())
    }

    @Test fun corruptJsonThrowsAndIsNeverRecreated() {
        val s = store()
        s.createSession(meta("a"))
        s.savePlants("a", listOf(plant("a", 1)))
        val f = File(tmp.root, "sessions/a/plants.json")
        f.writeText("{not json")
        val e = assertThrows(CorruptDataException::class.java) { s.plants("a") }
        assertTrue(e.path.endsWith("plants.json"))
        assertEquals("{not json", f.readText())
        File(tmp.root, "lots.json").writeText("[")
        assertThrows(CorruptDataException::class.java) { s.lots() }
        File(tmp.root, "sessions/a/meta.json").writeText("garbage")
        assertThrows(CorruptDataException::class.java) { s.session("a") }
        assertTrue(s.sessions().isEmpty()) // isolated, not fatal
        // the startup sweep reports instead of crashing the app at startup
        assertEquals(1, open().startupIssues.size)
    }

    @Test fun invalidStatsOnDiskIsCorrupt() {
        val s = store()
        s.createSession(meta("a"))
        assertNull(s.stats("a"))
        val st = SessionStats(10, 5.0, 12.0, 8.0, 30.0, 40.0, "CPU", 0, null, null, null, mapOf("blur" to 0.1), null, null)
        s.saveStats("a", st)
        assertEquals(st, s.stats("a"))
        val f = File(tmp.root, "sessions/a/stats.json")
        val text = f.readText()
        val patched = text.replace(Regex("\"durationS\":\\s*5.0"), "\"durationS\":-1.0")
        assertTrue("test setup", patched != text)
        f.writeText(patched)
        assertThrows(CorruptDataException::class.java) { s.stats("a") }
    }

    @Test fun manualCountsSurviveAndLatestIsKept() {
        val s = store()
        s.createSession(meta("a")); s.completeSession("a", 1)
        val m1 = s.setManualCount("L1", 1, 4, 40)
        s.createSession(meta("b")); s.completeSession("b", 2) // re-record the pass
        val m2 = s.setManualCount("L1", 1, 4, 44)
        assertTrue(m2.enteredAtMs > m1.enteredAtMs)
        assertEquals(listOf(40, 44), s.manualCounts("L1").map { it.count })
        assertEquals(2, s.allManualCounts().size)
        assertThrows(IllegalArgumentException::class.java) { s.setManualCount("L1", 1, 4, -1) }
        assertThrows(LotNotFoundException::class.java) { s.setManualCount("zz", 1, 4, 1) }
    }

    @Test fun atomicWriteLeavesNoTmpAndReplaces() {
        val s = store()
        s.saveLot(lot.copy(name = "Nuevo"))
        assertEquals("Nuevo", s.lots().single().name)
        assertTrue(tmp.root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun clearManualCountRemovesOnlyThatPlant() {
        val s = store()
        s.setManualCount("L1", 1, 4, 40)
        s.setManualCount("L1", 1, 4, 44)
        s.setManualCount("L1", 1, 5, 7)
        assertTrue(s.clearManualCount("L1", 1, 4))
        assertEquals(listOf(5), s.manualCounts("L1").map { it.plantNumber })
        assertEquals(1, s.allManualCounts().size)
        assertFalse(s.clearManualCount("L1", 1, 4)) // nothing left: no write, no error
        assertTrue(s.clearManualCount("L1", 1, 5))
        assertTrue(s.manualCounts("L1").isEmpty())
        // Survives a reopen and the plant can be counted again.
        val s2 = open()
        assertTrue(s2.manualCounts("L1").isEmpty())
        assertEquals(9, s2.setManualCount("L1", 1, 4, 9).count)
        assertThrows(LotNotFoundException::class.java) { s2.clearManualCount("zz", 1, 4) }
    }

    @Test fun clearManualCountWithoutFileIsFalse() {
        assertFalse(store().clearManualCount("L1", 1, 1))
    }

    @Test fun corruptSessionEntriesCarryTheDirectoryId() {
        val s = store()
        s.createSession(meta("a"))
        File(tmp.root, "sessions/a/meta.json").writeText("garbage")
        val e = s.corruptSessionEntries().single()
        assertEquals("a", e.id)
        assertTrue(s.discardCorruptSession(e.id))
        assertTrue(s.corruptSessionEntries().isEmpty())
    }
}
