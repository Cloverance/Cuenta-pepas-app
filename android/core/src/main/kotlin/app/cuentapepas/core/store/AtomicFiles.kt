package app.cuentapepas.core.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [bytes] to `<target>.tmp`, flushes it to disk and renames it over [target]. A reader therefore sees either
 * the old or the new content, never a partial file. Failures throw [IOException]; they are never swallowed.
 * The target is never deleted before the replacement is in place: if neither an atomic nor a replacing move works,
 * the tmp file is removed and the old target stays intact. An orphan `.tmp` (crash before the rename) holds no
 * committed data; the store's startup sweep deletes it.
 */
internal fun writeAtomic(target: File, bytes: ByteArray) {
    val dir = target.absoluteFile.parentFile ?: throw IOException("No parent directory for $target")
    ensureDir(dir)
    val tmp = File(dir, target.name + ".tmp")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
    val src = tmp.toPath()
    val dst = target.toPath()
    try {
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
        return
    } catch (_: IOException) {
        // AtomicMoveNotSupportedException or a platform refusal: fall through to a plain replacing move.
    }
    try {
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        // The old target is never deleted before the replacement is in place: it stays intact.
        tmp.delete()
        throw IOException("Cannot replace $target", e)
    }
}

internal fun ensureDir(dir: File) {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory $dir")
}
