package app.cuentapepas.core.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Observability of the rename fallback. When ATOMIC_MOVE is refused the write still succeeds with a plain replacing
 * move (not atomic: a crash in that instant could lose the old content), so the event is counted and reported to
 * [listener] (the Android app logs it with Log.w; this module has no logging dependency).
 */
object AtomicFilesDiagnostics {
    private val count = java.util.concurrent.atomic.AtomicInteger()

    /** How many writes had to use the non-atomic replacing move since the process started. */
    val fallbackCount: Int get() = count.get()

    /** Called with the target file and the refusal; must not throw. */
    @Volatile var listener: ((File, IOException) -> Unit)? = null

    internal fun reportFallback(target: File, cause: IOException) {
        count.incrementAndGet()
        listener?.invoke(target, cause)
    }
}

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
    } catch (e: IOException) {
        // AtomicMoveNotSupportedException or a platform refusal: fall through to a plain replacing move, but say so.
        AtomicFilesDiagnostics.reportFallback(target, e)
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
