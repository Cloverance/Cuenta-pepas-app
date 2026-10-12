package app.cuentapepas.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cuentapepas.R
import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.live.ThermalSeverity
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.device.GpsState
import java.util.Locale

private val HudBackground = Color(0xB3000000)
private val WarnRed = Color(0xFFC62828)
private val WarnAmber = Color(0xFFF9A825)

@Composable
internal fun className(id: String): String = when (id) {
    "green" -> stringResource(R.string.class_green)
    "semi_ripe" -> stringResource(R.string.class_semi_ripe)
    "ripe" -> stringResource(R.string.class_ripe)
    "overripe" -> stringResource(R.string.class_overripe)
    "dry" -> stringResource(R.string.class_dry)
    else -> id // unknown class from the model contract: show its identifier rather than invent a name
}

@Composable
private fun thermalLabel(status: Int): String = when (status) {
    -1 -> stringResource(R.string.thermal_no_data)
    0 -> stringResource(R.string.thermal_none)
    1 -> stringResource(R.string.thermal_light)
    2 -> stringResource(R.string.thermal_moderate)
    3 -> stringResource(R.string.thermal_severe)
    4 -> stringResource(R.string.thermal_critical)
    5 -> stringResource(R.string.thermal_emergency)
    else -> stringResource(R.string.thermal_shutdown)
}

private fun thermalColor(status: Int): Color = when (ThermalSeverity.of(status)) {
    ThermalSeverity.NO_DATA -> Color(0xFFBDBDBD)
    ThermalSeverity.NORMAL -> Color(0xFF81C784)
    ThermalSeverity.LIGHT -> Color(0xFFFFEE58)
    ThermalSeverity.MODERATE -> Color(0xFFFFA726)
    ThermalSeverity.SEVERE, ThermalSeverity.CRITICAL -> Color(0xFFEF5350)
}

/** Top bar: FPS, delegate, thermal state (coloured), battery and GPS accuracy. "No data" is shown as a dash. */
@Composable
internal fun StatusBar(hud: LiveHud, evaluationMode: Boolean, modifier: Modifier = Modifier) {
    val dash = stringResource(R.string.live_no_data)
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(HudBackground).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fps = if (hud.fps.isNaN()) dash else String.format(Locale.US, "%.1f", hud.fps)
        StatusText(stringResource(R.string.live_fps, fps))
        StatusText(
            when (hud.delegate) {
                "GPU" -> stringResource(R.string.delegate_gpu)
                "CPU" -> stringResource(R.string.delegate_cpu)
                else -> dash
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(thermalColor(hud.thermal)))
            StatusText(thermalLabel(hud.thermal))
        }
        StatusText(
            hud.batteryPct?.let { stringResource(R.string.live_battery, it) } ?: stringResource(R.string.live_battery_unknown),
        )
        val gpsColor = if (hud.gps is GpsState.Fix) Color.White else WarnAmber
        StatusText(
            when (val g = hud.gps) {
                is GpsState.Fix -> stringResource(R.string.live_gps_accuracy, Math.round(g.accuracyM))
                GpsState.NoPermission -> stringResource(R.string.live_gps_no_permission)
                GpsState.ProviderOff -> stringResource(R.string.live_gps_off)
                is GpsState.Unavailable -> stringResource(R.string.live_gps_unavailable)
                GpsState.Idle, GpsState.Searching -> stringResource(R.string.live_gps_searching)
            },
            color = gpsColor,
        )
        if (evaluationMode) StatusText(stringResource(R.string.live_evaluation_badge), color = WarnAmber)
    }
}

@Composable
private fun StatusText(text: String, color: Color = Color.White) {
    Text(text, color = color, fontSize = 13.sp, maxLines = 1)
}

/** Capture-quality warnings, red for speed and blur, amber for light. Nothing is drawn when there are none. */
@Composable
internal fun WarningsBanner(warnings: Set<QualityWarning>, modifier: Modifier = Modifier) {
    if (warnings.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in QualityWarning.values()) {
            if (w !in warnings) continue
            val (label, bg) = when (w) {
                QualityWarning.TOO_FAST -> stringResource(R.string.warn_too_fast) to WarnRed
                QualityWarning.BLURRY -> stringResource(R.string.warn_blurry) to WarnRed
                QualityWarning.TOO_DARK -> stringResource(R.string.warn_too_dark) to WarnAmber
                QualityWarning.BACKLIGHT -> stringResource(R.string.warn_backlight) to WarnAmber
            }
            Text(
                label,
                color = if (bg == WarnAmber) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Small informational and error lines (GPU fallback, throttling, processing and logging errors, video notices). */
@Composable
internal fun NoticesList(notices: List<String>, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(HudBackground).padding(6.dp)) {
        for (n in notices) Text(n, color = WarnAmber, fontSize = 12.sp, maxLines = 3)
    }
}

/**
 * Counts table: one row per class with the current plant and the whole route (recorrido), then the totals.
 * The label is always "Pepas visibles": these are visible cherries, not an estimate of the harvest.
 */
@Composable
internal fun CountsPanel(
    classes: List<String>,
    plantNumber: Int?,
    plantCounts: ClassCounts?,
    routeCounts: ClassCounts?,
    modifier: Modifier = Modifier,
) {
    val dash = stringResource(R.string.live_no_data)
    val numberWidth = 62.dp
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(HudBackground).padding(8.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Spacer(Modifier.weight(1f))
            Text(
                if (plantNumber != null) stringResource(R.string.live_plant, plantNumber) else stringResource(R.string.live_no_plant),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.width(numberWidth),
            )
            Text(
                stringResource(R.string.live_route),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.width(numberWidth),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 3.dp), color = Color(0x66FFFFFF))
        for ((i, id) in classes.withIndex()) {
            Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(classColor(i)))
                Text(className(id), color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp).weight(1f))
                CountCell(plantCounts?.let { it[i].toString() } ?: dash, numberWidth, bold = false)
                CountCell(routeCounts?.let { it[i].toString() } ?: dash, numberWidth, bold = false)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 3.dp), color = Color(0x66FFFFFF))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_total), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            CountCell(plantCounts?.total?.toString() ?: dash, numberWidth, bold = true)
            CountCell(routeCounts?.total?.toString() ?: dash, numberWidth, bold = true)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.live_visible),
            color = Color(0xFFE0E0E0), fontSize = 12.sp, fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CountCell(text: String, width: androidx.compose.ui.unit.Dp, bold: Boolean) {
    Text(
        text, color = Color.White, fontSize = if (bold) 18.sp else 15.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width),
    )
}

@Composable
internal fun PausedBanner(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.live_paused),
        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC1565C0)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
