package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassResolverTest {
    private fun meta(
        id: String,
        side: Side = Side.A,
        status: SessionStatus = SessionStatus.COMPLETED,
        seq: Long = 0,
        calib: Boolean = false,
        row: Int = 1,
        start: Long = 0,
    ) = SessionMeta(id, "L", row, side, 1, 1, calib, false, start, null, status, "1", "m", emptyMap(), seq)

    private fun plant(sid: String, n: Int, total: Int, ended: Long? = null, frames: Int? = null) =
        PlantRecord(sid, n, ClassCounts(intArrayOf(total, 0, 0, 0, 0)), 0, ended, framesObserved = frames)

    private fun manual(n: Int, count: Int, at: Long = 0, row: Int = 1) = ManualCount("L", row, n, count, at)

    private val key = PlantKey("L", 1, 1)

    @Test fun latestCompletedWinsNeverSumsAndReportsIssue() {
        val s = listOf(meta("old", seq = 1), meta("new", seq = 2))
        val r = PassResolver.effectivePlants(s, listOf(plant("old", 1, 10), plant("new", 1, 7)))
        val recs = r.plants.getValue(key)
        assertEquals(1, recs.size)
        assertEquals("new", recs.getValue(Side.A).sessionId)
        assertEquals(7, recs.getValue(Side.A).counts.total)
        assertEquals(listOf(IssueKind.MULTIPLE_COMPLETED), r.issues.map { it.kind })
        assertTrue(r.issues[0].message.contains("old") && r.issues[0].message.contains("new"))
    }

    @Test fun orderingUsesSeqNotStartTime() {
        val s = listOf(meta("a", seq = 2, start = 1), meta("b", seq = 1, start = 99))
        assertEquals("a", PassResolver.conflictingSession(s, "L", 1, Side.A)?.id)
        val r = PassResolver.effectivePlants(s, listOf(plant("a", 1, 3), plant("b", 1, 8)))
        assertEquals("a", r.plants.getValue(key).getValue(Side.A).sessionId)
    }

    @Test fun conflictingSessionReturnsLatestCompleted() {
        val s = listOf(meta("a", seq = 1), meta("b", seq = 3), meta("c", seq = 2), meta("r", seq = 9, status = SessionStatus.REPLACED))
        assertEquals("b", PassResolver.conflictingSession(s, "L", 1, Side.A)?.id)
    }

    @Test fun replacedIgnoredSilently() {
        val s = listOf(meta("old", seq = 1, status = SessionStatus.REPLACED), meta("new", seq = 2, status = SessionStatus.REPLACED))
        val r = PassResolver.effectivePlants(s, listOf(plant("old", 1, 10), plant("new", 1, 7)))
        assertEquals(emptyMap<PlantKey, Map<Side, PlantRecord>>(), r.plants)
        assertTrue(r.issues.isEmpty())
        val s2 = listOf(meta("old", seq = 5, status = SessionStatus.REPLACED), meta("new", seq = 2))
        val r2 = PassResolver.effectivePlants(s2, listOf(plant("old", 1, 10), plant("new", 1, 7)))
        assertEquals("new", r2.plants.getValue(key).getValue(Side.A).sessionId)
        assertTrue(r2.issues.isEmpty())
    }

    @Test fun abandonedAndActiveIgnored() {
        val s = listOf(
            meta("ab", seq = 5, status = SessionStatus.ABANDONED),
            meta("ac", Side.B, SessionStatus.ACTIVE, seq = 6),
            meta("ok", seq = 1),
        )
        val r = PassResolver.effectivePlants(s, listOf(plant("ab", 1, 99), plant("ac", 1, 98), plant("ok", 1, 4)))
        assertEquals(setOf(Side.A), r.plants.getValue(key).keys)
        assertEquals("ok", r.plants.getValue(key).getValue(Side.A).sessionId)
        assertTrue(r.issues.isEmpty())
        assertNull(PassResolver.conflictingSession(listOf(meta("ab", status = SessionStatus.ABANDONED)), "L", 1, Side.A))
    }

    @Test fun sidesGroupedIntoOnePlantKeyWithTwoRecords() {
        val s = listOf(meta("a", Side.A), meta("b", Side.B))
        val r = PassResolver.effectivePlants(s, listOf(plant("a", 1, 10), plant("b", 1, 6)))
        assertEquals(1, r.plants.size)
        assertEquals(setOf(Side.A, Side.B), r.plants.getValue(key).keys)
    }

    @Test fun conflictingSessionNullWhenNone() {
        val s = listOf(meta("a"), meta("r", Side.B, SessionStatus.REPLACED))
        assertEquals("a", PassResolver.conflictingSession(s, "L", 1, Side.A)?.id)
        assertNull(PassResolver.conflictingSession(s, "L", 1, Side.B))
        assertNull(PassResolver.conflictingSession(s, "L", 2, Side.A))
    }

    @Test fun unknownSessionIsIssueNotThrow() {
        val r = PassResolver.effectivePlants(emptyList(), listOf(plant("x", 1, 1), plant("x", 2, 1)))
        assertTrue(r.plants.isEmpty())
        assertEquals(listOf(IssueKind.UNKNOWN_SESSION), r.issues.map { it.kind })
    }

    @Test fun duplicatePlantInSessionKeepsLastByEndTime() {
        val s = listOf(meta("a"))
        val r = PassResolver.effectivePlants(s, listOf(plant("a", 1, 5, ended = 20), plant("a", 1, 9, ended = 10)))
        assertEquals(5, r.plants.getValue(key).getValue(Side.A).counts.total)
        assertEquals(listOf(IssueKind.DUPLICATE_PLANT), r.issues.map { it.kind })
        val r2 = PassResolver.effectivePlants(s, listOf(plant("a", 1, 5), plant("a", 1, 9)))
        assertEquals(9, r2.plants.getValue(key).getValue(Side.A).counts.total) // tie: later in list wins
    }

    @Test(expected = DataIntegrityException::class) fun duplicateSessionIdThrows() {
        PassResolver.effectivePlants(listOf(meta("a", seq = 1), meta("a", seq = 2)), emptyList())
    }

    @Test fun calibrationAveragesSides() {
        val s = listOf(meta("a", Side.A, calib = true), meta("b", Side.B, calib = true), meta("c", row = 2))
        val set = PassResolver.calibrationSamples(
            s,
            listOf(plant("a", 1, 10), plant("b", 1, 7), plant("c", 1, 5)),
            listOf(manual(1, 30), manual(1, 9, row = 2)),
        )
        assertEquals(2, set.samples.size)
        assertTrue(set.skipped.isEmpty())
        assertEquals("L/1/1", set.samples[0].plantId)
        assertEquals(9, set.samples[0].visible) // (10+7)/2 = 8.5 -> 9
        assertEquals(30, set.samples[0].manual)
        assertEquals(5, set.samples[1].visible)
    }

    private data class Triple3(val id: String, val visible: Int, val manual: Int)

    @Test fun manualCountSurvivesReRecordedPass() {
        val manuals = listOf(manual(1, 30))
        val before = PassResolver.calibrationSamples(listOf(meta("a", seq = 1)), listOf(plant("a", 1, 10)), manuals)
        assertEquals(listOf(10), before.samples.map { it.visible })
        // the pass is recorded again: old one REPLACED, new one COMPLETED
        val after = PassResolver.calibrationSamples(
            listOf(meta("a", seq = 1, status = SessionStatus.REPLACED), meta("a2", seq = 2)),
            listOf(plant("a", 1, 10), plant("a2", 1, 12)),
            manuals,
        )
        assertEquals(listOf(Triple3("L/1/1", 12, 30)), after.samples.map { Triple3(it.plantId, it.visible, it.manual) })
    }

    @Test fun latestManualCountPerPlantWins() {
        val set = PassResolver.calibrationSamples(
            listOf(meta("a")),
            listOf(plant("a", 1, 10)),
            listOf(manual(1, 20, at = 5), manual(1, 25, at = 9), manual(1, 22, at = 7)),
        )
        assertEquals(listOf(25), set.samples.map { it.manual })
    }

    @Test fun plantWithoutEffectiveSideIsSkippedWithReason() {
        val s = listOf(meta("a", status = SessionStatus.REPLACED))
        val set = PassResolver.calibrationSamples(s, listOf(plant("a", 1, 10)), listOf(manual(1, 30), manual(7, 4)))
        assertTrue(set.samples.isEmpty())
        assertEquals(
            listOf(SkippedPlant("L/1/1", "Sin recorrido para esta planta"), SkippedPlant("L/1/7", "Sin recorrido para esta planta")),
            set.skipped,
        )
    }

    @Test fun negativeManualCountSkipped() {
        val set = PassResolver.calibrationSamples(listOf(meta("a")), listOf(plant("a", 1, 10)), listOf(manual(1, -3)))
        assertTrue(set.samples.isEmpty())
        assertEquals(listOf(SkippedPlant("L/1/1", "Conteo manual inválido")), set.skipped)
    }

    @Test fun calibrationDoesNotRequireCalibrationFlag() {
        val set = PassResolver.calibrationSamples(listOf(meta("a", calib = false)), listOf(plant("a", 1, 10)), listOf(manual(1, 30)))
        assertEquals(1, set.samples.size)
    }

    @Test fun plantsObservedInZeroFramesAreExcludedAndReported() {
        val s = listOf(meta("a"))
        val r = PassResolver.effectivePlants(s, listOf(plant("a", 1, 0, frames = 0), plant("a", 2, 5, frames = 12)))
        assertEquals(setOf(PlantKey("L", 1, 2)), r.plants.keys)
        assertEquals(setOf(key), r.noData)
        assertEquals(listOf(IssueKind.NO_FRAMES_OBSERVED), r.issues.map { it.kind })
    }

    @Test fun legacyRecordsWithoutCoverageAreStillUsed() {
        val r = PassResolver.effectivePlants(listOf(meta("a")), listOf(plant("a", 1, 3, frames = null)))
        assertEquals(setOf(key), r.plants.keys)
        assertTrue(r.noData.isEmpty())
        assertTrue(r.issues.isEmpty())
    }

    @Test fun oneSideWithoutDataLeavesTheOtherSide() {
        val s = listOf(meta("a", Side.A), meta("b", Side.B))
        val r = PassResolver.effectivePlants(s, listOf(plant("a", 1, 10, frames = 8), plant("b", 1, 0, frames = 0)))
        assertEquals(setOf(Side.A), r.plants.getValue(key).keys)
        assertTrue(r.noData.isEmpty()) // the plant still has data from side A
    }

    @Test fun calibrationExcludesNoDataPlantsWithSpecificReason() {
        val s = listOf(meta("a", Side.A), meta("b", Side.B))
        val set = PassResolver.calibrationSamples(
            s,
            listOf(
                plant("a", 1, 0, frames = 0), plant("b", 1, 0, frames = 0), // plant 1: no data on both sides
                plant("a", 2, 10, frames = 5), plant("b", 2, 0, frames = 0), // plant 2: only side A counts, no fake 0 averaged in
            ),
            listOf(manual(1, 30), manual(2, 20)),
        )
        assertEquals(listOf("L/1/2"), set.samples.map { it.plantId })
        assertEquals(10, set.samples[0].visible)
        assertEquals(listOf(SkippedPlant("L/1/1", "Sin cuadros observados en esta planta (sin dato)")), set.skipped)
    }
}
