package app.cuentapepas.ui.live

import android.app.Application
import android.os.Environment
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.cuentapepas.CuentapepasApp
import app.cuentapepas.R
import app.cuentapepas.camera.CameraController
import app.cuentapepas.camera.RecordingEvent
import app.cuentapepas.core.count.ClassCounts
import app.cuentapepas.core.live.PlantLog
import app.cuentapepas.core.live.SessionStatsAssembler
import app.cuentapepas.core.live.WarningTally
import app.cuentapepas.core.perf.PerfStats
import app.cuentapepas.core.quality.GlobalMotionEstimator
import app.cuentapepas.core.quality.QualityAdvisor
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.session.SessionMeta
import app.cuentapepas.core.session.SessionStatus
import app.cuentapepas.core.session.Side
import app.cuentapepas.core.store.AppSettings
import app.cuentapepas.core.store.CorruptDataException
import app.cuentapepas.core.track.ByteTracker
import app.cuentapepas.core.count.TrackCounter
import app.cuentapepas.device.DeviceMonitor
import app.cuentapepas.device.GpsState
import app.cuentapepas.device.LocationRecorder
import app.cuentapepas.inference.FramePreprocessor
import app.cuentapepas.inference.ModelInvalidException
import app.cuentapepas.inference.ModelLoadException
import app.cuentapepas.inference.ModelMissingException
import app.cuentapepas.pipeline.EvaluationLogger
import app.cuentapepas.pipeline.FrameProcessor
import app.cuentapepas.pipeline.FrameResult
import app.cuentapepas.pipeline.LiveDetectionSettings
import app.cuentapepas.pipeline.ProcessorStatus
import app.cuentapepas.inference.FallbackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** Where the live screen is in its life. */
sealed interface LivePhase {
    /** Loading the session, model and detector. */
    data object Loading : LivePhase

    /** Camera can be bound and counting runs. */
    data class Ready(
        val classes: List<String>,
        val evaluationMode: Boolean,
        val row: Int,
        val side: Side,
        val minHits: Int,
        val minMeanScore: Float,
    ) : LivePhase

    /**
     * Counting is impossible ([message] is Spanish, ready to show; never a fake detection). [canAbandon] says whether
     * the session exists and can be closed (marked abandoned) from here.
     */
    data class Failed(val message: String, val canAbandon: Boolean) : LivePhase

    /** Saving and closing the session. */
    data object Finishing : LivePhase

    /** Saving failed; the session is still ACTIVE and [LiveViewModel.finish] can be retried. */
    data class SaveFailed(val message: String) : LivePhase
}

/** Low-frequency state shown by the status bar, counts panel, warnings banner and notices. */
data class LiveHud(
    val plantNumber: Int? = null,
    val plantCounts: ClassCounts? = null,
    val sessionCounts: ClassCounts? = null,
    val warnings: Set<QualityWarning> = emptySet(),
    /** Frames per second over the recent window; NaN = no data. */
    val fps: Float = Float.NaN,
    val delegate: String? = null,
    val thermal: Int = DeviceMonitor.NO_DATA,
    val batteryPct: Int? = null,
    val gps: GpsState = GpsState.Idle,
    val paused: Boolean = false,
    /** True once the detector has processed at least one frame. */
    val hasProcessedFrames: Boolean = false,
    /** Informational and error lines (Spanish), shown small under the status bar. */
    val notices: List<String> = emptyList(),
)

/**
 * State and commands of the live screen. Owns the frame processor, camera controller, device monitor and location
 * recorder of one session; everything is released in [finish] or [onCleared].
 *
 * Counting accuracy rules: next-plant reads the closing plant's counts and switches the counter's plant in one step on
 * the processing thread, so no cherry is attributed to the wrong plant; commands are serialised by a mutex.
 */
class LiveViewModel(app: Application, private val sessionId: String) : AndroidViewModel(app) {
    private val services = (app as CuentapepasApp).services
    private val store = services.store

    val camera = CameraController(app)
    private val monitor = DeviceMonitor(app)
    private val location = LocationRecorder(app, viewModelScope, sink = { pts -> store.appendGps(sessionId, pts) })

    private val phaseFlow = MutableStateFlow<LivePhase>(LivePhase.Loading)
    val phase: StateFlow<LivePhase> = phaseFlow.asStateFlow()

    private val hudFlow = MutableStateFlow(LiveHud())
    val hud: StateFlow<LiveHud> = hudFlow.asStateFlow()

    private val frameFlow = MutableStateFlow<FrameResult?>(null)

    /** Latest frame result (every frame); read it from draw code only, never from composition. */
    val frame: StateFlow<FrameResult?> = frameFlow.asStateFlow()

    private val countedFlow = MutableStateFlow<Set<Int>>(emptySet())

    /** Ids of the tracks the counter has confirmed, refreshed whenever the session total changes. */
    val countedIds: StateFlow<Set<Int>> = countedFlow.asStateFlow()

    private val finishedFlow = MutableStateFlow<String?>(null)

    /** Set to the session id once it was saved and closed; the screen then navigates on. */
    val finished: StateFlow<String?> = finishedFlow.asStateFlow()

    private val commandMutex = Mutex()
    private val tally = WarningTally()
    private var sticky: List<String> = emptyList()

    private var meta: SessionMeta? = null
    private var processor: FrameProcessor? = null
    private var plantLog: PlantLog? = null
    private var liveStartMs = 0L
    private var batteryStartPct: Int? = null
    private var userPaused = false
    private var lastNextPlantAt = 0L
    private var recordingSegment = 0
    private var released = false

    init {
        viewModelScope.launch { setup() }
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    // ---- Setup ----

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun fail(message: String, canAbandon: Boolean) {
        phaseFlow.value = LivePhase.Failed(message, canAbandon)
    }

    private suspend fun setup() {
        val loaded = try {
            store.session(sessionId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(str(R.string.live_session_error, e.message ?: e::class.java.simpleName), canAbandon = false)
            return
        }
        if (loaded.status != SessionStatus.ACTIVE) {
            fail(str(R.string.live_session_not_active), canAbandon = false)
            return
        }
        meta = loaded

        val spec: app.cuentapepas.core.detect.ModelSpec
        val settings: AppSettings
        val detector: app.cuentapepas.inference.TfliteDetector
        try {
            spec = services.modelSpec
            // The session's own snapshot rules, so a run is reproducible whatever the settings screen says today.
            settings = AppSettings.fromMap(loaded.settings, services.settings.load())
            detector = withContext(Dispatchers.Default) { services.detectorFactory(true) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(describeError(e), canAbandon = true)
            return
        }

        var logger: EvaluationLogger? = null
        if (loaded.evaluationMode) {
            try {
                logger = withContext(Dispatchers.IO) { EvaluationLogger(store.framesFile(sessionId)) }
            } catch (e: IOException) {
                addSticky(str(R.string.live_eval_log_error, e.message ?: e::class.java.simpleName))
            }
        }

        val proc = FrameProcessor(
            detector = detector,
            preprocessor = FramePreprocessor(spec),
            settings = LiveDetectionSettings(settings.scoreThreshold, settings.nmsIou),
            counter = TrackCounter(spec.numClasses, settings.minHits, settings.minMeanScore),
            tracker = ByteTracker(settings.tracker),
            advisor = QualityAdvisor(settings.quality),
            motion = GlobalMotionEstimator(),
            perf = PerfStats(),
            logger = logger,
            thermalStatus = { monitor.thermalStatus },
        )
        processor = proc

        // Wait for the detector; frames before that would all be errors.
        val status = proc.status.first { it !is ProcessorStatus.Initializing }
        if (status is ProcessorStatus.Failed) {
            fail(describeError(status.error), canAbandon = true)
            return
        }
        status as ProcessorStatus.Ready
        hudFlow.update { it.copy(delegate = status.delegate.name) }
        status.delegate.fallback?.let { fb ->
            addSticky(
                when (fb.kind) {
                    FallbackKind.GPU_DISABLED_BY_CALLER -> str(R.string.delegate_fallback_disabled)
                    FallbackKind.GPU_NOT_SUPPORTED -> str(R.string.delegate_fallback_not_supported)
                    FallbackKind.GPU_INIT_FAILED -> str(R.string.delegate_fallback_init_failed, fb.detail ?: "?")
                },
            )
        }

        val now = System.currentTimeMillis()
        liveStartMs = now
        val log = PlantLog(sessionId, loaded.firstPlant, loaded.plantStep, now)
        plantLog = log
        proc.startPlant(log.currentPlant)
        batteryStartPct = monitor.batteryPct()

        viewModelScope.launch { collectResults(proc) }
        viewModelScope.launch { refreshCountedOnTotalChange(proc) }
        viewModelScope.launch {
            while (true) {
                delay(AUTOSAVE_MS)
                saveProgress()
            }
        }

        phaseFlow.value = LivePhase.Ready(
            classes = spec.classes,
            evaluationMode = loaded.evaluationMode,
            row = loaded.row,
            side = loaded.side,
            minHits = settings.minHits,
            minMeanScore = settings.minMeanScore,
        )
    }

    private fun describeError(e: Throwable): String = when (e) {
        is ModelMissingException -> str(R.string.model_missing, e.assetPath)
        is ModelInvalidException -> str(R.string.model_invalid, e.message ?: "")
        is ModelLoadException -> str(R.string.model_load_failed, e.message ?: "")
        is CorruptDataException -> str(R.string.data_corrupt, e.path)
        else -> str(R.string.model_load_failed, "${e::class.java.simpleName}: ${e.message}")
    }

    private suspend fun collectResults(proc: FrameProcessor) {
        proc.results.collect { r ->
            frameFlow.value = r
            if (r.frameIndex > 0) tally.add(r.frameIndex, r.warnings, r.paused)
            hudFlow.update {
                it.copy(
                    plantNumber = r.plantNumber,
                    plantCounts = r.currentPlantCounts,
                    sessionCounts = r.sessionCounts,
                    warnings = r.warnings,
                    fps = if (r.fps.isNaN()) Float.NaN else (r.fps * 10f).roundToInt() / 10f,
                    delegate = r.delegate ?: it.delegate,
                    hasProcessedFrames = !r.medianInferenceMs.isNaN(),
                    notices = buildNotices(r),
                )
            }
        }
    }

    private suspend fun refreshCountedOnTotalChange(proc: FrameProcessor) {
        hudFlow.map { it.sessionCounts?.total ?: 0 }.distinctUntilChanged().collect {
            try {
                countedFlow.value = withContext(Dispatchers.IO) {
                    proc.withCounter { c -> c.countedTracks().mapTo(HashSet()) { t -> t.id } }.get()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The processor is shutting down; the overlay keeps the last known set.
            }
        }
    }

    private fun buildNotices(r: FrameResult?): List<String> {
        val out = ArrayList<String>(sticky)
        if (r != null) {
            if (r.throttled) out += str(R.string.throttled_notice)
            if (r.errorCount > 0) out += str(R.string.frame_errors, r.errorCount, r.errorMessage ?: "")
            r.loggingError?.let { out += str(R.string.logging_error, it) }
        }
        location.sinkError?.let { out += str(R.string.live_gps_save_error, it) }
        return out
    }

    private fun addSticky(message: String) {
        if (message !in sticky) sticky = sticky + message
        hudFlow.update { it.copy(notices = buildNotices(frameFlow.value)) }
    }

    private fun removeSticky(message: String) {
        sticky = sticky - message
        hudFlow.update { it.copy(notices = buildNotices(frameFlow.value)) }
    }

    private fun tick() {
        hudFlow.update {
            it.copy(
                thermal = monitor.thermalStatus,
                batteryPct = monitor.batteryPct(),
                gps = location.state.value,
                notices = buildNotices(frameFlow.value),
            )
        }
    }

    // ---- Camera ----

    /** Binds the camera for the screen's lifecycle; on failure the phase becomes [LivePhase.Failed]. */
    suspend fun bindCamera(owner: LifecycleOwner, previewView: PreviewView, rotation: Int) {
        val ready = phaseFlow.value as? LivePhase.Ready ?: return
        val proc = processor ?: return
        try {
            val outcome = camera.bind(owner, previewView, proc, proc.executor, ready.evaluationMode, rotation)
            if (outcome.videoUnavailable) addSticky(str(R.string.live_video_unavailable))
            if (outcome.videoBound) startNextRecording()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(str(R.string.live_camera_error, e.message ?: e::class.java.simpleName), canAbandon = true)
        }
    }

    fun unbindCamera() = camera.unbind()

    private fun startNextRecording() {
        val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (dir == null) {
            addSticky(str(R.string.live_video_no_storage))
            return
        }
        val n = recordingSegment++
        val file = File(dir, if (n == 0) "$sessionId.mp4" else "${sessionId}_$n.mp4")
        camera.startRecording(file) { event ->
            when (event) {
                is RecordingEvent.Failed -> addSticky(str(R.string.live_video_failed, event.code))
                is RecordingEvent.Interrupted -> addSticky(str(R.string.live_video_interrupted))
                is RecordingEvent.Finished -> Unit
            }
        }
    }

    // ---- Commands ----

    /** Advances to the next plant (ignored unless counting is ready; repeated presses within 700 ms count once). */
    fun nextPlant() {
        if (phaseFlow.value !is LivePhase.Ready) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNextPlantAt < NEXT_PLANT_DEBOUNCE_MS) return
        lastNextPlantAt = now
        viewModelScope.launch {
            commandMutex.withLock {
                val proc = processor ?: return@withLock
                val log = plantLog ?: return@withLock
                if (phaseFlow.value !is LivePhase.Ready) return@withLock
                val closing = log.currentPlant
                val next = log.nextPlant
                try {
                    // Read the closing plant's counts and switch plants in ONE step on the processing thread.
                    val counts = withContext(Dispatchers.IO) {
                        proc.withCounter { c ->
                            val k = c.plantCounts(closing)
                            c.startPlant(next)
                            k
                        }.get()
                    }
                    log.advance(counts, System.currentTimeMillis())
                    proc.startPlant(log.currentPlant) // already switched; this republishes the plant to the UI/logger
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    addSticky(str(R.string.live_save_error, e.message ?: e::class.java.simpleName))
                    return@withLock
                }
                saveProgressLocked()
            }
        }
    }

    fun setPaused(paused: Boolean) {
        userPaused = paused
        processor?.paused = paused
        hudFlow.update { it.copy(paused = paused) }
        if (!paused) removeSticky(str(R.string.live_background_paused))
    }

    /** The app left the foreground: pause counting and persist what exists. The session stays ACTIVE. */
    fun onBackground() {
        if (phaseFlow.value !is LivePhase.Ready) return
        setPaused(true)
        addSticky(str(R.string.live_background_paused))
        viewModelScope.launch {
            commandMutex.withLock { saveProgressLocked() }
            location.flush()
        }
    }

    /** The app is visible again: the evaluation video (stopped with the camera) starts a new segment. */
    fun onForeground() {
        if (phaseFlow.value is LivePhase.Ready && camera.isBound && camera.canRecord && !camera.isRecording) {
            startNextRecording()
        }
    }

    fun startLocation() {
        if (phaseFlow.value is LivePhase.Ready) location.start()
    }

    private suspend fun saveProgress() {
        if (phaseFlow.value !is LivePhase.Ready) return
        commandMutex.withLock { saveProgressLocked() }
    }

    /** Persists plants (current one still open) and pending GPS. Call with [commandMutex] held. */
    private suspend fun saveProgressLocked() {
        val proc = processor ?: return
        val log = plantLog ?: return
        try {
            val current = log.currentPlant
            val counts = withContext(Dispatchers.IO) { proc.withCounter { it.plantCounts(current) }.get() }
            store.savePlants(sessionId, log.records(counts, System.currentTimeMillis(), closeCurrent = false))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addSticky(str(R.string.live_save_error, e.message ?: e::class.java.simpleName))
        }
    }

    /**
     * Ends the session: stops camera, recording and GPS, saves the last plant and the session stats, then marks the
     * session COMPLETED (or ABANDONED when not a single frame was processed, so an empty run never counts as a pass)
     * and sets [finished]. If saving fails the phase becomes [LivePhase.SaveFailed] and this can be called again.
     */
    fun finish() {
        if (phaseFlow.value is LivePhase.Finishing) return
        viewModelScope.launch {
            commandMutex.withLock {
                if (phaseFlow.value is LivePhase.Finishing || finishedFlow.value != null) return@withLock
                phaseFlow.value = LivePhase.Finishing
                try {
                    closeSession()
                    finishedFlow.value = sessionId
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    phaseFlow.value =
                        LivePhase.SaveFailed(str(R.string.live_save_error, e.message ?: e::class.java.simpleName))
                }
            }
        }
    }

    private suspend fun closeSession() {
        val loaded = meta
        val proc = processor
        val log = plantLog
        val now = System.currentTimeMillis()

        camera.stopRecording()
        camera.unbind() // no more frames reach the processor
        location.stop()
        location.flush()

        var frames = 0
        if (proc != null && log != null && loaded != null) {
            proc.paused = true
            val current = log.currentPlant
            val counts = withContext(Dispatchers.IO) { proc.withCounter { it.plantCounts(current) }.get() }
            val durationS = (now - liveStartMs) / 1000.0
            val delegate = frameFlow.value?.delegate ?: hudFlow.value.delegate
            val stats = withContext(Dispatchers.IO) {
                proc.withPerf { perf ->
                    SessionStatsAssembler.build(
                        perf = perf,
                        durationS = durationS,
                        delegate = delegate,
                        maxThermalStatus = monitor.maxThermalSeen,
                        batteryStartPct = batteryStartPct,
                        batteryEndPct = monitor.batteryPct(),
                        warningFractions = tally.fractions(),
                        gps = location.points,
                    )
                }.get()
            }
            frames = stats.framesProcessed
            store.savePlants(sessionId, log.records(counts, now, closeCurrent = true))
            store.saveStats(sessionId, stats)
        }
        if (loaded != null) {
            if (frames == 0) {
                store.updateSession(loaded.copy(status = SessionStatus.ABANDONED, endedAtMs = now))
            } else {
                store.completeSession(sessionId, now)
            }
        }
        releaseResources()
    }

    private suspend fun releaseResources() {
        if (released) return
        released = true
        val proc = processor
        monitor.close()
        if (proc != null) {
            withContext(Dispatchers.IO) {
                try {
                    proc.close()
                } catch (e: Exception) {
                    // The data is already saved; a slow or failing detector release must not block leaving.
                }
            }
        }
    }

    override fun onCleared() {
        if (!released) {
            released = true
            camera.unbind()
            location.stop()
            monitor.close()
            val proc = processor
            if (proc != null) {
                // close() waits for the processing thread; never block the main thread on it.
                Thread({ try { proc.close() } catch (_: Exception) { } }, "frame-processor-release").start()
            }
        }
        super.onCleared()
    }

    class Factory(private val app: Application, private val sessionId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveViewModel(app, sessionId) as T
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val AUTOSAVE_MS = 20_000L
        const val NEXT_PLANT_DEBOUNCE_MS = 700L
    }
}
