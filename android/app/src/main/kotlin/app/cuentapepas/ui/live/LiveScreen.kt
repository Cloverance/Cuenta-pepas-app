package app.cuentapepas.ui.live

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cuentapepas.R

/**
 * Live camera counting screen for an ACTIVE session created by the new-session screen.
 * Contract (stable for navigation): [sessionId] is an existing ACTIVE session; [onFinished] is called with the same
 * id after the session was completed (or abandoned) and saved, so navigation can open the summary.
 *
 * Landscape, screen kept on. Volume keys advance to the next plant through [HardwareKeys] (MainActivity forwards
 * them). Camera permission is required; location is optional (without it the status bar says "GPS: sin permiso").
 */
@Composable
fun LiveScreen(sessionId: String, onFinished: (sessionId: String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: LiveViewModel = viewModel(key = "live-$sessionId", factory = LiveViewModel.Factory(app, sessionId))
    val phase by vm.phase.collectAsState()
    val finished by vm.finished.collectAsState()

    LaunchedEffect(finished) { finished?.let(onFinished) }

    // Keep the screen on while this screen is shown.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Volume keys -> next plant (MainActivity forwards them while liveScreenActive).
    DisposableEffect(Unit) {
        HardwareKeys.liveScreenActive = true
        onDispose { HardwareKeys.liveScreenActive = false }
    }
    LaunchedEffect(vm) { HardwareKeys.nextPlant.collect { vm.nextPlant() } }

    // Permissions.
    var cameraGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = context.isGranted(Manifest.permission.CAMERA)
        locationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun requestMissing() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            .filterNot { context.isGranted(it) }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    LaunchedEffect(Unit) { requestMissing() }

    // Lifecycle: leaving the app pauses and persists; coming back may restart the evaluation video; a visit to the
    // system settings may have granted permissions.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        cameraGranted = context.isGranted(Manifest.permission.CAMERA)
        locationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }

    val isReady = phase is LivePhase.Ready
    LaunchedEffect(isReady, locationGranted) {
        if (isReady) vm.startLocation() // reports "sin permiso" itself when the permission is missing
    }

    val onBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val p = phase) {
            LivePhase.Loading -> Message(stringResource(R.string.live_loading), showProgress = true)
            LivePhase.Finishing -> Message(stringResource(R.string.live_saving), showProgress = true)
            is LivePhase.Failed -> ErrorPanel(
                message = p.message,
                actionLabel = if (p.canAbandon) stringResource(R.string.live_close_session) else stringResource(R.string.bench_back),
                onAction = { if (p.canAbandon) vm.finish() else onBackDispatcher?.onBackPressed() },
            )
            is LivePhase.SaveFailed -> ErrorPanel(
                message = p.message,
                actionLabel = stringResource(R.string.live_finish),
                onAction = { vm.finish() },
            )
            is LivePhase.Ready ->
                if (!cameraGranted) {
                    CameraPermissionPanel(
                        onGrant = { requestMissing() },
                        onSettings = { context.openAppSettings() },
                        onClose = { vm.finish() },
                    )
                } else {
                    LiveContent(vm, p)
                }
        }
    }
}

@Composable
private fun LiveContent(vm: LiveViewModel, ready: LivePhase.Ready) {
    val hud by vm.hud.collectAsState()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val pv = previewView
    LaunchedEffect(pv, lifecycleOwner) {
        if (pv != null) vm.bindCamera(lifecycleOwner, pv, pv.display?.rotation ?: Surface.ROTATION_90)
    }
    DisposableEffect(Unit) { onDispose { vm.unbindCamera() } }

    // The phone may be turned 180 degrees (sensor landscape): keep the use cases' rotation in step with the display.
    DisposableEffect(pv) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = pv?.display ?: return
                if (display.displayId == displayId) vm.camera.setRotation(display.rotation)
            }
        }
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    BackHandler { confirmFinish = true }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView-backed: composes reliably under the overlay and transforms exactly like FILL_CENTER.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { previewView = it },
        )
        DetectionOverlay(
            frame = vm.frame,
            countedIds = vm.countedIds,
            minHits = ready.minHits,
            minMeanScore = ready.minMeanScore,
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            StatusBar(hud, ready.evaluationMode, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WarningsBanner(hud.warnings)
                    if (hud.paused) PausedBanner()
                    NoticesList(hud.notices, Modifier.widthIn(max = 460.dp))
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = { vm.setPaused(!hud.paused) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text(
                                if (hud.paused) stringResource(R.string.live_resume) else stringResource(R.string.live_pause),
                                fontSize = 16.sp,
                            )
                        }
                        OutlinedButton(
                            onClick = { confirmFinish = true },
                            modifier = Modifier.heightIn(min = 56.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xB3000000), contentColor = Color.White),
                        ) { Text(stringResource(R.string.live_finish), fontSize = 16.sp) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(240.dp).fillMaxHeight()) {
                    CountsPanel(
                        classes = ready.classes,
                        plantNumber = hud.plantNumber,
                        plantCounts = hud.plantCounts,
                        routeCounts = hud.sessionCounts,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { vm.nextPlant() },
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                    ) {
                        Text(
                            stringResource(R.string.live_next_plant),
                            fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (confirmFinish) {
        val total = hud.sessionCounts?.total ?: 0
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(stringResource(R.string.live_finish_title)) },
            text = {
                Text(
                    if (hud.hasProcessedFrames) {
                        stringResource(R.string.live_finish_message, total)
                    } else {
                        stringResource(R.string.live_finish_no_frames)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finish() }) {
                    Text(stringResource(R.string.live_finish_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.live_cancel)) }
            },
        )
    }
}

@Composable
private fun Message(text: String, showProgress: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
        }
        Text(text, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}

/** Full-screen explanation of a failure that makes counting impossible; never a black screen, never fake data. */
@Composable
private fun ErrorPanel(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction, modifier = Modifier.heightIn(min = 56.dp)) { Text(actionLabel, fontSize = 16.sp) }
    }
}

@Composable
private fun CameraPermissionPanel(onGrant: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.live_camera_permission_title),
            color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.live_camera_permission_message),
            color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onGrant, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.live_camera_permission_grant))
            }
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text(stringResource(R.string.live_open_settings)) }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.live_close_session), color = Color.White)
            }
        }
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
