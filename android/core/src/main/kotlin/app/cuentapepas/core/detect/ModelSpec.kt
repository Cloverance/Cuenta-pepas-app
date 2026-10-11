package app.cuentapepas.core.detect

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Contract with the model, read from `assets/model/model.json` (written by Plan A, Task A6). */
@Serializable
data class ModelSpec(
    @SerialName("model_id") val modelId: String,
    @SerialName("taxonomy_schema_version") val taxonomySchemaVersion: Int,
    val classes: List<String>,
    val input: Input,
    val output: Output,
    val postprocess: Postprocess,
    val files: Files,
    @SerialName("training_run") val trainingRun: String? = null,
    val metrics: Map<String, Double> = emptyMap(),
    @SerialName("license_attribution") val licenseAttribution: String? = null,
) {
    @Serializable
    data class Input(
        val name: String,
        val width: Int,
        val height: Int,
        val layout: String,
        val dtype: String,
        @SerialName("channel_order") val channelOrder: String,
        val range: List<Float>,
        val letterbox: String,
        @SerialName("pad_value") val padValue: Int,
    )

    @Serializable
    data class Output(
        val shape: List<Int>,
        val layout: List<String>,
        val coords: String,
        val activations: String,
        /** True when the TFLite output is `[1, 5 + numClasses, numAnchors]` instead of `[1, numAnchors, 5 + numClasses]`. */
        val transposed: Boolean = false,
    )

    @Serializable
    data class Postprocess(
        val score: String,
        @SerialName("default_score_threshold") val defaultScoreThreshold: Float,
        val nms: String,
        @SerialName("default_nms_iou") val defaultNmsIou: Float,
        @SerialName("max_detections") val maxDetections: Int,
    )

    @Serializable
    data class Files(val fp16: ModelFile, val fp32: ModelFile)

    @Serializable
    data class ModelFile(val name: String, val sha256: String)

    init {
        require(classes.isNotEmpty()) { "model.json: classes is empty" }
        require(input.width > 0 && input.height > 0) { "model.json: input size must be positive: ${input.width}x${input.height}" }
        require(output.shape.size == 3) { "model.json: output.shape must have 3 dims, got ${output.shape}" }
        val channels = if (output.transposed) output.shape[1] else output.shape[2]
        require(channels == 5 + classes.size) {
            "model.json: output has $channels channels but 5 + ${classes.size} classes were declared (shape=${output.shape}, transposed=${output.transposed})"
        }
    }

    val inputWidth: Int get() = input.width
    val inputHeight: Int get() = input.height
    val transposed: Boolean get() = output.transposed
    val numClasses: Int get() = classes.size

    /** Number of anchors (candidate boxes) per frame: dim 1, or dim 2 when [transposed]. */
    val numAnchors: Int get() = if (output.transposed) output.shape[2] else output.shape[1]

    val defaultScoreThreshold: Float get() = postprocess.defaultScoreThreshold
    val defaultNmsIou: Float get() = postprocess.defaultNmsIou
    val maxDetections: Int get() = postprocess.maxDetections

    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun parse(json: String): ModelSpec = lenientJson.decodeFromString(serializer(), json)
    }
}
