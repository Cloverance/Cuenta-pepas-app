package app.cuentapepas.pipeline

import app.cuentapepas.core.detect.Detection
import app.cuentapepas.core.quality.QualitySample
import app.cuentapepas.core.quality.QualityWarning
import app.cuentapepas.core.quality.Shift
import app.cuentapepas.core.track.TrackSnapshot
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the logger needs about one frame. [detections] are in source-frame pixels, like the tracks.
 * All members must be treated as immutable after the record is handed to [EvaluationLogger.log].
 */
class EvaluationRecord(
    val frameIndex: Long,
    val tsNs: Long,
    val plant: Int?,
    val quality: QualitySample?,
    val cameraShift: Shift,
    val inferenceMs: Float,
    val detections: List<Detection>,
    val tracks: List<TrackSnapshot>,
    val warnings: Set<QualityWarning>,
)

/**
 * Writes one JSON object per frame (JSONL):
 * `{f, ts, plant, q:{sharp, bright, brightFrac, dx, dy}, inf_ms, dets:[[x1,y1,x2,y2,score,cls],...],
 * tracks:[[id,x1,y1,x2,y2,hits,cls],...], warn:[...]}`.
 *
 * Lines are formatted and written by a dedicated thread, so [log] only enqueues and the analysis thread never
 * waits on the disk. Nothing fails silently: a write error stops the writer and is kept in [error]; a full queue
 * drops the frame and is reported by [error] too. The frame processor copies [error] into every `FrameResult`
 * (`loggingError`) so the UI shows it.
 *
 * Non-finite numbers (NaN/Infinity) are written as `null`, because JSON has no representation for them.
 *
 * @throws java.io.IOException from the constructor when the file cannot be opened.
 */
class EvaluationLogger(val file: File, queueCapacity: Int = DEFAULT_QUEUE_CAPACITY) : AutoCloseable {

    private val queue = ArrayBlockingQueue<Any>(queueCapacity)
    private val writer: BufferedWriter
    private val thread: Thread

    @Volatile private var firstError: String? = null

    @Volatile private var dropped = 0L

    @Volatile private var closed = false

    init {
        file.absoluteFile.parentFile?.mkdirs()
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), BUFFER_CHARS)
        thread = Thread(::drain, "eval-logger").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Technical description (English) of the first write failure, or of frames lost to a full queue; null while
     * everything is fine. The UI wraps it in a Spanish message.
     */
    val error: String?
        get() {
            firstError?.let { return it }
            val d = dropped
            return if (d > 0) "evaluation log queue full, $d frames dropped" else null
        }

    /** Enqueues one frame. Never blocks. Returns false (and records the loss in [error]) if it was not accepted. */
    fun log(record: EvaluationRecord): Boolean {
        if (closed || firstError != null) return false
        if (queue.offer(record)) return true
        dropped++
        return false
    }

    private fun drain() {
        val sb = StringBuilder(2048)
        try {
            while (true) {
                val item = queue.poll(FLUSH_AFTER_MS, TimeUnit.MILLISECONDS)
                if (item == null) {
                    writer.flush() // idle: make what we have durable
                    continue
                }
                if (item === STOP) break
                sb.setLength(0)
                format(item as EvaluationRecord, sb)
                sb.append('\n')
                writer.append(sb)
            }
        } catch (e: Exception) {
            firstError = "write to ${file.name} failed: ${e.message ?: e::class.java.simpleName}"
        } finally {
            try {
                writer.close() // flushes
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = "closing ${file.name} failed: ${e.message ?: e::class.java.simpleName}"
                }
            }
        }
    }

    /** Stops accepting frames, writes what is queued and closes the file. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        // Offer may fail only if the queue is full; the writer is draining it, so a bounded wait is enough.
        if (!queue.offer(STOP, CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            firstError = firstError ?: "queue still full at close, pending frames were not written"
            thread.interrupt()
        }
        thread.join(CLOSE_WAIT_MS)
    }

    internal companion object {
        const val DEFAULT_QUEUE_CAPACITY = 256
        private const val BUFFER_CHARS = 64 * 1024
        private const val FLUSH_AFTER_MS = 500L
        private const val CLOSE_WAIT_MS = 5_000L
        private val STOP = Any()

        fun format(r: EvaluationRecord, sb: StringBuilder) {
            sb.append("{\"f\":").append(r.frameIndex)
            sb.append(",\"ts\":").append(r.tsNs)
            sb.append(",\"plant\":")
            if (r.plant != null) sb.append(r.plant) else sb.append("null")
            sb.append(",\"q\":")
            val q = r.quality
            if (q == null) {
                sb.append("null")
            } else {
                sb.append("{\"sharp\":"); num(sb, q.sharpness)
                sb.append(",\"bright\":"); num(sb, q.meanBrightness)
                sb.append(",\"brightFrac\":"); num(sb, q.brightFraction)
                sb.append(",\"dx\":"); num(sb, r.cameraShift.dx)
                sb.append(",\"dy\":"); num(sb, r.cameraShift.dy)
                sb.append('}')
            }
            sb.append(",\"inf_ms\":"); num(sb, r.inferenceMs)
            sb.append(",\"dets\":[")
            for ((i, d) in r.detections.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append('[')
                num(sb, d.box.x1); sb.append(','); num(sb, d.box.y1); sb.append(',')
                num(sb, d.box.x2); sb.append(','); num(sb, d.box.y2); sb.append(',')
                num(sb, d.score); sb.append(',').append(d.cls).append(']')
            }
            sb.append("],\"tracks\":[")
            for ((i, t) in r.tracks.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append('[').append(t.id).append(',')
                num(sb, t.box.x1); sb.append(','); num(sb, t.box.y1); sb.append(',')
                num(sb, t.box.x2); sb.append(','); num(sb, t.box.y2); sb.append(',')
                sb.append(t.hits).append(',').append(argmax(t.classProbs)).append(']')
            }
            sb.append("],\"warn\":[")
            for ((i, w) in r.warnings.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append('"').append(w.name).append('"') // enum names: [A-Z_], no escaping needed
            }
            sb.append("]}")
        }

        private fun num(sb: StringBuilder, v: Float) {
            if (v.isFinite()) sb.append(v) else sb.append("null")
        }

        private fun num(sb: StringBuilder, v: Double) {
            if (v.isFinite()) sb.append(v) else sb.append("null")
        }

        private fun argmax(p: FloatArray): Int {
            var best = 0
            for (i in 1 until p.size) if (p[i] > p[best]) best = i
            return best
        }
    }
}
