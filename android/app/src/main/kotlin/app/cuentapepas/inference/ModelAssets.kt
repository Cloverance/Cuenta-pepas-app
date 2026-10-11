package app.cuentapepas.inference

import android.content.Context
import app.cuentapepas.core.detect.ModelSpec
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Base of every failure to obtain a usable model; the UI maps the subtype to a Spanish message. */
sealed class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An expected file is not in `assets/model/` (the ML export was never copied there). */
class ModelMissingException(val assetPath: String) :
    ModelException("Model file missing from the app assets: $assetPath (see assets/model/README.txt)")

/** The file exists but cannot be parsed, or its contract is not one this app supports. */
class ModelInvalidException(message: String, cause: Throwable? = null) : ModelException(message, cause)

/** The file exists but could not be read or mapped (I/O error, or the asset was compressed). */
class ModelLoadException(val assetPath: String, cause: Throwable) :
    ModelException("Could not load model asset $assetPath: ${cause.message}", cause)

/** Access to the model files shipped in `assets/model/`. */
object ModelAssets {
    const val DIR = "model"
    const val SPEC_FILE = "model.json"

    /** Reads and validates `assets/model/model.json`. @throws ModelException when it is missing or invalid. */
    fun loadSpec(context: Context): ModelSpec {
        val path = "$DIR/$SPEC_FILE"
        if (!exists(context, SPEC_FILE)) throw ModelMissingException(path)
        val text = try {
            context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            throw ModelLoadException(path, e)
        }
        return try {
            ModelSpec.parse(text)
        } catch (e: Exception) { // SerializationException, IllegalArgumentException from ModelSpec.init
            throw ModelInvalidException("$path is not a valid model contract: ${e.message}", e)
        }
    }

    fun exists(context: Context, fileName: String): Boolean =
        context.assets.list(DIR)?.contains(fileName) == true

    /** Memory-maps `assets/model/<fileName>`. Requires the asset to be stored uncompressed (`noCompress += "tflite"`). */
    fun mapModel(context: Context, fileName: String): MappedByteBuffer {
        val path = "$DIR/$fileName"
        if (!exists(context, fileName)) throw ModelMissingException(path)
        try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: IOException) {
            throw ModelLoadException(path, e)
        }
    }
}
