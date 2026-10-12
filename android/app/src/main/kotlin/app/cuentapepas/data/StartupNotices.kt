package app.cuentapepas.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException

/** What Home still has to show: ids of sessions recovered as ABANDONED and paths the startup sweep could not read. */
data class PendingNotices(val recovered: List<String>, val corrupt: List<String>) {
    val isEmpty: Boolean get() = recovered.isEmpty() && corrupt.isEmpty()
}

/**
 * Startup recovery notices that survive process restarts until the user dismisses them (SharedPreferences
 * "cuentapepas_ui"). [record] merges what this process's startup sweep found; [pending] is what to show;
 * [dismissAll] forgets it. Failed writes throw [IOException].
 */
class StartupNotices(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cuentapepas_ui", Context.MODE_PRIVATE)

    private fun set(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty()

    fun record(recovered: List<String>, corruptPaths: List<String>) {
        if (recovered.isEmpty() && corruptPaths.isEmpty()) return
        val ok = prefs.edit()
            .putStringSet(KEY_RECOVERED, set(KEY_RECOVERED) + recovered)
            .putStringSet(KEY_CORRUPT, set(KEY_CORRUPT) + corruptPaths)
            .commit()
        if (!ok) throw IOException("No se pudieron guardar los avisos de inicio")
    }

    fun pending(): PendingNotices = PendingNotices(set(KEY_RECOVERED).sorted(), set(KEY_CORRUPT).sorted())

    fun dismissAll() {
        if (!prefs.edit().remove(KEY_RECOVERED).remove(KEY_CORRUPT).commit()) {
            throw IOException("No se pudo guardar que el aviso se leyó")
        }
    }

    private companion object {
        const val KEY_RECOVERED = "notice.recovered"
        const val KEY_CORRUPT = "notice.corrupt"
    }
}
