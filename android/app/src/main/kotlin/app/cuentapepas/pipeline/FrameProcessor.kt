package app.cuentapepas.pipeline

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.cuentapepas.core.count.TrackCounter
import app.cuentapepas.core.detect.Detection
import app.cuentapepas.core.detect.YoloxPostprocessor
import app.cuentapepas.core.perf.PerfStats
import app.cuentapepas.core.perf.RollingWindow
import app.cuentapepas.core.quality.GlobalMotionEstimator
import app.cuentapepas.core.quality.LumaImage
import app.cuentapepas.core.quality.QualityAdvisor
import app.cuentapepas.core.quality.QualityMetrics
import app.cuentapepas.core.quality.QualitySample
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.quality.Shift
import app.cuentapepas.core.track.ByteTracker
import app.cuentapepas.core.track.TrackSnapshot
import app.cuentapepas.device.DeviceMonitor
import app.cuentapepas.inference.DelegateInfo
import app.cuentapepas.inference.FramePreprocessor
import app.cuentapepas.inference.TfliteDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Lifecycle of the detector owned by a [FrameProcessor]. */
sealed interface ProcessorStatus {
    data object Initializing : ProcessorStatus

    /** Interpreter built; [delegate] says GPU or CPU and, for CPU, why. */
    data class Ready(val delegate: DelegateInfo, val modelFile: String) : ProcessorStatus

    /** Initialisation failed; [error] is typed (e.g. a `ModelException`) so the UI can pick its message. */
    data class Failed(val error: Throwable) : ProcessorStatus
}

/**
 * Per-frame pipeline: camera frame -> preprocessing -> camera motion -> quality -> detector -> postprocessing ->
 * tracker -> counter -> quality advisor -> perf -> [FrameResult].
 *
 * **Threading.** Everything runs on one dedicated thread ([executor]); register that same executor with
 * `ImageAnalysis.setAnalyzer(executor, processor)` so [analyze] is called on it. The detector (whose GPU
 * delegate is thread-bound), preprocessor, postprocessor, tracker, counter, advisor, motion estimator and logger
 * are created/used only there, so none of them needs to be thread-safe. State changes from other threads
 * ([startPlant], [startSession], [withCounter]) are posted to that thread. Only [paused] is a volatile flag read
 * across threads.
 *
 * Recommended camera setup: `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`, YUV_420_888 output (the default).
 *
 * **Errors.** A frame that throws is counted and reported in [FrameResult.errorMessage]/[FrameResult.errorCount];
 * nothing is swallowed. If the detector cannot be initialised ([status] becomes [ProcessorStatus.Failed], e.g.
 * model files missing) every frame publishes that error and no detection is ever fabricated.
 *
 * Call [startPlant] before (or right after) binding the camera: until a plant is active, counting a frame is an
 * error (the counter requires it) and is reported as such on every unpaused frame.
 *
 * The processor owns [detector], [preprocessor] and the logger and releases them in [close].
 */
class FrameProcessor(
    private val detector: TfliteDetector,
    private val preprocessor: FramePreprocessor,
    private val settings: DetectionSettings,
    counter: TrackCounter,
    tracker: ByteTracker,
    private val advisor: QualityAdvisor,
    private val motion: GlobalMotionEstimator,
    perf: PerfStats,
    logger: EvaluationLogger? = null,
    private val thermalStatus: () -> Int = { DeviceMonitor.NO_DATA },
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "frame-processor").apply { isDaemon = true }
    },
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val spec = detector.spec

    /** Single-threaded executor all work runs on; pass it to `ImageAnalysis.setAnalyzer`. */
    val executor: java.util.concurrent.Executor get() = executorService

    // ---- Published state ----
    private val resultFlow = MutableStateFlow(FrameResult.initial(spec.numClasses))

    /** Latest [FrameResult]; a new value per processed frame. */
    val results: StateFlow<FrameResult> = resultFlow.asStateFlow()

    private val statusFlow = MutableStateFlow<ProcessorStatus>(ProcessorStatus.Initializing)

    /** Detector state: initialising, ready (with delegate info) or failed (with the typed error). */
    val status: StateFlow<ProcessorStatus> = statusFlow.asStateFlow()

    /** While true, tracking continues but nothing new is counted. Safe to set from any thread. */
    @Volatile var paused: Boolean = false

    @Volatile private var closed = false

    // ---- State confined to the processing thread ----
    private var counter = counter
    private var tracker = tracker
    private var logger = logger
    private val postprocessor = YoloxPostprocessor(spec.numClasses)
    private var activePlant: Int? = null
    private var perf = perf
    private var lastFrameEndNs = 0L
    private val recentLatencyMs = RollingWindow()
    private val recentIntervalMs = RollingWindow()
    private var frameIndex = 0L
    private var errorCount = 0
    private var lastError: String? = null
    private var prevLuma: LumaImage? = null
    private var prevTsNs = 0L

    init {
        executorService.execute { initializeDetector() }
    }

    private fun initializeDetector() {
        try {
            detector.initialize()
            val info = checkNotNull(detector.delegateInfo)
            statusFlow.value = ProcessorStatus.Ready(info, detector.modelFileName)
        } catch (t: Throwable) {
            statusFlow.value = ProcessorStatus.Failed(t)
            lastError = "Detector initialisation failed: ${describe(t)}"
        }
    }

    // ---- Commands (callable from any thread) ----

    /** Starts (or switches to) a plant: cherries confirmed from now on are attributed to it. */
    fun startPlant(plantNumber: Int) {
        executorService.execute {
            counter.startPlant(plantNumber)
            activePlant = plantNumber
            resultFlow.value = resultFlow.value.copy(
                plantNumber = plantNumber,
                currentPlantCounts = counter.currentPlantCounts(),
                sessionCounts = counter.sessionCounts(),
            )
        }
    }

    /**
     * Begins a new session on the same (already initialised) detector: swaps in a fresh counter, tracker and
     * logger and resets quality, motion, perf and error state. Closes the previous logger. No plant is active
     * until [startPlant] is called.
     */
    fun startSession(counter: TrackCounter, tracker: ByteTracker, logger: EvaluationLogger? = null) {
        executorService.execute {
            this.logger?.close()
            this.counter = counter
            this.tracker = tracker
            this.logger = logger
            activePlant = null
            frameIndex = 0
            errorCount = 0
            lastError = if (statusFlow.value is ProcessorStatus.Failed) lastError else null
            prevLuma = null
            prevTsNs = 0
            advisor.reset()
            perf = PerfStats()
            lastFrameEndNs = 0L
            recentLatencyMs.clear()
            recentIntervalMs.clear()
            val st = statusFlow.value
            resultFlow.value = FrameResult.initial(spec.numClasses).copy(
                delegate = (st as? ProcessorStatus.Ready)?.delegate?.name,
                delegateFallback = (st as? ProcessorStatus.Ready)?.delegate?.fallback,
                errorMessage = lastError,
                paused = paused,
            )
        }
    }

    /**
     * Runs [block] on the processing thread with the live counter (e.g. to read `countedTracks()` or
     * per-plant counts for the session summary) and returns its result asynchronously. The counter must not
     * escape [block].
     */
    fun <T> withCounter(block: (TrackCounter) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ block(counter) }, executorService)

    /**
     * Runs [block] on the processing thread with the session-wide (cumulative) [PerfStats], e.g. to build the
     * session summary. The stats must not escape [block]. The live figures in [FrameResult] come from a short
     * rolling window instead, so they follow recent performance.
     */
    fun <T> withPerf(block: (PerfStats) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ block(perf) }, executorService)

    // ---- ImageAnalysis.Analyzer ----

    override fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            processFrame(image)
        } catch (e: Exception) {
            recordFrameError(image, e)
        } finally {
            image.close()
        }
    }

    private fun processFrame(image: ImageProxy) {
        val seen = ++frameIndex
        val tsNs = image.imageInfo.timestamp

        // Never pretend to detect: without a ready detector the frame is an error, reported loudly.
        val ready = when (val st = statusFlow.value) {
            is ProcessorStatus.Ready -> st
            is ProcessorStatus.Failed -> throw IllegalStateException("Detector unavailable: ${describe(st.error)}", st.error)
            ProcessorStatus.Initializing -> throw IllegalStateException("Detector is still initialising")
        }

        val thermal = thermalStatus()
        val throttled = thermal >= DeviceMonitor.THERMAL_STATUS_SEVERE
        if (throttled && seen % 2L == 0L) return // process one frame out of two

        // 1. Preprocess.
        val prep = preprocessor.prepare(image)

        // 2. Camera motion between consecutive processed frames, in source pixels.
        val prev = prevLuma
        val shift = if (prev != null) prep.lumaShiftToSource(motion.estimate(prev, prep.luma)) else Shift.ZERO
        val dtSeconds = if (prev != null) (tsNs - prevTsNs) / 1e9 else Double.NaN
        val frameWidthsPerSecond =
            if (prev != null && dtSeconds > 0.0) abs(shift.dx).toDouble() / prep.srcWidth / dtSeconds else Double.NaN
        prevLuma = prep.luma
        prevTsNs = tsNs

        // 3. Quality measurements.
        val brightness = QualityMetrics.brightness(prep.luma)
        val sample = QualitySample(
            sharpness = QualityMetrics.sharpness(prep.luma),
            meanBrightness = brightness.mean,
            brightFraction = brightness.brightFraction,
            frameWidthsPerSecond = frameWidthsPerSecond,
        )

        // 4. Inference.
        val t0 = System.nanoTime()
        val raw = detector.run(prep.input)
        val t1 = System.nanoTime()
        val inferenceMs = (t1 - t0) / 1e6f

        // 5. Postprocess in model pixels, then map back to the source frame.
        val dets: List<Detection> = postprocessor
            .process(raw, spec.numAnchors, spec.transposed, settings.scoreThreshold, settings.nmsIou, spec.maxDetections)
            .map { it.copy(box = prep.letterbox.toSource(it.box)) }

        // 6. Track.
        val tracks: List<TrackSnapshot> = tracker.update(dets, shift)

        // 7. Count (unless paused). A failure here must not hide the rest of the frame, but it is reported.
        val isPaused = paused
        var stageError: Throwable? = null
        if (!isPaused) {
            try {
                counter.observe(tracks)
            } catch (e: Exception) {
                stageError = e
            }
        }

        // 8. Quality warnings.
        val warnings: Set<QualityWarning> = advisor.update(sample)

        // 9. Perf.
        perf.addLatencyMs(inferenceMs.toDouble())
        recentLatencyMs.add(inferenceMs.toDouble())
        if (lastFrameEndNs != 0L) {
            val intervalMs = (t1 - lastFrameEndNs) / 1e6
            perf.addFrameIntervalMs(intervalMs)
            if (intervalMs > 0.0) recentIntervalMs.add(intervalMs)
        }
        lastFrameEndNs = t1
        // Live figures: median over the last ~60 processed frames (bounded ring buffer, sorted copy of <= 60 values).
        val liveMedianMs = recentLatencyMs.median()?.toFloat() ?: Float.NaN
        val liveFps = recentIntervalMs.median()?.let { (1000.0 / it).toFloat() } ?: Float.NaN

        if (stageError != null) {
            errorCount++
            lastError = "Counting failed on frame $seen: ${describe(stageError)}"
        }

        // Evaluation log (enqueue only; the writer thread does the I/O).
        logger?.log(EvaluationRecord(seen, tsNs, activePlant, sample, shift, inferenceMs, dets, tracks, warnings))

        // 10. Publish.
        resultFlow.value = FrameResult(
            frameIndex = seen,
            tsNs = tsNs,
            srcWidth = prep.srcWidth,
            srcHeight = prep.srcHeight,
            plantNumber = activePlant,
            tracks = tracks,
            currentPlantCounts = counter.currentPlantCounts(),
            sessionCounts = counter.sessionCounts(),
            warnings = warnings,
            quality = sample,
            cameraShift = shift,
            rawDetections = dets.size,
            inferenceMs = inferenceMs,
            medianInferenceMs = liveMedianMs,
            fps = liveFps,
            delegate = ready.delegate.name,
            delegateFallback = ready.delegate.fallback,
            throttled = throttled,
            thermalStatus = thermal,
            paused = isPaused,
            errorMessage = lastError,
            errorCount = errorCount,
            loggingError = logger?.error,
        )
        // 11. image.close() happens in analyze()'s finally.
    }

    private fun recordFrameError(image: ImageProxy, e: Exception) {
        errorCount++
        lastError = "Frame $frameIndex: ${describe(e)}"
        resultFlow.value = resultFlow.value.copy(
            frameIndex = frameIndex,
            tsNs = image.imageInfo.timestamp,
            errorMessage = lastError,
            errorCount = errorCount,
            loggingError = logger?.error,
        )
    }

    private fun describe(t: Throwable): String = "${t::class.java.simpleName}: ${t.message}"

    /**
     * Stops the processing thread and releases the detector, preprocessor and logger (on that thread, because
     * the GPU delegate must be closed where it was created). Unbind the camera analyzer first.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            executorService.submit {
                logger?.close()
                detector.close()
                preprocessor.release()
            }.get(CLOSE_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            executorService.shutdown()
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_S = 10L
    }
}
