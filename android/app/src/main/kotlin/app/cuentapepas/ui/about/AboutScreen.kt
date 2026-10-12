package app.cuentapepas.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelSpec
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import app.cuentapepas.inference.ModelException
import java.io.IOException

private class AboutData(
    val version: String,
    val spec: ModelSpec?,
    val specError: String?,
    val notice: String?,
    val noticeError: String?,
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val loader = rememberLoader(Unit) {
        var spec: ModelSpec? = null
        var specError: String? = null
        try {
            spec = services.modelSpec
        } catch (e: ModelException) {
            specError = context.describe(e)
        }
        var notice: String? = null
        var noticeError: String? = null
        try {
            notice = context.assets.open("NOTICE.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            noticeError = e.message ?: e::class.java.simpleName
        }
        AboutData(services.appVersion, spec, specError, notice, noticeError)
    }
    ScreenScaffold(stringResource(R.string.about_title), onBack) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        LoadView(loader) { d ->
            Text(stringResource(R.string.about_version, d.version))
            d.specError?.let { ErrorText(it) }
            d.spec?.let { spec ->
                Text(stringResource(R.string.about_model_id, spec.modelId))
                Text(stringResource(R.string.about_training_run, spec.trainingRun ?: Fmt.NO_DATA))
                Text(stringResource(R.string.about_classes, spec.classes.joinToString(", ")))
                Text(stringResource(R.string.about_metrics_title), style = MaterialTheme.typography.titleMedium)
                if (spec.metrics.isEmpty()) Text(Fmt.NO_DATA)
                for ((k, v) in spec.metrics.toSortedMap()) {
                    Text(stringResource(R.string.about_metric_line, k, Fmt.orNoData(v, 3)))
                }
                spec.licenseAttribution?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(stringResource(R.string.about_limits_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_limits_text))
            Text(stringResource(R.string.about_licenses_title), style = MaterialTheme.typography.titleMedium)
            d.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            d.noticeError?.let { ErrorText(stringResource(R.string.about_notice_missing, it)) }
        }
    }
}
