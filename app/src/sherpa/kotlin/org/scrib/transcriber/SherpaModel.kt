package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import org.json.JSONObject

data class SherpaModelInfo(
    val id: String,
    val displayName: String,
    val modelType: String,
    val languages: List<String>?,
    val files: Map<String, String>,
    val totalBytes: Long
)

object SherpaModel {

    const val ENGINE_ID = "sherpa-onnx"

    const val ROLE_ENCODER = "encoder"
    const val ROLE_DECODER = "decoder"
    const val ROLE_JOINER = "joiner"
    const val ROLE_MODEL = "model"
    const val ROLE_TOKENS = "tokens"

    const val TYPE_DEFAULT = ""
    const val TYPE_NEMO = "nemo_transducer"
    const val TYPE_STREAMING_TRANSDUCER = "streaming_transducer"
    const val TYPE_SENSE_VOICE = "sense_voice"

    private const val SIDECAR = "model.json"

    private const val MIN_TOKENS_BYTES = 100L

    private val SENSE_VOICE_LANGUAGES = setOf("auto", "zh", "en", "ja", "ko", "yue")

    // Legacy curated installs (shipped by older builds, since removed from source): exact
    // ids and file names, so they can be moved under the new root and converted into a regular
    // user entry.
    private const val LEGACY_CURATED_ID = "sherpa-transducer"
    private const val LEGACY_OLD_ID = "gigaam-v3-e2e-rnnt"
    private const val LEGACY_DISPLAY_NAME = "GigaAM v3"
    private val LEGACY_FILES = mapOf(
        ROLE_ENCODER to "gigaam_v3_e2e_rnnt_encoder_int8.onnx",
        ROLE_DECODER to "gigaam_v3_e2e_rnnt_decoder.onnx",
        ROLE_JOINER to "gigaam_v3_e2e_rnnt_joint.onnx",
        ROLE_TOKENS to "gigaam_v3_e2e_rnnt_tokens.txt"
    )

    fun modelsRoot(context: Context): File =
        File(ModelManager.modelsDir(context), "sherpa").also { it.mkdirs() }

    fun dirFor(context: Context, id: String): File = File(modelsRoot(context), id)

    fun list(context: Context): List<SherpaModelInfo> {
        migrateLegacy(context.applicationContext)
        return modelsRoot(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readSidecar(it) }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
    }

    fun byId(context: Context, id: String): SherpaModelInfo? =
        list(context).firstOrNull { it.id == id }

    fun requiredRoles(modelType: String): List<String> =
        if (modelType == TYPE_SENSE_VOICE) {
            listOf(ROLE_MODEL, ROLE_TOKENS)
        } else {
            listOf(ROLE_ENCODER, ROLE_DECODER, ROLE_JOINER, ROLE_TOKENS)
        }

    fun isStreaming(modelType: String): Boolean =
        modelType == TYPE_STREAMING_TRANSDUCER

    fun isComplete(dir: File, files: Map<String, String>, modelType: String = TYPE_DEFAULT): Boolean {
        return requiredRoles(modelType).all { role ->
            val file = files[role]?.let { File(dir, it) } ?: return@all false
            file.isFile && if (role == ROLE_TOKENS) {
                file.length() > MIN_TOKENS_BYTES
            } else {
                file.length() > 0L
            }
        }
    }

    fun isComplete(dir: File, info: SherpaModelInfo): Boolean =
        isComplete(dir, info.files, info.modelType)

    fun buildConfig(
        dir: File,
        info: SherpaModelInfo,
        provider: String = "cpu",
        languageHint: String? = null
    ): OfflineRecognizerConfig {
        val tokens = File(dir, info.files.getValue(ROLE_TOKENS)).absolutePath
        val modelConfig = if (info.modelType == TYPE_SENSE_VOICE) {
            OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath,
                    language = senseVoiceLanguage(info, languageHint),
                    useInverseTextNormalization = true
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
        } else {
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, info.files.getValue(ROLE_ENCODER)).absolutePath,
                    decoder = File(dir, info.files.getValue(ROLE_DECODER)).absolutePath,
                    joiner = File(dir, info.files.getValue(ROLE_JOINER)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider,
                modelType = info.modelType
            )
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig
        )
    }

    fun buildOnlineConfig(
        dir: File,
        info: SherpaModelInfo,
        provider: String = "cpu"
    ): OnlineRecognizerConfig {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(dir, info.files.getValue(ROLE_ENCODER)).absolutePath,
                decoder = File(dir, info.files.getValue(ROLE_DECODER)).absolutePath,
                joiner = File(dir, info.files.getValue(ROLE_JOINER)).absolutePath
            ),
            tokens = File(dir, info.files.getValue(ROLE_TOKENS)).absolutePath,
            numThreads = 4,
            provider = provider,
            modelType = "zipformer2"
        )
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig,
            enableEndpoint = false
        )
    }

    fun senseVoiceLanguage(info: SherpaModelInfo, languageHint: String? = null): String {
        val hint = languageHint?.trim()?.lowercase()
        if (hint != null && hint in SENSE_VOICE_LANGUAGES) {
            return hint
        }
        return info.languages?.singleOrNull()?.lowercase()?.takeIf { it in SENSE_VOICE_LANGUAGES }
            ?: "auto"
    }

    fun validateLoadable(dir: File, info: SherpaModelInfo) {
        if (isStreaming(info.modelType)) {
            val recognizer = try {
                OnlineRecognizer(null, buildOnlineConfig(dir, info))
            } catch (e: Throwable) {
                throw RuntimeException("Sherpa could not load this model: ${e.message ?: "invalid files"}", e)
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(FloatArray(16_000), 16_000)
                stream.inputFinished()
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }
                recognizer.getResult(stream)
            } finally {
                stream.release()
                recognizer.release()
            }
        } else {
            val recognizer = try {
                OfflineRecognizer(null, buildConfig(dir, info))
            } catch (e: Throwable) {
                throw RuntimeException("Sherpa could not load this model: ${e.message ?: "invalid files"}", e)
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(FloatArray(16_000), 16_000)
                recognizer.decode(stream)
                recognizer.getResult(stream)
            } finally {
                stream.release()
                recognizer.release()
            }
        }
    }

    fun importModel(
        context: Context,
        displayName: String,
        modelType: String,
        languages: List<String>?,
        parts: Map<String, Uri>
    ): SherpaModelInfo {
        val app = context.applicationContext
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Enter a name" }
        val roles = requiredRoles(modelType)
        val missing = roles.filterNot { parts.containsKey(it) }
        require(missing.isEmpty()) { "Choose all model files" }
        val id = uniqueSlug(app, name)
        val dir = dirFor(app, id).also { it.mkdirs() }
        try {
            val names = roles.associateWith { role ->
                val extension = if (role == ROLE_TOKENS) ".txt" else ".onnx"
                copyPart(app, parts.getValue(role), dir, extension)
            }
            val info = SherpaModelInfo(
                id = id,
                displayName = name,
                modelType = modelType,
                languages = languages?.takeIf { it.isNotEmpty() },
                files = names,
                totalBytes = names.values.sumOf { File(dir, it).length() }
            )
            if (!isComplete(dir, info)) {
                throw RuntimeException("Model files are incomplete")
            }
            validateLoadable(dir, info)
            writeSidecar(dir, info)
            return info
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun deleteModel(context: Context, id: String): Boolean {
        val dir = dirFor(context.applicationContext, id)
        if (!dir.exists()) {
            return false
        }
        dir.deleteRecursively()
        return true
    }

    private fun copyPart(context: Context, uri: Uri, dir: File, extension: String): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw RuntimeException("Cannot open file")
        val fileName = (queryName(context, uri) ?: "part$extension").substringAfterLast('/').substringAfterLast('\\')
        val safe = fileName.takeIf { it.endsWith(extension, ignoreCase = true) } ?: (fileName + extension)
        val dest = File(dir, safe)
        input.use { source ->
            dest.outputStream().use { output -> source.copyTo(output) }
        }
        return dest.name
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    private fun uniqueSlug(context: Context, displayName: String): String {
        val base = displayName.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .takeIf { it.isNotEmpty() } ?: "model"
        var slug = base
        var n = 2
        while (File(modelsRoot(context), slug).exists()) {
            slug = "$base-$n"
            n++
        }
        return slug
    }

    private fun writeSidecar(dir: File, info: SherpaModelInfo) {
        val json = JSONObject()
            .put("displayName", info.displayName)
            .put("modelType", info.modelType)
            .put("languages", info.languages?.joinToString(",") ?: "")
            .put("files", JSONObject().apply {
                info.files.forEach { (role, name) -> put(role, name) }
            })
        File(dir, SIDECAR).writeText(json.toString())
    }

    private fun readSidecar(dir: File): SherpaModelInfo? = runCatching {
        val json = JSONObject(File(dir, SIDECAR).readText())
        val files = json.getJSONObject("files")
        val modelType = json.optString("modelType", TYPE_DEFAULT)
        val names = requiredRoles(modelType).associateWith { files.getString(it) }
        val langs = json.optString("languages", "")
            .split(',', ' ')
            .map { it.trim().lowercase() }
            .filter { it.length in 2..3 }
            .distinct()
            .takeIf { it.isNotEmpty() }
        SherpaModelInfo(
            id = dir.name,
            displayName = json.optString("displayName", dir.name),
            modelType = modelType,
            languages = langs,
            files = names,
            totalBytes = names.values.sumOf { File(dir, it).length() }
        )
    }.getOrNull()

    private fun migrateLegacy(app: Context) {
        val root = ModelManager.modelsDir(app)
        val veryOld = File(root, LEGACY_OLD_ID)
        if (veryOld.exists()) {
            val target = dirFor(app, LEGACY_CURATED_ID)
            if (!target.exists()) {
                moveDir(veryOld, target)
            }
            if (ModelManager.storedActiveName(app) == LEGACY_OLD_ID) {
                ModelManager.setActive(app, LEGACY_CURATED_ID)
            }
        }
        val curated = File(root, LEGACY_CURATED_ID)
        if (curated.exists()) {
            val target = dirFor(app, LEGACY_CURATED_ID)
            if (!target.exists() && isComplete(curated, LEGACY_FILES)) {
                moveDir(curated, target)
            } else if (!isComplete(curated, LEGACY_FILES)) {
                curated.deleteRecursively()
            } else if (isComplete(target, LEGACY_FILES)) {
                curated.deleteRecursively()
            }
        }
        val target = dirFor(app, LEGACY_CURATED_ID)
        if (target.exists() && !File(target, SIDECAR).exists() && isComplete(target, LEGACY_FILES)) {
            val info = SherpaModelInfo(
                id = LEGACY_CURATED_ID,
                displayName = LEGACY_DISPLAY_NAME,
                modelType = TYPE_NEMO,
                languages = listOf("ru"),
                files = LEGACY_FILES,
                totalBytes = LEGACY_FILES.values.sumOf { File(target, it).length() }
            )
            writeSidecar(target, info)
        }
    }

    private fun moveDir(from: File, to: File) {
        if (to.exists() && to.listFiles()?.isEmpty() != false) {
            to.deleteRecursively()
        }
        if (!from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
    }
}
