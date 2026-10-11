package app.cuentapepas.core.estimate

import app.cuentapepas.core.calibration.CalibrationResult
import app.cuentapepas.core.calibration.CalibrationSample
import app.cuentapepas.core.calibration.RatioCalibrator
import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.stats.Bootstrap
import app.cuentapepas.core.stats.Estimate
import app.cuentapepas.core.stats.Interval
import kotlinx.serialization.Serializable
import java.util.SplittableRandom

/** Visible cherries of one plant, one [ClassCounts] per side walked (1 or 2). */
@Serializable
data class PlantVisible(val plantId: String, val sides: List<ClassCounts>) {
    init {
        require(sides.size in 1..2) { "a plant has 1 or 2 sides, got ${sides.size} ($plantId)" }
    }
}

@Serializable
data class LotInput(
    val totalPlants: Int?,
    val plants: List<PlantVisible>,
    val calibrationSamples: List<CalibrationSample>,
    val cherriesPerKg: Double?,
)

@Serializable
enum class LotStatus { INSUFFICIENT_EVIDENCE, VISIBLE_ONLY, CALIBRATED }

/**
 * Lot harvest estimate. Numeric fields are null whenever they cannot be backed by evidence; null never means 0.
 * [coverage] and [plantsVisited] are facts about the walk and are reported in every state.
 * [skippedReplicates] counts bootstrap replicates dropped from at least one interval (no visible cherries).
 */
@Serializable
data class LotEstimate(
    val status: LotStatus,
    val plantsVisited: Int,
    val coverage: Double?,
    val visiblePerPlant: Estimate?,
    val maturityShare: List<Estimate>?,
    val totalCherries: Estimate?,
    val totalKg: Estimate?,
    val calibration: CalibrationResult,
    val skippedReplicates: Int,
    val notes: List<String>,
)

object LotEstimator {
    const val ASSUMPTION_NOTE =
        "Supone que las plantas recorridas representan el lote; lo no recorrido no se interpola"

    // Keeps the calibration resampling stream independent of the plant resampling stream.
    private const val CALIBRATION_STREAM_SALT = 0x5DEECE66DL

    fun estimate(
        input: LotInput,
        minPlants: Int = 10,
        confidence: Double = 0.90,
        replicates: Int = 2000,
        seed: Long = 20261011L,
    ): LotEstimate {
        require(replicates > 0) { "replicates must be positive" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be in (0, 1)" }
        val plants = input.plants
        val classSizes = plants.flatMap { p -> p.sides.map { it.perClass.size } }.distinct()
        require(classSizes.size <= 1) { "all plants must use the same number of classes, got $classSizes" }
        val numClasses = classSizes.firstOrNull() ?: 0

        val calibration = RatioCalibrator.fit(input.calibrationSamples, 10, confidence, replicates, seed)

        val totalPlants = input.totalPlants
        val coverageOk = totalPlants != null && totalPlants > 0 && totalPlants >= plants.size
        val coverage = if (coverageOk) plants.size.toDouble() / totalPlants!! else null
        val totalPlantsNote = if (totalPlants != null && totalPlants < plants.size) {
            "El total de plantas del lote ($totalPlants) es menor que las plantas recorridas (${plants.size}); revise el dato"
        } else null

        if (plants.size < minPlants || plants.isEmpty()) {
            return LotEstimate(
                LotStatus.INSUFFICIENT_EVIDENCE, plants.size, coverage, null, null, null, null, calibration, 0,
                listOfNotNull(
                    "Evidencia insuficiente: ${plants.size} de $minPlants plantas mínimas",
                    totalPlantsNote,
                    ASSUMPTION_NOTE,
                ),
            )
        }

        val n = plants.size
        val visibleOf = DoubleArray(n) { i -> plants[i].sides.sumOf { it.total }.toDouble() / plants[i].sides.size }
        val classOf = Array(n) { i ->
            DoubleArray(numClasses) { c -> plants[i].sides.sumOf { it[c] }.toDouble() / plants[i].sides.size }
        }
        val sumVisible = visibleOf.sum()
        val meanVisible = sumVisible / n
        val classSums = DoubleArray(numClasses) { c -> classOf.sumOf { it[c] } }

        val calibrated = calibration.usable
        val estimateTotal = calibrated && coverageOk
        val plantRng = SplittableRandom(seed)
        val calRng = SplittableRandom(seed xor CALIBRATION_STREAM_SALT)
        val calVisible = IntArray(input.calibrationSamples.size) { input.calibrationSamples[it].visible }
        val calManual = IntArray(input.calibrationSamples.size) { input.calibrationSamples[it].manual }

        // Joint bootstrap: plants and calibration samples are resampled independently in every replicate.
        val meanStar = DoubleArray(replicates)
        val shareStar = Array(numClasses) { DoubleArray(replicates) }
        val totalStar = DoubleArray(replicates)
        var shareValid = 0
        var totalValid = 0
        var skipped = 0
        val classAcc = DoubleArray(numClasses)
        for (b in 0 until replicates) {
            var sv = 0.0
            classAcc.fill(0.0)
            repeat(n) {
                val i = plantRng.nextInt(n)
                sv += visibleOf[i]
                for (c in 0 until numClasses) classAcc[c] += classOf[i][c]
            }
            var dropped = false
            meanStar[b] = sv / n
            if (sv > 0.0) {
                for (c in 0 until numClasses) shareStar[c][shareValid] = classAcc[c] / sv
                shareValid++
            } else {
                dropped = true
            }
            if (calibrated) {
                val kb = RatioCalibrator.resampledRatio(calVisible, calManual, calRng)
                if (kb.isNaN()) dropped = true
                else if (estimateTotal) totalStar[totalValid++] = totalPlants!! * kb * (sv / n)
            }
            if (dropped) skipped++
        }

        val visiblePerPlant = Estimate(meanVisible, Bootstrap.percentile(meanStar, confidence))
        val maturityShare: List<Estimate>? =
            if (sumVisible > 0.0 && shareValid > 0) {
                List(numClasses) { c ->
                    Estimate(classSums[c] / sumVisible, Bootstrap.percentile(shareStar[c].copyOf(shareValid), confidence))
                }
            } else null

        val notes = ArrayList<String>()
        var totalCherries: Estimate? = null
        var totalKg: Estimate? = null
        val status: LotStatus
        if (!calibrated) {
            status = LotStatus.VISIBLE_ONLY
            notes += "Sin calibración: se reportan pepas visibles, no cosecha"
            calibration.reason?.let { notes += it }
        } else {
            status = LotStatus.CALIBRATED
            if (totalPlants == null) {
                notes += "Falta el número total de plantas del lote"
            } else if (estimateTotal) {
                if (totalValid > 0) {
                    totalCherries = Estimate(
                        totalPlants * calibration.k!! * meanVisible,
                        Bootstrap.percentile(totalStar.copyOf(totalValid), confidence),
                    )
                } else {
                    notes += "No se pudo calcular el intervalo de la cosecha: todas las réplicas quedaron sin pepas visibles"
                }
            }
            val perKg = input.cherriesPerKg
            if (perKg != null && !(perKg > 0.0 && perKg.isFinite())) {
                notes += "Las pepas por kg deben ser mayores que 0; no se calcula el peso"
            } else if (perKg != null && totalCherries != null) {
                totalKg = Estimate(
                    totalCherries.value / perKg,
                    Interval(totalCherries.ci.low / perKg, totalCherries.ci.high / perKg),
                )
            }
        }
        totalPlantsNote?.let { notes += it }
        if (sumVisible <= 0.0) {
            notes += "No se vieron pepas en las plantas recorridas; no hay proporción de madurez"
        } else if (maturityShare == null) {
            notes += "No se pudo calcular el intervalo de madurez: todas las réplicas quedaron sin pepas visibles"
        }
        if (skipped > 0) notes += "Se descartaron $skipped de $replicates réplicas bootstrap sin pepas visibles"
        notes += ASSUMPTION_NOTE

        return LotEstimate(status, n, coverage, visiblePerPlant, maturityShare, totalCherries, totalKg, calibration, skipped, notes)
    }
}
