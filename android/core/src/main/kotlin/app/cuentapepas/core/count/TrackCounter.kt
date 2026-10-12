package app.cuentapepas.core.count

import app.cuentapepas.core.track.TrackSnapshot

/**
 * A confirmed cherry: its track id, the plant active when it was confirmed, its current maturity class
 * (argmax of [classProbs], ties to the lowest index) and the latest class probabilities.
 */
class CountedTrack(
    val id: Int,
    val plantNumber: Int,
    val cls: Int,
    val classProbs: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CountedTrack) return false
        return id == other.id && plantNumber == other.plantNumber && cls == other.cls &&
            classProbs.contentEquals(other.classProbs)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + plantNumber
        result = 31 * result + cls
        result = 31 * result + classProbs.contentHashCode()
        return result
    }

    override fun toString(): String =
        "CountedTrack(id=$id, plantNumber=$plantNumber, cls=$cls, classProbs=${classProbs.contentToString()})"
}

/**
 * Counts each cherry (track) exactly once.
 *
 * A track is confirmed on the first frame where `hits >= minHits && meanScore >= minMeanScore`, and is
 * attributed to the plant active at that moment. Confirmed ids are never counted again, even if the id
 * disappears and reappears; their class probabilities keep being updated, so the final class is the
 * argmax of the latest probabilities.
 */
class TrackCounter(
    val numClasses: Int,
    val minHits: Int = 3,
    val minMeanScore: Float = 0.40f,
) {
    init {
        require(numClasses > 0) { "numClasses must be positive: $numClasses" }
    }

    private class Record(val plantNumber: Int, var classProbs: FloatArray)

    /** Confirmed tracks by id, in confirmation order. */
    private val counted = LinkedHashMap<Int, Record>()
    private val startedPlants = ArrayList<Int>()
    private var activePlant: Int? = null

    /** Sets the plant that newly confirmed cherries are attributed to. */
    fun startPlant(plantNumber: Int) {
        activePlant = plantNumber
        if (plantNumber !in startedPlants) startedPlants.add(plantNumber)
    }

    /**
     * Processes the active tracks of one frame.
     *
     * @throws IllegalStateException if no plant has been started.
     * @throws IllegalArgumentException if a snapshot's class probabilities do not have [numClasses] entries.
     */
    fun observe(tracks: List<TrackSnapshot>) {
        val plant = checkNotNull(activePlant) { "startPlant must be called before observe" }
        for (t in tracks) {
            require(t.classProbs.size == numClasses) {
                "track ${t.id} has ${t.classProbs.size} class probabilities, expected $numClasses"
            }
        }
        for (t in tracks) {
            val record = counted[t.id]
            if (record != null) {
                record.classProbs = t.classProbs.copyOf()
            } else if (t.hits >= minHits && t.meanScore >= minMeanScore) {
                counted[t.id] = Record(plant, t.classProbs.copyOf())
            }
        }
    }

    /** Counts for the active plant, or zeros if no plant has been started. */
    fun currentPlantCounts(): ClassCounts = activePlant?.let { plantCounts(it) } ?: ClassCounts.zero(numClasses)

    /** Counts of the cherries attributed to [plantNumber]; zeros for a plant that was never started. */
    fun plantCounts(plantNumber: Int): ClassCounts {
        val perClass = IntArray(numClasses)
        for (record in counted.values) {
            if (record.plantNumber == plantNumber) perClass[argmax(record.classProbs)]++
        }
        return ClassCounts(perClass)
    }

    /** Counts of every confirmed cherry, across all plants. */
    fun sessionCounts(): ClassCounts {
        val perClass = IntArray(numClasses)
        for (record in counted.values) perClass[argmax(record.classProbs)]++
        return ClassCounts(perClass)
    }

    /** Plant numbers passed to [startPlant], in order of first start. */
    fun plantNumbers(): List<Int> = startedPlants.toList()

    /** Every confirmed cherry, in confirmation order, with its current class. */
    fun countedTracks(): List<CountedTrack> = counted.map { (id, record) ->
        CountedTrack(id, record.plantNumber, argmax(record.classProbs), record.classProbs.copyOf())
    }

    private companion object {
        /** Index of the largest value; ties resolve to the lowest index. */
        fun argmax(p: FloatArray): Int {
            var best = 0
            for (i in 1 until p.size) if (p[i] > p[best]) best = i
            return best
        }
    }
}
