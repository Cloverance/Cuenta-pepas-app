package app.cuentapepas.core.live

import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.perf.PerfStats
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.session.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBookkeepingTest {
    private fun counts(vararg v: Int) = ClassCounts(v)

    private val seen = PlantCoverage(observed = 10, paused = 2, errored = 1)

    @Test fun plantLogAdvancesByStepAndClosesRecords() {
        val log = PlantLog("s1", firstPlant = 10, plantStep = 2, startedAtMs = 1_000)
        assertEquals(10, log.currentPlant)
        assertEquals(12, log.nextPlant)
        val first = log.advance(counts(1, 2), seen, nowMs = 5_000)!!
        assertEquals(10, first.plantNumber)
        assertEquals(1_000L, first.startedAtMs)
        assertEquals(5_000L, first.endedAtMs)
        assertEquals(counts(1, 2), first.counts)
        assertEquals(10, first.framesObserved)
        assertEquals(2, first.framesPaused)
        assertEquals(1, first.framesErrored)
        assertEquals(12, log.currentPlant)

        val open = log.records(counts(3, 0), seen, nowMs = 6_000, closeCurrent = false)
        assertEquals(listOf(10, 12), open.map { it.plantNumber })
        assertNull(open[1].endedAtMs)
        assertEquals(5_000L, open[1].startedAtMs)

        val done = log.records(counts(3, 0), seen, nowMs = 7_000, closeCurrent = true)
        assertEquals(7_000L, done[1].endedAtMs)
        assertTrue(done.all { it.sessionId == "s1" })
    }

    @Test fun plantLogSupportsNegativeStep() {
        val log = PlantLog("s", firstPlant = 5, plantStep = -1, startedAtMs = 0)
        log.advance(counts(0), seen, 1)
        log.advance(counts(0), seen, 2)
        assertEquals(3, log.currentPlant)
        assertEquals(listOf(5, 4, 3), log.records(counts(0), seen, 3, true).map { it.plantNumber })
    }

    @Test fun descendingPassNeverReachesPlantZero() {
        val log = PlantLog("s", firstPlant = 2, plantStep = -1, startedAtMs = 0)
        assertTrue(log.canAdvance)
        log.advance(counts(1), seen, 1)
        assertEquals(1, log.currentPlant)
        assertFalse(log.canAdvance) // next would be plant 0
        assertEquals(0, log.nextPlant)
        assertThrows(IllegalStateException::class.java) { log.advance(counts(1), seen, 2) }
        assertEquals(1, log.currentPlant) // unchanged by the refused advance
        assertEquals(listOf(2, 1), log.records(counts(1), seen, 3, true).map { it.plantNumber })
    }

    @Test fun stepTwoDescendingStopsBelowOne() {
        val log = PlantLog("s", firstPlant = 4, plantStep = -2, startedAtMs = 0)
        log.advance(counts(0), seen, 1) // 4 -> 2
        assertFalse(log.canAdvance)     // 2 - 2 = 0
    }

    @Test fun firstPlantBelowOneIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlantLog("s", 0, 1, 0) }
    }

    @Test fun plantWithoutObservedFramesIsNotRecorded() {
        val log = PlantLog("s", firstPlant = 1, plantStep = 1, startedAtMs = 0)
        val none = PlantCoverage(observed = 0, paused = 3, errored = 2)
        assertNull(log.advance(counts(0), none, 1)) // plant 1: ghost, dropped
        assertEquals(2, log.currentPlant)
        log.advance(counts(4), seen, 2)             // plant 2: real
        // current plant 3 has no frames either: not saved at finish
        assertEquals(listOf(2), log.records(counts(0), PlantCoverage(), 3, true).map { it.plantNumber })
        // with frames it is saved
        assertEquals(listOf(2, 3), log.records(counts(1), PlantCoverage(observed = 1), 3, true).map { it.plantNumber })
    }

    @Test fun coverageFlags() {
        assertFalse(PlantCoverage().hasData)
        assertFalse(PlantCoverage(paused = 5).hasData)
        assertEquals(7, PlantCoverage(observed = 4, paused = 3, errored = 9).processed)
        assertTrue(PlantCoverage(observed = 1).hasData)
    }

    @Test fun warningTallyWeightsByFrameDistanceAndIgnoresPaused() {
        val t = WarningTally()
        assertTrue(t.fractions().isEmpty()) // no data, not zeros
        t.add(1, emptySet(), paused = false)                           // weight 1, clean
        t.add(5, setOf(QualityWarning.BLURRY), paused = false)         // weight 4, blurry
        t.add(7, setOf(QualityWarning.BLURRY, QualityWarning.TOO_DARK), paused = true) // ignored
        t.add(7, emptySet(), paused = false)                           // same index again: weight 0
        t.add(9, setOf(QualityWarning.TOO_DARK), paused = false)       // weight 2
        val f = t.fractions()
        assertEquals(4.0 / 7.0, f.getValue("BLURRY"), 1e-12)
        assertEquals(2.0 / 7.0, f.getValue("TOO_DARK"), 1e-12)
        assertEquals(0.0, f.getValue("TOO_FAST"), 0.0)
        assertEquals(QualityWarning.values().size, f.size)
    }

    @Test fun batteryRateNeedsDataAndTime() {
        assertNull(SessionStatsAssembler.batteryPctPerHour(null, 80, 3600.0))
        assertNull(SessionStatsAssembler.batteryPctPerHour(90, null, 3600.0))
        assertNull(SessionStatsAssembler.batteryPctPerHour(90, 89, 60.0))
        assertNull(SessionStatsAssembler.batteryPctPerHour(80, 85, 3600.0)) // charging
        assertEquals(10.0, SessionStatsAssembler.batteryPctPerHour(90, 85, 1800.0)!!, 1e-9)
        assertEquals(0.0, SessionStatsAssembler.batteryPctPerHour(90, 90, 1800.0)!!, 0.0)
    }

    @Test fun gpsAccuracyPercentiles() {
        assertEquals(null to null, SessionStatsAssembler.gpsAccuracy(emptyList()))
        val pts = (1..10).map { GpsPoint(it * 1000L, 4.0, -75.0, it.toFloat()) } +
            GpsPoint(0, 4.0, -75.0, Float.NaN) // ignored
        val (median, p90) = SessionStatsAssembler.gpsAccuracy(pts)
        assertEquals(5.5, median!!, 1e-9)
        assertEquals(9.1, p90!!, 1e-9)
    }

    @Test fun statsFromPerfAndSensors() {
        val perf = PerfStats()
        repeat(20) { perf.addLatencyMs(40.0); perf.addFrameIntervalMs(50.0) }
        val s = SessionStatsAssembler.build(
            perf = perf, durationS = 3600.0, delegate = "GPU", maxThermalStatus = 2,
            batteryStartPct = 90, batteryEndPct = 80, warningFractions = mapOf("BLURRY" to 0.1), gps = emptyList(),
        )
        assertEquals(20, s.framesProcessed)
        assertEquals(20.0, s.fpsMedian!!, 1e-9)
        assertEquals(40.0, s.inferenceMsMedian!!, 1e-9)
        assertEquals("GPU", s.delegate)
        assertEquals(10.0, s.batteryPctPerHour!!, 1e-9)
        assertNull(s.gpsAccuracyMedianM)
        assertNull(s.gpsAccuracyP90M)
        assertNull(s.framesErrored) // unknown unless the caller says so
        assertEquals(0, s.droppedPerfSamples)
    }

    @Test fun statsCarryHonestyCounters() {
        val perf = PerfStats()
        perf.addLatencyMs(Double.NaN) // rejected
        perf.addLatencyMs(10.0)
        val s = SessionStatsAssembler.build(
            perf, 10.0, "CPU", 0, null, null, emptyMap(), emptyList(),
            framesErrored = 3, framesDroppedByThrottle = 7, evalLogError = "disk", gpsSaveError = "io", videoIssue = "timeout",
        )
        assertEquals(3, s.framesErrored)
        assertEquals(7, s.framesDroppedByThrottle)
        assertEquals(1, s.droppedPerfSamples)
        assertEquals("disk", s.evalLogError)
        assertEquals("io", s.gpsSaveError)
        assertEquals("timeout", s.videoIssue)
    }

    @Test fun statsWithoutAnyFrameHaveNoNumbers() {
        val s = SessionStatsAssembler.build(
            PerfStats(), Double.NaN, null, -1, null, null, emptyMap(), emptyList(),
        )
        assertEquals(0, s.framesProcessed)
        assertEquals(0.0, s.durationS, 0.0)
        assertNull(s.fpsMedian)
        assertNull(s.inferenceMsP90)
        assertEquals("none", s.delegate)
        assertTrue(s.warningFraction.isEmpty())
    }

    @Test fun thermalSeverity() {
        assertEquals(ThermalSeverity.NO_DATA, ThermalSeverity.of(-1))
        assertEquals(ThermalSeverity.NORMAL, ThermalSeverity.of(0))
        assertEquals(ThermalSeverity.MODERATE, ThermalSeverity.of(2))
        assertEquals(ThermalSeverity.SEVERE, ThermalSeverity.of(3))
        assertEquals(ThermalSeverity.CRITICAL, ThermalSeverity.of(6))
    }
}
