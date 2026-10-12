package app.cuentapepas.ui.summary

import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.cuentapepas.R
import app.cuentapepas.core.format.Fmt
import app.cuentapepas.core.session.Geo
import app.cuentapepas.core.session.PlantRecord
import app.cuentapepas.core.session.RepeatCheck
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStats
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.ui.common.ErrorText
import app.cuentapepas.ui.common.LoadView
import app.cuentapepas.ui.common.ScreenScaffold
import app.cuentapepas.ui.common.classNames
import app.cuentapepas.ui.common.describe
import app.cuentapepas.ui.common.rememberLoader
import app.cuentapepas.ui.common.services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private class SummaryData(
    val meta: SessionMeta,
    val plants: List<PlantRecord>,
    val stats: SessionStats?,
    val manual: Map<Int, Int>,
    val classNames: List<String>,
    val others: Map<String, SessionMeta>,
    val repeat: RepeatCheck?,
    val video: File?,
)

@Composable
fun SessionSummaryScreen(sessionId: String, onDone: (lotId: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(sessionId) {
        val store = services.store
        val meta = store.session(sessionId)
        val plants = store.plants(sessionId).sortedBy { it.plantNumber }
        val stats = store.stats(sessionId)
        val manual = store.manualCounts(meta.lotId).filter { it.row == meta.row }
            .groupBy { it.plantNumber }.mapValues { (_, v) -> v.maxBy { it.enteredAtMs }.count }
        val others = store.sessions(meta.lotId).filter { it.id != sessionId && it.status == SessionStatus.COMPLETED }
        val repeat = if (meta.status == SessionStatus.COMPLETED && others.isNotEmpty()) {
            val maxAcc = meta.settings["gps.maxAccuracyM"]?.toFloatOrNull() ?: 10f
            Geo.possibleRepeats(store.gps(sessionId), others.associate { it.id to store.gps(it.id) }, maxAccuracyM = maxAcc)
        } else null
        val video = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?.let { File(it, "$sessionId.mp4") }?.takeIf { it.isFile }
        val numClasses = plants.firstOrNull()?.counts?.perClass?.size ?: 0
        SummaryData(meta, plants, stats, manual, services.classNames(numClasses), others.associateBy { it.id }, repeat, video)
    }

    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }

    ScreenScaffold(stringResource(R.string.summary_title), onBack) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { ErrorText(it) }
        LoadView(loader) { d ->
            val status = stringResource(
                when (d.meta.status) {
                    SessionStatus.ACTIVE -> R.string.session_status_ACTIVE
                    SessionStatus.COMPLETED -> R.string.session_status_COMPLETED
                    SessionStatus.REPLACED -> R.string.session_status_REPLACED
                    SessionStatus.ABANDONED -> R.string.session_status_ABANDONED
                },
            )
            Text(
                stringResource(R.string.summary_header, d.meta.row, d.meta.side.name, Fmt.dateTime(d.meta.startedAtMs)),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.summary_status, status))

            GpsCheck(d, dismissed,
                onDistinct = { dismissed = dismissed + it },
                onMark = { otherId ->
                    scope.launch {
                        try {
                            val fresh = services.store.sessions(d.meta.lotId)
                            val other = fresh.firstOrNull { it.id == otherId }
                            val me = fresh.firstOrNull { it.id == d.meta.id }
                            val label = d.others[otherId]?.let { gpsLabel(context, it) } ?: otherId
                            if (other?.status == SessionStatus.COMPLETED && me?.status == SessionStatus.COMPLETED) {
                                services.store.markReplaced(otherId)
                                message = context.getString(R.string.gps_replaced_ok, label)
                                error = null
                                loader.reload()
                            } else {
                                error = context.getString(R.string.gps_cannot_replace, label)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = context.describe(e)
                        }
                    }
                },
            )

            PlantsTable(d, onSaved = { n ->
                message = context.getString(R.string.summary_manual_saved, n)
                error = null
                loader.reload()
            }, onError = { error = it })

            Text(stringResource(R.string.summary_stats_title), style = MaterialTheme.typography.titleMedium)
            StatsBlock(d.stats)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = services.exporter.exportSession(sessionId)
                            context.startActivity(services.exporter.shareIntent(file))
                            error = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = context.describe(e)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(if (busy) R.string.summary_exporting else R.string.summary_export)) }
                if (d.meta.evaluationMode) {
                    OutlinedButton(onClick = {
                        val v = d.video
                        if (v == null) {
                            error = context.getString(R.string.summary_video_missing)
                        } else try {
                            val uri = FileProvider.getUriForFile(context, "app.cuentapepas.fileprovider", v)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(send, context.getString(R.string.summary_share_video_title))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                            error = null
                        } catch (e: Exception) {
                            error = context.describe(e)
                        }
                    }) { Text(stringResource(R.string.summary_share_video)) }
                }
                OutlinedButton(onClick = { onDone(d.meta.lotId) }) { Text(stringResource(R.string.summary_done)) }
            }
        }
    }
}

private fun gpsLabel(context: android.content.Context, s: SessionMeta): String =
    context.getString(R.string.gps_session_label, s.row, s.side.name, Fmt.dateTime(s.endedAtMs ?: s.startedAtMs))

@Composable
private fun GpsCheck(
    d: SummaryData,
    dismissed: Set<String>,
    onDistinct: (String) -> Unit,
    onMark: (String) -> Unit,
) {
    val context = LocalContext.current
    val check = d.repeat ?: return
    for (id in check.repeats) {
        if (id in dismissed) continue
        val other = d.others[id] ?: continue
        val label = gpsLabel(context, other)
        Card(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gps_check_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.gps_repeat, label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onMark(id) }) { Text(stringResource(R.string.gps_mark_replacement, label)) }
                    OutlinedButton(onClick = { onDistinct(id) }) { Text(stringResource(R.string.gps_distinct)) }
                }
            }
        }
    }
    for (id in check.unverifiable) {
        val other = d.others[id] ?: continue
        Text(stringResource(R.string.gps_unverifiable, gpsLabel(context, other)), color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun PlantsTable(d: SummaryData, onSaved: (Int) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val services = context.services
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.summary_plants_title), style = MaterialTheme.typography.titleMedium)
    if (d.plants.isEmpty()) {
        Text(stringResource(R.string.summary_no_plants))
        return
    }
    val calibration = d.meta.isCalibrationSample
    val numClasses = d.plants.first().counts.perClass.size
    val entries = remember(d) { mutableStateMapOf<Int, String>().apply { d.manual.forEach { (k, v) -> put(k, v.toString()) } } }
    val cellW = 72.dp

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.summary_col_plant), Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
        d.classNames.forEach { Text(it, Modifier.width(cellW), style = MaterialTheme.typography.labelLarge) }
        Text(stringResource(R.string.summary_col_total), Modifier.width(cellW), style = MaterialTheme.typography.labelLarge)
        if (calibration) Text(stringResource(R.string.summary_col_manual), Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
    }
    HorizontalDivider()
    for (p in d.plants) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(p.plantNumber.toString(), Modifier.width(64.dp))
            for (c in 0 until numClasses) Text(p.counts[c].toString(), Modifier.width(cellW))
            Text(p.counts.total.toString(), Modifier.width(cellW))
            if (calibration) {
                OutlinedTextField(
                    value = entries[p.plantNumber].orEmpty(),
                    onValueChange = { entries[p.plantNumber] = it },
                    modifier = Modifier.width(120.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        }
    }
    HorizontalDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.summary_total_row), Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
        for (c in 0 until numClasses) {
            Text(d.plants.sumOf { it.counts[c] }.toString(), Modifier.width(cellW), style = MaterialTheme.typography.labelLarge)
        }
        Text(d.plants.sumOf { it.counts.total }.toString(), Modifier.width(cellW), style = MaterialTheme.typography.labelLarge)
    }
    if (calibration) {
        Text(stringResource(R.string.summary_manual_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            // Validate everything first; blank entries are left as they are (a saved count cannot be removed).
            val changes = ArrayList<Pair<Int, Int>>()
            for (p in d.plants) {
                val raw = entries[p.plantNumber]?.trim().orEmpty()
                if (raw.isEmpty()) continue
                val v = raw.toIntOrNull()?.takeIf { it >= 0 }
                if (v == null) {
                    onError(context.getString(R.string.summary_manual_invalid, p.plantNumber))
                    return@OutlinedButton
                }
                if (d.manual[p.plantNumber] != v) changes += p.plantNumber to v
            }
            scope.launch {
                try {
                    for ((plant, count) in changes) services.store.setManualCount(d.meta.lotId, d.meta.row, plant, count)
                    onSaved(changes.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(context.describe(e))
                }
            }
        }) { Text(stringResource(R.string.summary_manual_save)) }
    }
}

@Composable
private fun StatsBlock(s: SessionStats?) {
    if (s == null) {
        Text(stringResource(R.string.summary_stats_missing))
        return
    }
    val context = LocalContext.current
    Text(stringResource(R.string.stat_duration, Fmt.duration(s.durationS)))
    Text(stringResource(R.string.stat_frames, s.framesProcessed))
    Text(stringResource(R.string.stat_fps, Fmt.orNoData(s.fpsMedian), Fmt.orNoData(s.fpsP10)))
    Text(stringResource(R.string.stat_inference, Fmt.orNoData(s.inferenceMsMedian), Fmt.orNoData(s.inferenceMsP90)))
    Text(stringResource(R.string.stat_delegate, s.delegate))
    val thermal = when (s.maxThermalStatus) {
        0 -> R.string.thermal_none
        1 -> R.string.thermal_light
        2 -> R.string.thermal_moderate
        3 -> R.string.thermal_severe
        4 -> R.string.thermal_critical
        5 -> R.string.thermal_emergency
        6 -> R.string.thermal_shutdown
        else -> R.string.thermal_no_data
    }
    Text(stringResource(R.string.stat_thermal, context.getString(thermal)))
    val battery = s.batteryPctPerHour?.let { "${Fmt.num(it)} %" } ?: Fmt.NO_DATA
    Text(stringResource(R.string.stat_battery, battery))
    if (s.warningFraction.isEmpty()) {
        Text(stringResource(R.string.stat_warnings_none))
    } else {
        Text(stringResource(R.string.stat_warnings))
        for ((k, v) in s.warningFraction.toSortedMap()) {
            Text("  " + stringResource(R.string.stat_warning_line, k, Fmt.percent(v)))
        }
    }
    Text(stringResource(R.string.stat_gps, Fmt.orNoData(s.gpsAccuracyMedianM), Fmt.orNoData(s.gpsAccuracyP90M)))
}
