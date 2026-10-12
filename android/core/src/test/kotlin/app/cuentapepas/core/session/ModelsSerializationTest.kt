package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsSerializationTest {
    private val json = SessionJson

    @Test fun sessionMetaRoundTrip() {
        val m = SessionMeta("s1", "L", 3, Side.B, 1, 2, true, false, 10L, null, SessionStatus.ACTIVE, "0.1", "yolox", mapOf("a" to "b"), 7L)
        assertEquals(m, json.decodeFromString<SessionMeta>(json.encodeToString(m)))
        val ab = m.copy(status = SessionStatus.ABANDONED)
        assertEquals(ab, json.decodeFromString<SessionMeta>(json.encodeToString(ab)))
    }

    @Test fun plantRecordAndManualCountRoundTrip() {
        val p = PlantRecord("s1", 4, ClassCounts(intArrayOf(1, 2, 3, 4, 5)), 1L, 2L)
        assertEquals(p, json.decodeFromString<PlantRecord>(json.encodeToString(p)))
        val q = p.copy(endedAtMs = null)
        assertEquals(q, json.decodeFromString<PlantRecord>(json.encodeToString(q)))
        val m = ManualCount("L", 2, 4, 17, 99L)
        assertEquals(m, json.decodeFromString<ManualCount>(json.encodeToString(m)))
        assertEquals(PlantKey("L", 2, 4), m.key)
    }

    @Test fun otherModelsRoundTrip() {
        val l = Lot("L", "Lote", null, 5000.0, 1L)
        assertEquals(l, json.decodeFromString<Lot>(json.encodeToString(l)))
        val g = GpsPoint(1L, 4.0, -75.0, 3.5f)
        assertEquals(g, json.decodeFromString<GpsPoint>(json.encodeToString(g)))
        val st = SessionStats(10, 1.0, null, null, 5.0, 9.0, "GPU", 1, 80, null, null, mapOf("blur" to 0.1), null, null)
        assertEquals(st, json.decodeFromString<SessionStats>(json.encodeToString(st)))
    }

    @Test fun unknownKeysAreIgnored() {
        val l = json.decodeFromString<Lot>("""{"id":"L","name":"x","totalPlants":null,"cherriesPerKg":1.5,"createdAtMs":3,"futureField":[1,2]}""")
        assertEquals(Lot("L", "x", null, 1.5, 3L), l)
        val g = json.decodeFromString<GpsPoint>("""{"tMs":1,"lat":4.0,"lon":-75.0,"accuracyM":2.0,"extra":"y"}""")
        assertEquals(GpsPoint(1, 4.0, -75.0, 2f), g)
    }

    @Test fun sessionStatsRejectsNonFiniteValues() {
        fun st(fps: Double? = 1.0, dur: Double = 1.0, warn: Map<String, Double> = emptyMap()) =
            SessionStats(1, dur, fps, null, null, null, "CPU", 0, null, null, null, warn, null, null)
        assertThrows(IllegalArgumentException::class.java) { st(fps = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { st(dur = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { st(warn = mapOf("blur" to Double.NaN)) }
        assertNull(st(fps = null).fpsMedian)
    }

    @Test fun legacyPlantAndStatsFilesDecodeWithUnknownCoverage() {
        val p = json.decodeFromString<PlantRecord>(
            """{"sessionId":"s","plantNumber":2,"counts":{"perClass":[1,2]},"startedAtMs":1,"endedAtMs":null}""",
        )
        assertNull(p.framesObserved)
        assertTrue(p.hasData) // unknown is not "no data"
        val st = json.decodeFromString<SessionStats>(
            """{"framesProcessed":3,"durationS":1.0,"fpsMedian":null,"fpsP10":null,"inferenceMsMedian":null,"inferenceMsP90":null,""" +
                """"delegate":"none","maxThermalStatus":-1,"batteryStartPct":null,"batteryEndPct":null,"batteryPctPerHour":null,""" +
                """"warningFraction":{},"gpsAccuracyMedianM":null,"gpsAccuracyP90M":null}""",
        )
        assertNull(st.framesErrored)
        assertNull(st.evalLogError)
        assertNull(st.gpsSaveError)
    }

    @Test fun coverageRoundTripsAndZeroMeansNoData() {
        val p = PlantRecord("s", 1, ClassCounts(intArrayOf(0, 0)), 0, null, framesObserved = 0, framesPaused = 4, framesErrored = 1)
        assertEquals(p, json.decodeFromString<PlantRecord>(json.encodeToString(p)))
        assertFalse(p.hasData)
        assertThrows(IllegalArgumentException::class.java) { p.copy(framesObserved = -1) }
        val st = SessionStats(1, 1.0, null, null, null, null, "CPU", 0, null, null, null, emptyMap(), null, null,
            framesErrored = 2, framesDroppedByThrottle = 5, droppedPerfSamples = 1, evalLogError = "x", gpsSaveError = "y", videoIssue = "z")
        assertEquals(st, json.decodeFromString<SessionStats>(json.encodeToString(st)))
    }
}
