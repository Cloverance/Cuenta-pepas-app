package app.cuentapepas.core.session

/**
 * CSV of per-plant counts for one session. Columns: plant, one per entry of [classNames] (model order), total,
 * manual. [manualCounts] maps plant number to the whole-plant hand count; the manual cell is empty when there is
 * none (never 0). Fields are quoted per RFC 4180 when needed. Lines end with LF.
 */
fun plantsCsv(plants: List<PlantRecord>, classNames: List<String>, manualCounts: Map<Int, Int?>): String {
    val sb = StringBuilder("plant")
    for (name in classNames) sb.append(',').append(csvField(name))
    sb.append(",total,manual\n")
    for (p in plants) {
        require(p.counts.perClass.size == classNames.size) {
            "plant ${p.plantNumber}: expected ${classNames.size} classes, got ${p.counts.perClass.size}"
        }
        sb.append(p.plantNumber)
        for (c in p.counts.perClass) sb.append(',').append(c)
        sb.append(',').append(p.counts.total).append(',').append(manualCounts[p.plantNumber]?.toString() ?: "").append('\n')
    }
    return sb.toString()
}

private fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
