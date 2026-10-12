package app.cuentapepas.core.store

import app.cuentapepas.core.session.plantsCsv
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds `<exportsDir>/<lot>_s<row><side>_<yyyyMMdd-HHmm>.zip` for one session with meta.json, plants.json,
 * gps.jsonl and stats.json (each only if it exists), frames.jsonl (evaluation mode, if it exists) and plants.csv.
 * The video is not included (too large; shared separately). The files are validated by decoding them first, so a
 * corrupt file raises [CorruptDataException] instead of being exported. The zip is written atomically.
 */
class SessionExporter(
    private val store: FileSessionStore,
    private val exportsDir: File,
    private val classNames: List<String>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun exportSession(sessionId: String): File {
        val meta = store.session(sessionId)
        val plants = store.plants(sessionId) // validates plants.json
        store.stats(sessionId) // validates stats.json
        store.gps(sessionId) // validates gps.jsonl
        val lot = store.lots().firstOrNull { it.id == meta.lotId }
        // Latest manual count per plant of this row (a whole-plant count, so it is shared by both sides).
        val manual: Map<Int, Int?> = store.manualCounts(meta.lotId)
            .filter { it.row == meta.row }
            .groupBy { it.plantNumber }
            .mapValues { (_, list) -> list.maxByOrNull { it.enteredAtMs }?.count }

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun add(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            for ((kind, name) in listOf(
                SessionFileKind.META to "meta.json",
                SessionFileKind.PLANTS to "plants.json",
                SessionFileKind.GPS to "gps.jsonl",
                SessionFileKind.STATS to "stats.json",
                SessionFileKind.FRAMES to "frames.jsonl",
            )) {
                store.existingFile(sessionId, kind)?.let { add(name, it.readBytes()) }
            }
            add("plants.csv", plantsCsv(plants, classNames, manual).toByteArray(Charsets.UTF_8))
        }

        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(meta.startedAtMs).atZone(zone))
        val out = File(exportsDir, "${safeName(lot?.name ?: meta.lotId)}_s${meta.row}${meta.side}_$stamp.zip")
        writeAtomic(out, bytes.toByteArray())
        return out
    }

    private fun safeName(s: String): String {
        val ascii = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "lote" }
    }
}
