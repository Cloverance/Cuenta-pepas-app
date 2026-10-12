package app.cuentapepas.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.cuentapepas.R
import app.cuentapepas.core.store.SessionExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Zip export of one session (meta, plants, gps, stats, frames if present, plants.csv; the video is shared separately)
 * into `filesDir/exports/`, and the share intent for it. Failures propagate (IOException, CorruptDataException).
 */
class Exporter(
    private val context: Context,
    private val store: SessionStore,
    private val classNames: List<String>,
) {
    private val exportsDir = File(context.filesDir, "exports")

    suspend fun exportSession(sessionId: String): File = withContext(Dispatchers.IO) {
        SessionExporter(store.blocking(), exportsDir, classNames).exportSession(sessionId)
    }

    /** ACTION_SEND chooser for [file] (must be under `filesDir/exports/`, see res/xml/file_paths.xml). */
    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.export_share_title)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val AUTHORITY = "app.cuentapepas.fileprovider"
    }
}
