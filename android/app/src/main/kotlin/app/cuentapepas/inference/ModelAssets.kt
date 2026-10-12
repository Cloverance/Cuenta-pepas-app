package app.cuentapepas.inference

import android.content.Context
import app.cuentapepas.R
import app.cuentapepas.core.detect.ModelSpec
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Base of every failure to obtain a usable model; the UI maps the subtype to a Spanish message. */
sealed class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An expected file is not in `assets/model/` (the ML export was never copied there). */
class ModelMissingException(val assetPath: String) :
    ModelException("Falta el archivo del modelo en los recursos de la app: $assetPath (vea assets/model/README.txt)")

/** The file exists but cannot be parsed, or its contract is not one this app supports. */
class ModelInvalidException(message: String, cause: Throwable? = null) : ModelException(message, cause)

/** The file exists but could not be read or mapped (I/O error, or the asset was compressed). */
class ModelLoadException(val assetPath: String, cause: Throwable) :
    ModelException("No se pudo leer el recurso del modelo $assetPath", cause)

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
            throw ModelInvalidException("$path no es un contrato de modelo válido", e)
        }
    }

    /** @throws ModelLoadException when the asset directory cannot be listed (an I/O error is not "absent"). */
    fun exists(context: Context, fileName: String): Boolean =
        try {
            context.assets.list(DIR)?.contains(fileName) == true
        } catch (e: IOException) {
            throw ModelLoadException(DIR, e)
        }

    /** The file TfliteDetector uses: fp16 when its asset is present, otherwise fp32. */
    fun preferredFile(context: Context, spec: ModelSpec): ModelSpec.ModelFile =
        if (exists(context, spec.files.fp16.name)) spec.files.fp16 else spec.files.fp32

    private val verified = java.util.concurrent.ConcurrentHashMap<String, String>() // fileName -> sha256 already checked

    /** Memory-maps `assets/model/<fileName>`. Requires the asset to be stored uncompressed (`noCompress += "tflite"`). */
    fun mapModel(context: Context, fileName: String): MappedByteBuffer {
        val path = "$DIR/$fileName"
        if (!exists(context, fileName)) throw ModelMissingException(path)
        val buffer = try {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (e: IOException) {
            throw ModelLoadException(path, e)
        }
        verifySha256(context, fileName, buffer, declaredSha256(context, fileName))
        return buffer
    }

    /** The sha256 that model.json declares for [fileName]. @throws ModelInvalidException when it is not declared. */
    private fun declaredSha256(context: Context, fileName: String): String {
        val spec = loadSpec(context)
        val f = listOf(spec.files.fp16, spec.files.fp32).firstOrNull { it.name == fileName }
            ?: throw ModelInvalidException(context.getString(R.string.model_file_undeclared, fileName))
        return f.sha256
    }

    /**
     * Checks the mapped bytes against [expected] (hex, from model.json). A mismatch or a malformed digest is a
     * [ModelInvalidException]. A file already verified in this process is not hashed again.
     */
    private fun verifySha256(context: Context, fileName: String, buffer: MappedByteBuffer, expected: String) {
        val want = expected.trim().lowercase()
        if (verified[fileName] == want) return
        if (!Regex("[0-9a-f]{64}").matches(want)) {
            throw ModelInvalidException(context.getString(R.string.model_sha_malformed, fileName))
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(buffer.duplicate()) // duplicate: the caller's position/limit stay untouched
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) throw ModelInvalidException(context.getString(R.string.model_sha_mismatch, fileName))
        verified[fileName] = want
    }

}
