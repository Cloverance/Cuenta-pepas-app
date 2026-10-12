package app.cuentapepas.core.session

import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.detect.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvTest {
    private val names = listOf("green", "semi_ripe", "ripe", "overripe", "dry")

    @Test fun headerFollowsModelClassOrderAndManualComesFromMap() {
        val spec = ModelSpec.parse(javaClass.getResource("/model_example.json")!!.readText())
        val p = PlantRecord("s", 3, ClassCounts(intArrayOf(1, 2, 3, 4, 5)), 0, null)
        assertEquals(
            "plant,green,semi_ripe,ripe,overripe,dry,total,manual\n3,1,2,3,4,5,15,40\n",
            plantsCsv(listOf(p), spec.classes, mapOf(3 to 40)),
        )
    }

    @Test fun manualEmptyWhenAbsentOrNull() {
        val a = PlantRecord("s", 1, ClassCounts(intArrayOf(0, 0, 0, 0, 0)), 0, null)
        val b = PlantRecord("s", 2, ClassCounts(intArrayOf(1, 0, 0, 0, 0)), 0, null)
        assertEquals(
            "plant,green,semi_ripe,ripe,overripe,dry,total,manual\n1,0,0,0,0,0,0,\n2,1,0,0,0,0,1,\n",
            plantsCsv(listOf(a, b), names, mapOf(2 to null)),
        )
    }

    @Test fun customClassNamesAndEscaping() {
        val p = PlantRecord("s", 1, ClassCounts(intArrayOf(1, 2)), 0, null)
        assertEquals(
            "plant,a,\"b,\"\"c\"\"\",total,manual\n1,1,2,3,0\n",
            plantsCsv(listOf(p), listOf("a", "b,\"c\""), mapOf(1 to 0)),
        )
    }

    @Test fun wrongClassCountThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            plantsCsv(listOf(PlantRecord("s", 1, ClassCounts(intArrayOf(1, 2)), 0, null)), names, emptyMap())
        }
    }
}
