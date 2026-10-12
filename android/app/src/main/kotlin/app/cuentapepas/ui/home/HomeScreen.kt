package app.cuentapepas.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.cuentapepas.R
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.calibration.RatioCalibrator
import app.cuentapepas.core.session.Lot
import app.cuentapepas.core.session.PassResolver
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.loadLotData
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private class LotRow(val lot: Lot, val sessionCount: Int, val calibration: String?, val error: String?)

/** Startup recovery notices are shown once per process, not on every visit to Home. */
private var startupNoticeHandled = false

@Composable
fun HomeScreen(
    onOpenLot: (String) -> Unit,
    onBench: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(Unit) {
        val store = services.store
        store.lots().map { lot ->
            try {
                val d = store.loadLotData(lot)
                val set = PassResolver.calibrationSamples(d.sessions, d.plants, d.manualCounts)
                val cal = RatioCalibrator.fit(set.samples, replicates = 50)
                LotRow(lot, d.sessions.count { it.status == SessionStatus.COMPLETED }, Fmt.calibrationStatus(cal.n, cal.usable), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LotRow(lot, 0, null, context.describe(e))
            }
        }
    }

    var showNew by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (startupNoticeHandled) return@LaunchedEffect
        try {
            val recovered = services.store.recoveredSessions()
            val issues = services.store.startupIssues()
            startupNoticeHandled = true
            val lines = ArrayList<String>()
            if (recovered.isNotEmpty()) lines += context.getString(R.string.home_notice_recovered, recovered.size)
            for (i in issues) lines += context.getString(R.string.home_notice_corrupt, i.path)
            notice = lines
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            startupNoticeHandled = true
            notice = listOf(context.describe(e))
        }
    }

    ScreenScaffold(stringResource(R.string.home_title), onBack = null, actions = {
        TextButton(onClick = onBench) { Text(stringResource(R.string.home_bench)) }
        TextButton(onClick = onSettings) { Text(stringResource(R.string.home_settings)) }
        TextButton(onClick = onAbout) { Text(stringResource(R.string.home_about)) }
    }) {
        Button(onClick = { showNew = true }) { Text(stringResource(R.string.home_new_lot)) }
        actionError?.let { ErrorText(it) }
        LoadView(loader) { rows ->
            if (rows.isEmpty()) Text(stringResource(R.string.home_empty))
            for (row in rows) {
                Card(Modifier.fillMaxWidth().clickable { onOpenLot(row.lot.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.lot.name, style = MaterialTheme.typography.titleMedium)
                        if (row.error != null) {
                            ErrorText(stringResource(R.string.home_lot_error, row.error))
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(stringResource(R.string.home_lot_sessions, row.sessionCount))
                                Text(row.calibration.orEmpty())
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        LotEditDialog(initial = null, onDismiss = { showNew = false }, onSave = { form ->
            showNew = false
            scope.launch {
                try {
                    services.store.saveLot(
                        Lot(UUID.randomUUID().toString(), form.name, form.totalPlants, form.cherriesPerKg, System.currentTimeMillis()),
                    )
                    actionError = null
                    loader.reload()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    actionError = context.describe(e)
                }
            }
        })
    }

    if (notice.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { notice = emptyList() },
            title = { Text(stringResource(R.string.home_notice_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { notice.forEach { Text(it) } } },
            confirmButton = { TextButton(onClick = { notice = emptyList() }) { Text(stringResource(R.string.common_accept)) } },
        )
    }
}
