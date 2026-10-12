package app.cuentapepas.core.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [bytes] to `<target>.tmp`, flushes it to disk and renames it over [target]. A reader therefore sees either
 * the old or the new content, never a partial file. Failures throw [IOException]; they are never swallowed.
 * When the platform refuses to rename over an existing file, the target is deleted only after the tmp file was
 * written completely, and the tmp file is kept if the final move still fails.
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
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        if (!target.exists()) throw e
        Files.delete(dst)
        Files.move(src, dst)
    }
}

internal fun ensureDir(dir: File) {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory $dir")
}
