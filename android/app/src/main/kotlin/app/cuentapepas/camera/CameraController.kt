package app.cuentapepas.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of [CameraController.bind]. */
data class BindOutcome(
    /** True when a `VideoCapture` is bound together with preview and analysis. */
    val videoBound: Boolean,
    /** True when video was requested but the 3-use-case binding failed and the camera runs without it. */
    val videoUnavailable: Boolean,
    /** The original exception that made the 3-use-case binding fail (set exactly when [videoUnavailable]). */
    val videoCause: Throwable? = null,
)

/** What [CameraController.stopRecording] found. */
enum class StopResult {
    /** There was no recording to stop. */
    NOT_RECORDING,

    /** The recording was stopped and its file finalised in time. */
    FINALIZED,

    /** The file was not finalised within the timeout: the video may be truncated or unplayable. */
    TIMED_OUT,
}

/** What happened to a video recording, reported on the main thread. */
sealed interface RecordingEvent {
    /** The file was finalised correctly. */
    data class Finished(val file: File) : RecordingEvent

    /** The recording ended because the camera source went inactive (app sent to background, camera unbound). */
    data class Interrupted(val file: File) : RecordingEvent

    /** The recording failed; [code] is a `VideoRecordEvent.Finalize.ERROR_*`. */
    data class Failed(val file: File, val code: Int) : RecordingEvent
}

/**
 * Binds the back camera to a lifecycle with preview + analysis (+ optional evaluation video) and controls the video
 * recording. Owned by the live view model; holds no view after [unbind].
 *
 * Use-case setup: 16:9 resolution selector preferring 1280x720 for preview, analysis and video; analysis is
 * `STRATEGY_KEEP_ONLY_LATEST` with YUV_420_888 output and its analyzer runs on the executor given to [bind]
 * (the frame processor's own thread). Video is HD, **without audio**.
 *
 * All methods except [stopRecording] must be called on the main thread.
 */
class CameraController(context: Context) {
    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var finalized: CompletableDeferred<Unit>? = null

    /** True while use cases are bound (the lifecycle may still have them paused). */
    val isBound: Boolean get() = provider != null && analysis != null

    /** True when a video use case is bound, i.e. [startRecording] can work. */
    val canRecord: Boolean get() = videoCapture != null

    val isRecording: Boolean get() = recording != null

    private fun resolutionSelector() = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(WIDTH, HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
        )
        .build()

    private suspend fun awaitProvider(): ProcessCameraProvider = provider ?: suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, mainExecutor)
    }

    /**
     * Binds preview + analysis (+ video when [withVideo]). If the 3-use-case binding is rejected the camera is
     * bound again without video and [BindOutcome.videoUnavailable] is true. Any failure of the 2-use-case binding is
     * thrown (camera missing, in use, ...).
     *
     * @param rotation `Surface.ROTATION_*` of the display.
     */
    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        analyzerExecutor: Executor,
        withVideo: Boolean,
        rotation: Int,
    ): BindOutcome {
        val cameraProvider = awaitProvider()
        provider = cameraProvider
        releaseRecording()
        cameraProvider.unbindAll()

        fun build(video: Boolean): List<UseCase> {
            val p = Preview.Builder().setResolutionSelector(resolutionSelector()).setTargetRotation(rotation).build()
            p.setSurfaceProvider(previewView.surfaceProvider)
            val a = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(rotation)
                .build()
            a.setAnalyzer(analyzerExecutor, analyzer)
            val v = if (video) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)),
                    )
                    .build()
                VideoCapture.withOutput(recorder).also { it.targetRotation = rotation }
            } else {
                null
            }
            preview = p
            analysis = a
            videoCapture = v
            return listOfNotNull(p, a, v)
        }

        var videoUnavailable = false
        var videoCause: Throwable? = null
        try {
            cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *build(withVideo).toTypedArray())
        } catch (e: Exception) {
            if (!withVideo) {
                Log.e(TAG, "Camera binding failed", e)
                clearUseCases()
                throw e
            }
            // The device cannot run preview + analysis + video together: keep counting, tell the user.
            Log.e(TAG, "Binding preview + analysis + video failed; continuing without video", e)
            cameraProvider.unbindAll()
            videoUnavailable = true
            videoCause = e
            try {
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *build(false).toTypedArray())
            } catch (e2: Exception) {
                Log.e(TAG, "Camera binding without video failed too", e2)
                clearUseCases()
                e2.addSuppressed(e)
                throw e2
            }
        }
        return BindOutcome(videoBound = videoCapture != null, videoUnavailable = videoUnavailable, videoCause = videoCause)
    }

    /** Applies a new display rotation (e.g. the phone was turned 180 degrees) to every bound use case. */
    fun setRotation(rotation: Int) {
        preview?.targetRotation = rotation
        analysis?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    /**
     * Starts recording to [file] (no audio). Returns false when no video use case is bound. [onEvent] runs on the
     * main thread when the recording ends. May throw (e.g. IllegalStateException, SecurityException): callers must
     * catch and report it; counting does not depend on the video.
     */
    fun startRecording(file: File, onEvent: (RecordingEvent) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return true
        file.absoluteFile.parentFile?.mkdirs()
        val done = CompletableDeferred<Unit>()
        finalized = done
        recording = vc.output
            .prepareRecording(appContext, FileOutputOptions.Builder(file).build()) // no withAudioEnabled(): silent
            .start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    val code = event.error
                    onEvent(
                        when (code) {
                            VideoRecordEvent.Finalize.ERROR_NONE -> RecordingEvent.Finished(file)
                            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> RecordingEvent.Interrupted(file)
                            else -> RecordingEvent.Failed(file, code)
                        },
                    )
                    done.complete(Unit)
                }
            }
        return true
    }

    /**
     * Stops the recording and waits (up to [timeoutMs]) for the file to be finalised. Main thread. A
     * [StopResult.TIMED_OUT] result must be reported to the user: the video file may be incomplete.
     */
    suspend fun stopRecording(timeoutMs: Long = 5_000): StopResult {
        val rec = recording ?: return StopResult.NOT_RECORDING
        val done = finalized
        rec.stop()
        val finishedInTime = done == null || withTimeoutOrNull(timeoutMs) { done.await() } != null
        recording = null
        if (!finishedInTime) Log.e(TAG, "Video finalisation did not finish within $timeoutMs ms")
        return if (finishedInTime) StopResult.FINALIZED else StopResult.TIMED_OUT
    }

    private fun releaseRecording() {
        recording?.stop()
        recording = null
    }

    /** Unbinds everything and drops the references to the view and the analyzer. */
    fun unbind() {
        releaseRecording()
        analysis?.clearAnalyzer()
        preview?.setSurfaceProvider(null)
        provider?.unbindAll()
        clearUseCases()
    }

    private fun clearUseCases() {
        preview = null
        analysis = null
        videoCapture = null
    }

    private companion object {
        const val TAG = "Cuentapepas"
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
