package app.cuentapepas.core.estimate

import app.cuentapepas.core.calibration.CalibrationSample
import app.cuentapepas.core.calibration.SyntheticSamples
import app.cuentapepas.core.count.ClassCounts
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.SplittableRandom
import kotlin.math.abs

class LotEstimatorTest {
    private val assumption = "Supone que las plantas recorridas representan el lote; lo no recorrido no se interpola"
    private val calibration = SyntheticSamples.generate(n = 30, seed = 42L)

    private fun plant(id: String, vararg sides: IntArray) = PlantVisible(id, sides.map { ClassCounts(it) })

    /** One-sided plants with random counts for 3 maturity classes. */
    private fun randomPlants(n: Int, seed: Long = 3L): List<PlantVisible> {
        val rng = SplittableRandom(seed)
        return List(n) { plant("p$it", intArrayOf(rng.nextInt(10, 60), rng.nextInt(5, 40), rng.nextInt(20, 120))) }
    }

    private fun meanVisible(plants: List<PlantVisible>) =
        plants.sumOf { p -> p.sides.sumOf { it.total }.toDouble() / p.sides.size } / plants.size

    @Test fun fewPlantsGiveInsufficientEvidence() {
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(5), calibration, 550.0))
        assertEquals(LotStatus.INSUFFICIENT_EVIDENCE, e.status)
        assertNull(e.visiblePerPlant); assertNull(e.maturityShare); assertNull(e.totalCherries); assertNull(e.totalKg)
        assertEquals(listOf("Evidencia insuficiente: 5 de 10 plantas mínimas", assumption), e.notes)
        // Coverage is a fact about the walk, not an estimate: it is reported in every state.
        assertEquals(5, e.plantsVisited)
        assertEquals(0.005, e.coverage!!, 1e-12)
    }

    @Test fun withoutCalibrationOnlyVisibleCherriesAreReported() {
        val plants = randomPlants(12)
        val e = LotEstimator.estimate(LotInput(1000, plants, emptyList(), 550.0))
        assertEquals(LotStatus.VISIBLE_ONLY, e.status)
        assertNull(e.totalCherries); assertNull(e.totalKg)
        val vpp = e.visiblePerPlant!!
        assertEquals(meanVisible(plants), vpp.value, 1e-12)
        assertTrue("ci ${vpp.ci} vs ${vpp.value}", vpp.ci.contains(vpp.value))
        assertEquals(3, e.maturityShare!!.size)
        assertEquals(
            listOf("Sin calibración: se reportan pepas visibles, no cosecha", "Se necesitan al menos 10 plantas de muestra", assumption),
            e.notes,
        )
        assertEquals(0.012, e.coverage!!, 1e-12)
        assertTrue(!e.calibration.usable)
    }

    @Test fun calibratedTotalIsNearKTimesNTimesMeanAndItsCiContainsIt() {
        val plants = randomPlants(12)
        val e = LotEstimator.estimate(LotInput(1000, plants, calibration, null))
        assertEquals(LotStatus.CALIBRATED, e.status)
        val mean = meanVisible(plants)
        val total = e.totalCherries!!
        assertEquals(1000 * e.calibration.k!! * mean, total.value, 1e-6)
        assertTrue("total ${total.value} vs ${3000 * mean}", abs(total.value / (3000 * mean) - 1) < 0.07)
        assertTrue("ci ${total.ci} vs ${3000 * mean}", total.ci.contains(3000 * mean))
        assertTrue("ci ${total.ci} vs ${total.value}", total.ci.contains(total.value))
        assertEquals(listOf(assumption), e.notes)
        assertEquals(0, e.skippedReplicates)
    }

    @Test fun totalCiCombinesCalibrationAndPlantUncertainty() {
        // Joint bootstrap: the relative width of the total CI must exceed each source's own relative width.
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(12), calibration, null))
        val total = e.totalCherries!!
        val relTotal = total.ci.width / total.value
        val relK = e.calibration.kCi!!.width / e.calibration.k!!
        val relVisible = e.visiblePerPlant!!.ci.width / e.visiblePerPlant!!.value
        assertTrue("total $relTotal vs k $relK", relTotal > relK)
        assertTrue("total $relTotal vs visible $relVisible", relTotal > relVisible)
    }

    @Test fun visiblePerPlantDoesNotDependOnCalibrationBeingPresent() {
        val plants = randomPlants(12)
        val calibrated = LotEstimator.estimate(LotInput(1000, plants, calibration, null))
        val visibleOnly = LotEstimator.estimate(LotInput(1000, plants, emptyList(), null))
        assertEquals(visibleOnly.visiblePerPlant, calibrated.visiblePerPlant)
        assertEquals(visibleOnly.maturityShare, calibrated.maturityShare)
    }

    @Test fun twoSidedPlantUsesTheMeanOfItsSides() {
        // Five one-sided plants (total 10) and five two-sided plants with side totals 10 and 20 -> 15.
        val oneSide = List(5) { plant("a$it", intArrayOf(1, 2, 7)) }
        val twoSides = List(5) { plant("b$it", intArrayOf(2, 4, 4), intArrayOf(4, 6, 10)) }
        val e = LotEstimator.estimate(LotInput(null, oneSide + twoSides, emptyList(), null))
        assertEquals(12.5, e.visiblePerPlant!!.value, 1e-12)
        // Σ per-class side means = 5·(1,2,7) + 5·(3,5,7) = (20, 35, 70); Σ visible = 125.
        val shares = e.maturityShare!!.map { it.value }
        assertEquals(0.16, shares[0], 1e-12); assertEquals(0.28, shares[1], 1e-12); assertEquals(0.56, shares[2], 1e-12)
    }

    @Test fun maturitySharesSumToOne() {
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(25, seed = 9L), calibration, null))
        assertEquals(1.0, e.maturityShare!!.sumOf { it.value }, 1e-9)
        for (s in e.maturityShare!!) assertTrue("${s.ci} vs ${s.value}", s.ci.low >= 0.0 && s.ci.high <= 1.0)
    }

    @Test fun missingCherriesPerKgLeavesKgNull() {
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(12), calibration, null))
        assertNotNull(e.totalCherries)
        assertNull(e.totalKg)
    }

    @Test fun cherriesPerKgConvertsTotalAndInterval() {
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(12), calibration, 550.0))
        val total = e.totalCherries!!; val kg = e.totalKg!!
        assertEquals(total.value / 550.0, kg.value, 1e-9)
        assertEquals(total.ci.low / 550.0, kg.ci.low, 1e-9)
        assertEquals(total.ci.high / 550.0, kg.ci.high, 1e-9)
    }

    @Test fun nonPositiveCherriesPerKgLeavesKgNullWithNote() {
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(12), calibration, 0.0))
        assertNull(e.totalKg)
        assertTrue(e.notes.contains("Las pepas por kg deben ser mayores que 0; no se calcula el peso"))
    }

    @Test fun missingTotalPlantsLeavesTotalAndCoverageNull() {
        val e = LotEstimator.estimate(LotInput(null, randomPlants(12), calibration, 550.0))
        assertEquals(LotStatus.CALIBRATED, e.status)
        assertNull(e.totalCherries); assertNull(e.totalKg); assertNull(e.coverage)
        assertNotNull(e.visiblePerPlant)
        assertEquals(listOf("Falta el número total de plantas del lote", assumption), e.notes)
    }

    @Test fun totalPlantsBelowVisitedPlantsIsTreatedAsUnknown() {
        val e = LotEstimator.estimate(LotInput(8, randomPlants(12), calibration, 550.0))
        assertNull(e.coverage); assertNull(e.totalCherries); assertNull(e.totalKg)
        assertEquals(
            listOf("El total de plantas del lote (8) es menor que las plantas recorridas (12); revise el dato", assumption),
            e.notes,
        )
    }

    @Test fun noVisibleCherriesGivesNoMaturityShare() {
        val plants = List(10) { plant("p$it", intArrayOf(0, 0, 0)) }
        val e = LotEstimator.estimate(LotInput(100, plants, emptyList(), null))
        assertEquals(LotStatus.VISIBLE_ONLY, e.status)
        assertEquals(0.0, e.visiblePerPlant!!.value, 0.0)
        assertNull(e.maturityShare)
        assertTrue(e.notes.contains("No se vieron pepas en las plantas recorridas; no hay proporción de madurez"))
        assertEquals(assumption, e.notes.last())
    }

    @Test fun degenerateResamplesAreSkippedAndReported() {
        // Calibration where 9 of 10 sample plants show no visible cherries: ~35 % of resamples have Σvisible* = 0.
        val badCalibration = List(9) { CalibrationSample("z$it", 0, 5) } + CalibrationSample("v", 20, 60)
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(12), badCalibration, null))
        assertEquals(LotStatus.CALIBRATED, e.status)
        assertTrue("skipped ${e.skippedReplicates}", e.skippedReplicates in 400..1100)
        assertTrue(e.totalCherries!!.ci.low.isFinite() && e.totalCherries!!.ci.high.isFinite())
        assertTrue(e.notes.contains("Se descartaron ${e.skippedReplicates} de 2000 réplicas bootstrap: quedaron descartadas de al menos un intervalo"))
        assertEquals(assumption, e.notes.last())
    }

    @Test fun sameSeedGivesSameEstimate() {
        val input = LotInput(1000, randomPlants(15), calibration, 550.0)
        assertEquals(LotEstimator.estimate(input), LotEstimator.estimate(input))
        assertEquals(LotEstimator.estimate(input, seed = 5L), LotEstimator.estimate(input, seed = 5L))
    }

    @Test fun estimateSurvivesJsonRoundTrip() {
        val e = LotEstimator.estimate(LotInput(1000, randomPlants(12), calibration, 550.0))
        val json = Json.encodeToString(LotEstimate.serializer(), e)
        assertEquals(e, Json.decodeFromString(LotEstimate.serializer(), json))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plantWithThreeSidesIsRejected() {
        plant("x", intArrayOf(1), intArrayOf(2), intArrayOf(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plantWithoutSidesIsRejected() {
        PlantVisible("x", emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun mixedClassCountsAreRejected() {
        val plants = randomPlants(11) + plant("odd", intArrayOf(1, 2))
        LotEstimator.estimate(LotInput(1000, plants, emptyList(), null))
    }

    @Test fun plantsWithoutObservedFramesAreExcluded() {
        val real = randomPlants(10)
        val ghosts = List(5) { PlantVisible("g$it", listOf(ClassCounts(intArrayOf(0, 0, 0))), framesObserved = 0) }
        val e = LotEstimator.estimate(LotInput(100, real + ghosts, emptyList(), null))
        assertEquals(10, e.plantsVisited)
        assertEquals(0.1, e.coverage!!, 1e-12)
        assertEquals(meanVisible(real), e.visiblePerPlant!!.value, 1e-9) // ghosts do not drag the mean to 0
        assertTrue(e.notes.contains("Se excluyeron 5 planta(s) sin cuadros observados (sin dato)"))
    }

    @Test fun unknownCoverageIsUsedAndGhostsCanLeaveInsufficientEvidence() {
        val legacy = randomPlants(10).map { it.copy(framesObserved = null) }
        assertEquals(10, LotEstimator.estimate(LotInput(null, legacy, emptyList(), null)).plantsVisited)
        val ghosts = randomPlants(10).map { it.copy(framesObserved = 0) }
        val e = LotEstimator.estimate(LotInput(null, ghosts, emptyList(), null))
        assertEquals(LotStatus.INSUFFICIENT_EVIDENCE, e.status)
        assertEquals(0, e.plantsVisited)
        assertNull(e.visiblePerPlant)
    }

    @Test fun fromRecordsDropsSidesWithoutData() {
        fun rec(total: Int, frames: Int?) =
            app.cuentapepas.core.session.PlantRecord("s", 1, ClassCounts(intArrayOf(total, 0)), 0, null, framesObserved = frames)
        val both = PlantVisible.fromRecords("p", listOf(rec(10, 5), rec(0, 0)))!!
        assertEquals(1, both.sides.size)
        assertEquals(5, both.framesObserved)
        assertNull(PlantVisible.fromRecords("p", listOf(rec(0, 0))))
        assertNull(PlantVisible.fromRecords("p", listOf(rec(3, null), rec(2, 4)))!!.framesObserved)
    }
}
