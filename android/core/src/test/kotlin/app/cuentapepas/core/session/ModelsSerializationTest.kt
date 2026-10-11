package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsSerializationTest {
    private val json = Json

    @Test fun sessionMetaRoundTrip() {
        val m = SessionMeta("s1", "L", 3, Side.B, 1, 2, true, false, 10L, null, SessionStatus.ACTIVE, "0.1", "yolox", mapOf("a" to "b"))
        assertEquals(m, json.decodeFromString<SessionMeta>(json.encodeToString(m)))
    }

    @Test fun plantRecordRoundTrip() {
        val p = PlantRecord("s1", 4, ClassCounts(intArrayOf(1, 2, 3, 4, 5)), 1L, 2L, 17)
        assertEquals(p, json.decodeFromString<PlantRecord>(json.encodeToString(p)))
        val q = p.copy(endedAtMs = null, manualCount = null)
        assertEquals(q, json.decodeFromString<PlantRecord>(json.encodeToString(q)))
    }

    @Test fun otherModelsRoundTrip() {
        val l = Lot("L", "Lote", null, 5000.0, 1L)
        assertEquals(l, json.decodeFromString<Lot>(json.encodeToString(l)))
        val g = GpsPoint(1L, 4.0, -75.0, 3.5f)
        assertEquals(g, json.decodeFromString<GpsPoint>(json.encodeToString(g)))
        val st = SessionStats(10, 1.0, null, null, 5.0, 9.0, "GPU", 1, 80, null, null, mapOf("blur" to 0.1), null, null)
        assertEquals(st, json.decodeFromString<SessionStats>(json.encodeToString(st)))
    }
}
