package app.cuentapepas.ui.calibration

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cuentapepas.R
import app.cuentapepas.core.calibration.CalibrationResult
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.CalibrationSet
import app.cuentapepas.core.calibration.RatioCalibrator
import app.cuentapepas.core.session.PassResolver
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import app.cuentapepas.ui.common.loadLotData

private const val MIN_SAMPLES = 10

private class CalData(val lotName: String, val set: CalibrationSet, val result: CalibrationResult)

@Composable
fun CalibrationScreen(lotId: String, onBack: () -> Unit) {
    val services = LocalContext.current.services
    val loader = rememberLoader(lotId) {
        val d = services.store.loadLotData(lotId)
        val set = PassResolver.calibrationSamples(d.sessions, d.plants, d.manualCounts)
        CalData(d.lot.name, set, RatioCalibrator.fit(set.samples, MIN_SAMPLES))
    }
    ScreenScaffold(stringResource(R.string.cal_title), onBack) {
        LoadView(loader) { d ->
            Text(d.lotName, style = MaterialTheme.typography.titleLarge)
            val r = d.result
            if (!r.usable || r.n < MIN_SAMPLES) {
                Text(stringResource(R.string.cal_none_big), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.error)
                r.reason?.let { Text(it) }
                Text(stringResource(R.string.cal_samples, r.n, MIN_SAMPLES))
                Text(stringResource(R.string.cal_how_to))
            } else {
                Text(Fmt.calibrationStatus(r.n, true), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.cal_samples, r.n, MIN_SAMPLES))
                // k is a factor, not a harvest figure; it still travels with its interval.
                Text(stringResource(R.string.cal_k, "${Fmt.orNoData(r.k, 2)} ${Fmt.interval(r.kCi, 2)}"))
                Text(stringResource(R.string.cal_plant_factor, Fmt.interval(r.plantFactorInterval, 2)))
                Text(stringResource(R.string.cal_loocv, Fmt.percentOrNoData(r.loocvMeanAbsPctError)))
            }
            Text(stringResource(R.string.cal_explain), style = MaterialTheme.typography.bodyMedium)

            if (d.set.skipped.isNotEmpty()) {
                Text(stringResource(R.string.cal_skipped_title), style = MaterialTheme.typography.titleMedium)
                for (s in d.set.skipped) Text(stringResource(R.string.cal_skipped_line, s.plantId, s.reason))
            }
            if (d.set.samples.isNotEmpty()) {
                Text(stringResource(R.string.cal_samples_title), style = MaterialTheme.typography.titleMedium)
                for (s in d.set.samples) Text(stringResource(R.string.cal_sample_line, s.plantId, s.visible, s.manual))
            }
        }
    }
}
