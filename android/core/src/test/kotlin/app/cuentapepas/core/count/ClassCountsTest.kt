package app.cuentapepas.core.count

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClassCountsTest {
    @Test fun totalAndGet() {
        val c = ClassCounts(intArrayOf(3, 0, 7))
        assertEquals(10, c.total)
        assertEquals(3, c[0]); assertEquals(0, c[1]); assertEquals(7, c[2])
    }

    @Test fun zeroHasRequestedSizeAndNoCounts() {
        val z = ClassCounts.zero(4)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), z.perClass)
        assertEquals(0, z.total)
    }

    @Test fun plusAddsPerClass() {
        val sum = ClassCounts(intArrayOf(1, 2, 3)) + ClassCounts(intArrayOf(10, 0, 5))
        assertEquals(ClassCounts(intArrayOf(11, 2, 8)), sum)
    }

    @Test fun plusDoesNotMutateOperands() {
        val a = ClassCounts(intArrayOf(1, 2))
        a + ClassCounts(intArrayOf(5, 5))
        assertArrayEquals(intArrayOf(1, 2), a.perClass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plusWithDifferentClassCountFails() {
        ClassCounts(intArrayOf(1, 2)) + ClassCounts(intArrayOf(1, 2, 3))
    }

    @Test fun equalsAndHashCodeUseContent() {
        val a = ClassCounts(intArrayOf(1, 2, 3))
        val b = ClassCounts(intArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ClassCounts(intArrayOf(1, 2, 4)))
        assertNotEquals(a, ClassCounts(intArrayOf(1, 2)))
    }

    @Test fun jsonRoundTripKeepsEquality() {
        val c = ClassCounts(intArrayOf(4, 0, 9, 1))
        val json = Json.encodeToString(ClassCounts.serializer(), c)
        assertEquals(c, Json.decodeFromString(ClassCounts.serializer(), json))
    }
}
