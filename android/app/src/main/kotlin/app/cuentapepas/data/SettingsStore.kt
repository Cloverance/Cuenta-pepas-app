package app.cuentapepas.data

import android.content.Context
import android.content.SharedPreferences
import app.cuentapepas.core.store.AppSettings
import app.cuentapepas.core.store.KeyValueStore
import app.cuentapepas.core.store.SettingsRepository
import java.io.IOException

/**
 * Persists [AppSettings] in SharedPreferences (file "cuentapepas_settings"). Reads and writes are small and
 * synchronous. [load] throws `CorruptDataException` for an unreadable stored value; call [reset] to start over.
 */
class SettingsStore(context: Context, defaults: AppSettings) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cuentapepas_settings", Context.MODE_PRIVATE)

    private val repo = SettingsRepository(PrefsKeyValueStore(prefs), defaults)

    fun load(): AppSettings = repo.load()
    fun save(settings: AppSettings) = repo.save(settings)
    fun reset() = repo.reset()

    /** The current settings as the flat map stored in `SessionMeta.settings` for every new session. */
    fun snapshot(): Map<String, String> = load().snapshot()
}

private class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun putAll(entries: Map<String, String>) {
        val e = prefs.edit()
        for ((k, v) in entries) e.putString(k, v)
        if (!e.commit()) throw IOException("No se pudieron guardar los ajustes")
    }

    override fun clear() {
        if (!prefs.edit().clear().commit()) throw IOException("No se pudieron borrar los ajustes")
    }
}
