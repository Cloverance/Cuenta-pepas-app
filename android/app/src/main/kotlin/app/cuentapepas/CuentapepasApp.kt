package app.cuentapepas

import android.app.Application
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.store.AppSettings
import app.cuentapepas.data.Exporter
import app.cuentapepas.data.SessionStore
import app.cuentapepas.data.SettingsStore
import app.cuentapepas.inference.ModelAssets
import app.cuentapepas.inference.TfliteDetector

/** Application class; owns the [ServiceLocator]. */
class CuentapepasApp : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator(this)
    }
}

/**
 * Lazy singletons. [modelSpec] throws `ModelException` (missing/invalid model.json) every time it is read until
 * the problem is fixed; the UI maps the subtype to a Spanish message. [settings] and [exporter] depend on it.
 */
class ServiceLocator(private val app: Application) {
    val store: SessionStore by lazy { SessionStore(app) }

    val modelSpec: ModelSpec by lazy { ModelAssets.loadSpec(app) }

    val settings: SettingsStore by lazy { SettingsStore(app, AppSettings.defaults(modelSpec)) }

    val exporter: Exporter by lazy { Exporter(app, store, modelSpec.classes) }

    /** Creates a detector for the model contract; the caller owns it and must close it. */
    val detectorFactory: (preferGpu: Boolean) -> TfliteDetector =
        { preferGpu -> TfliteDetector(app, modelSpec, preferGpu) }

    val appVersion: String by lazy {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    }
}
