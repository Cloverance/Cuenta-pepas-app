package app.cuentapepas.core.count

import kotlinx.serialization.Serializable

/** Per-maturity-class cherry counts. Equality and hash use the array contents. */
@Serializable
class ClassCounts(val perClass: IntArray) {
    val total: Int get() = perClass.sum()

    operator fun get(i: Int): Int = perClass[i]

    operator fun plus(other: ClassCounts): ClassCounts {
        require(perClass.size == other.perClass.size) {
            "class count mismatch: ${perClass.size} vs ${other.perClass.size}"
        }
        return ClassCounts(IntArray(perClass.size) { perClass[it] + other.perClass[it] })
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClassCounts && perClass.contentEquals(other.perClass))

    override fun hashCode(): Int = perClass.contentHashCode()

    override fun toString(): String = "ClassCounts(${perClass.joinToString(",")})"

    companion object {
        fun zero(numClasses: Int): ClassCounts = ClassCounts(IntArray(numClasses))
    }
}
