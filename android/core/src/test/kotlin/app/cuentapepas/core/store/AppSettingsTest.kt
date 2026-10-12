package app.cuentapepas.core.store

import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.quality.QualityThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsTest {
    private class MemKv : KeyValueStore {
        val m = HashMap<String, String>()
        override fun all(): Map<String, String> = HashMap(m)
        override fun putAll(entries: Map<String, String>) {
            m.putAll(entries)
        }
        override fun clear() = m.clear()
    }

    private val spec = ModelSpec.parse(javaClass.getResource("/model_example.json")!!.readText())

    @Test fun defaultsComeFromModelContract() {
        val d = AppSettings.defaults(spec)
        assertEquals(0.30f, d.scoreThreshold, 0f)
        assertEquals(0.45f, d.nmsIou, 0f)
    }

    @Test fun saveLoadRoundTripAndSnapshot() {
        val repo = SettingsRepository(MemKv(), AppSettings.defaults(spec))
        assertEquals(AppSettings.defaults(spec), repo.load())
        val s = AppSettings(
            quality = QualityThresholds(minSharpness = 33.5, onFrames = 7),
            minHits = 5,
            scoreThreshold = 0.25f,
            gpsMaxAccuracyM = 6f,
        )
        repo.save(s)
        assertEquals(s, repo.load())
        assertEquals("33.5", s.snapshot()["quality.minSharpness"])
        assertEquals("5", s.snapshot()["counter.minHits"])
        repo.reset()
        assertEquals(AppSettings.defaults(spec), repo.load())
    }

    @Test fun unreadableValueIsCorruptNotDefaulted() {
        val kv = MemKv().apply { m["counter.minHits"] = "abc" }
        val e = assertThrows(CorruptDataException::class.java) { SettingsRepository(kv, AppSettings()).load() }
        assertEquals("settings:counter.minHits", e.path)
        kv.m["counter.minHits"] = "0"
        assertThrows(CorruptDataException::class.java) { SettingsRepository(kv, AppSettings()).load() }
    }
}
