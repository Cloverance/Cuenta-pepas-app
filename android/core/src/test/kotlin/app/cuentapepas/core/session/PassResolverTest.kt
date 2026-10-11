package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassResolverTest {
    private fun meta(id: String, side: Side = Side.A, status: SessionStatus = SessionStatus.COMPLETED, start: Long = 0, calib: Boolean = false, row: Int = 1) =
        SessionMeta(id, "L", row, side, 1, 1, calib, false, start, null, status, "1", "m", emptyMap())

    private fun plant(sid: String, n: Int, total: Int, manual: Int? = null) =
        PlantRecord(sid, n, ClassCounts(intArrayOf(total, 0, 0, 0, 0)), 0, null, manual)

    @Test fun latestCompletedWinsAndNeverSums() {
        val s = listOf(meta("old", start = 1), meta("new", start = 2))
        val r = PassResolver.effectivePlants(s, listOf(plant("old", 1, 10), plant("new", 1, 7)))
        val recs = r.getValue(PlantKey("L", 1, 1))
        assertEquals(1, recs.size)
        assertEquals("new", recs[0].sessionId)
        assertEquals(7, recs[0].counts.total)
    }

    @Test fun replacedIgnored() {
        val s = listOf(meta("old", start = 1, status = SessionStatus.REPLACED), meta("new", start = 2, status = SessionStatus.REPLACED))
        assertEquals(emptyMap<PlantKey, List<PlantRecord>>(), PassResolver.effectivePlants(s, listOf(plant("old", 1, 10), plant("new", 1, 7))))
        val s2 = listOf(meta("old", start = 5, status = SessionStatus.REPLACED), meta("new", start = 2))
        val r = PassResolver.effectivePlants(s2, listOf(plant("old", 1, 10), plant("new", 1, 7)))
        assertEquals("new", r.getValue(PlantKey("L", 1, 1))[0].sessionId)
    }

    @Test fun sidesGroupedIntoTwoRecords() {
        val s = listOf(meta("a", Side.A), meta("b", Side.B))
        val r = PassResolver.effectivePlants(s, listOf(plant("a", 1, 10), plant("b", 1, 6)))
        assertEquals(1, r.size)
        assertEquals(2, r.getValue(PlantKey("L", 1, 1)).size)
    }

    @Test fun conflictingSession() {
        val s = listOf(meta("a"), meta("r", Side.B, SessionStatus.REPLACED))
        assertEquals("a", PassResolver.conflictingSession(s, "L", 1, Side.A)?.id)
        assertNull(PassResolver.conflictingSession(s, "L", 1, Side.B))
        assertNull(PassResolver.conflictingSession(s, "L", 2, Side.A))
    }

    @Test fun calibrationAveragesSides() {
        val s = listOf(meta("a", Side.A, calib = true), meta("b", Side.B, calib = true), meta("c", row = 2))
        val smp = PassResolver.calibrationSamples(
            s, listOf(plant("a", 1, 10, 30), plant("b", 1, 7, 30), plant("c", 1, 5, 9)),
        )
        assertEquals(1, smp.size)
        assertEquals("L/1/1", smp[0].plantId)
        assertEquals(9, smp[0].visible) // (10+7)/2 = 8.5 -> 9
        assertEquals(30, smp[0].manual)
    }

    @Test fun calibrationSkipsMissingManual() {
        val s = listOf(meta("a", calib = true))
        assertEquals(0, PassResolver.calibrationSamples(s, listOf(plant("a", 1, 10))).size)
    }

    @Test(expected = IllegalArgumentException::class) fun unknownSessionThrows() {
        PassResolver.effectivePlants(emptyList(), listOf(plant("x", 1, 1)))
    }

    @Test(expected = IllegalStateException::class) fun sameStartTimeIsAmbiguous() {
        PassResolver.effectivePlants(listOf(meta("a", start = 1), meta("b", start = 1)), listOf(plant("a", 1, 1), plant("b", 1, 2)))
    }
}
