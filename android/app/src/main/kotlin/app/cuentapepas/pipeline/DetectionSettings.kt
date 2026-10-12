package app.cuentapepas.pipeline

/**
 * Detection thresholds read by the frame processor on every frame (from its analysis thread), so
 * implementations must be safe to read while the UI thread writes them: use `@Volatile` fields or an
 * immutable snapshot swapped atomically.
 */
interface DetectionSettings {
    val scoreThreshold: Float
    val nmsIou: Float
}

/** Plain thread-safe holder; the UI writes the properties, the analysis thread reads them. */
class LiveDetectionSettings(scoreThreshold: Float, nmsIou: Float) : DetectionSettings {
    @Volatile override var scoreThreshold: Float = scoreThreshold

    @Volatile override var nmsIou: Float = nmsIou
}
