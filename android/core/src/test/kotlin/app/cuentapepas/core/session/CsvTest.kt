package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun headerAndOneRow() {
        val p = PlantRecord("s", 3, ClassCounts(intArrayOf(1, 2, 3, 4, 5)), 0, null, 40)
        assertEquals("plant,green,semi_ripe,ripe,overripe,dry,total,manual\n3,1,2,3,4,5,15,40\n", plantsCsv(listOf(p)))
    }

    @Test fun manualEmptyWhenNull() {
        val p = PlantRecord("s", 1, ClassCounts(intArrayOf(0, 0, 0, 0, 0)), 0, null)
        assertEquals("plant,green,semi_ripe,ripe,overripe,dry,total,manual\n1,0,0,0,0,0,0,\n", plantsCsv(listOf(p)))
    }

    @Test(expected = IllegalArgumentException::class) fun wrongClassCountThrows() {
        plantsCsv(listOf(PlantRecord("s", 1, ClassCounts(intArrayOf(1, 2)), 0, null)))
    }
}
