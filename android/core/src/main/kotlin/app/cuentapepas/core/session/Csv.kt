package app.cuentapepas.core.session

private const val CSV_CLASSES = 5

/** CSV of per-plant counts. `manual` is empty when there is no manual count (never 0). Lines end with LF. */
fun plantsCsv(plants: List<PlantRecord>): String {
    val sb = StringBuilder("plant,green,semi_ripe,ripe,overripe,dry,total,manual\n")
    for (p in plants) {
        require(p.counts.perClass.size == CSV_CLASSES) {
            "plant ${p.plantNumber}: expected $CSV_CLASSES classes, got ${p.counts.perClass.size}"
        }
        sb.append(p.plantNumber)
        for (c in p.counts.perClass) sb.append(',').append(c)
        sb.append(',').append(p.counts.total).append(',').append(p.manualCount?.toString() ?: "").append('\n')
    }
    return sb.toString()
}
