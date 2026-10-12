package app.cuentapepas.core.store

import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.session.GpsPoint
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.core.session.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.util.zip.ZipFile

class SessionExporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val names = listOf("green", "semi_ripe", "ripe", "overripe", "dry")

    private fun setup(): Pair<FileSessionStore, SessionExporter> {
        val store = FileSessionStore(File(tmp.root, "data"))
        store.saveLot(Lot("L1", "Lote Ñandú #1", 10, null, 0))
        store.createSession(
            SessionMeta("s1", "L1", 3, Side.B, 1, 1, false, false, 1_760_000_000_000, null, SessionStatus.ACTIVE, "1", "m", emptyMap(), 0),
        )
        store.savePlants(
            "s1",
            listOf(
                PlantRecord("s1", 7, ClassCounts(intArrayOf(1, 2, 3, 4, 5)), 0, 1),
                PlantRecord("s1", 8, ClassCounts(intArrayOf(0, 0, 0, 0, 1)), 0, 1),
            ),
        )
        store.appendGps("s1", listOf(GpsPoint(1, 1.0, 2.0, 3f)))
        store.completeSession("s1", 99)
        store.setManualCount("L1", 3, 7, 40)
        return store to SessionExporter(store, File(tmp.root, "exports"), names, ZoneOffset.UTC)
    }

    @Test fun zipHasExpectedEntriesAndCsv() {
        val (_, ex) = setup()
        val f = ex.exportSession("s1")
        assertEquals("Lote_Nandu_1_s3B_20251009-0853.zip", f.name)
        ZipFile(f).use { z ->
            assertEquals(
                setOf("meta.json", "plants.json", "gps.jsonl", "plants.csv"),
                z.entries().asSequence().map { it.name }.toSet(),
            )
            val csv = z.getInputStream(z.getEntry("plants.csv")).readBytes().toString(Charsets.UTF_8)
            assertEquals(
                "plant,green,semi_ripe,ripe,overripe,dry,total,manual\n7,1,2,3,4,5,15,40\n8,0,0,0,0,1,1,\n",
                csv,
            )
        }
        assertTrue(File(tmp.root, "exports").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun framesIncludedWhenPresentAndCorruptDataRefused() {
        val (store, ex) = setup()
        store.framesFile("s1").writeText("{}\n")
        ZipFile(ex.exportSession("s1")).use { assertTrue(it.getEntry("frames.jsonl") != null) }
        File(tmp.root, "data/sessions/s1/plants.json").writeText("oops")
        assertThrows(CorruptDataException::class.java) { ex.exportSession("s1") }
    }
}
