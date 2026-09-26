package org.scrib.transcriber

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
    const val ROLE_MERGED_DECODER = "mergedDecoder"
    const val ROLE_MODEL = "model"
    const val ROLE_TOKENS = "tokens"
    const val AUXILIARY_PREFIX = "aux:"

    const val TYPE_DEFAULT = ""
    const val TYPE_NEMO = "nemo_transducer"
    const val TYPE_STREAMING_TRANSDUCER = "streaming_transducer"
    const val TYPE_STREAMING_ZIPFORMER2 = "streaming_zipformer2"
    const val TYPE_SENSE_VOICE = "sense_voice"
    const val TYPE_MOONSHINE = "moonshine"
    const val TYPE_CANARY = "canary"
    const val TYPE_DOLPHIN = "dolphin"
    const val TYPE_OMNILINGUAL = "omnilingual"
    const val TYPE_NEMO_CTC = "nemo_ctc"
    const val TYPE_ZIPFORMER_CTC = "zipformer_ctc"
    const val TYPE_FIRE_RED_AED = "fire_red_aed"
    const val TYPE_FIRE_RED_CTC = "fire_red_ctc"

    val supportedTypes = listOf(
        TYPE_DEFAULT,
        TYPE_NEMO,
        TYPE_STREAMING_TRANSDUCER,
        TYPE_STREAMING_ZIPFORMER2,
        TYPE_SENSE_VOICE,
        TYPE_MOONSHINE,
        TYPE_CANARY,
        TYPE_DOLPHIN,
        TYPE_OMNILINGUAL,
        TYPE_NEMO_CTC,
        TYPE_ZIPFORMER_CTC,
        TYPE_FIRE_RED_AED,
        TYPE_FIRE_RED_CTC
    )

    sealed interface Detection {
        data class Detected(val modelType: String) : Detection
        data class Ambiguous(val candidates: List<String>) : Detection
        data object Unknown : Detection
    }

    private const val SIDECAR = "model.json"

    private const val MIN_TOKENS_BYTES = 100L
    private const val ONNX_METADATA_SCAN_BYTES = 2L * 1024 * 1024
    private const val SAMPLE_RATE = 16_000

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
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { readSidecar(it) }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
    }

    fun byId(context: Context, id: String): SherpaModelInfo? =
        list(context).firstOrNull { it.id == id }

    fun requiredRoles(modelType: String): List<String> = when (modelType) {
        TYPE_DEFAULT, TYPE_NEMO, TYPE_STREAMING_TRANSDUCER, TYPE_STREAMING_ZIPFORMER2 ->
            listOf(ROLE_ENCODER, ROLE_DECODER, ROLE_JOINER, ROLE_TOKENS)
        TYPE_SENSE_VOICE, TYPE_DOLPHIN, TYPE_OMNILINGUAL, TYPE_NEMO_CTC,
        TYPE_ZIPFORMER_CTC, TYPE_FIRE_RED_CTC -> listOf(ROLE_MODEL, ROLE_TOKENS)
        TYPE_MOONSHINE -> listOf(ROLE_ENCODER, ROLE_MERGED_DECODER, ROLE_TOKENS)
        TYPE_CANARY, TYPE_FIRE_RED_AED -> listOf(ROLE_ENCODER, ROLE_DECODER, ROLE_TOKENS)
        else -> throw IllegalArgumentException("Unsupported sherpa model type: $modelType")
    }

    fun isSupportedType(modelType: String): Boolean = modelType in supportedTypes

    fun isStreaming(modelType: String): Boolean =
        modelType == TYPE_STREAMING_TRANSDUCER || modelType == TYPE_STREAMING_ZIPFORMER2

    fun auxiliaryRole(fileName: String): String = "$AUXILIARY_PREFIX$fileName"

    fun isAuxiliaryRole(role: String): Boolean = role.startsWith(AUXILIARY_PREFIX)

    fun isAuxiliaryFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".data") || lower.endsWith(".weights") || lower.endsWith(".onnx_data")
    }

    fun planFiles(modelType: String, fileNames: List<String>): Map<String, String>? {
        val tokens = pickTokensFile(fileNames) ?: return null
        return when (modelType) {
            TYPE_DEFAULT, TYPE_NEMO, TYPE_STREAMING_TRANSDUCER, TYPE_STREAMING_ZIPFORMER2 -> {
                val encoder = pickFile(fileNames, "encoder") ?: return null
                val decoder = pickFile(fileNames, "decoder", exclude = setOf("merged")) ?: return null
                val joiner = pickFile(fileNames, "joiner", "joint") ?: return null
                linkedMapOf(
                    ROLE_ENCODER to encoder,
                    ROLE_DECODER to decoder,
                    ROLE_JOINER to joiner,
                    ROLE_TOKENS to tokens
                )
            }
            TYPE_SENSE_VOICE, TYPE_DOLPHIN, TYPE_OMNILINGUAL, TYPE_NEMO_CTC,
            TYPE_ZIPFORMER_CTC, TYPE_FIRE_RED_CTC -> {
                val model = pickModelFile(fileNames) ?: return null
                linkedMapOf(ROLE_MODEL to model, ROLE_TOKENS to tokens)
            }
            TYPE_MOONSHINE -> {
                val encoder = pickFile(fileNames, "encoder") ?: return null
                val decoder = pickFile(fileNames, "merged", "decoder_model_merged") ?: return null
                linkedMapOf(
                    ROLE_ENCODER to encoder,
                    ROLE_MERGED_DECODER to decoder,
                    ROLE_TOKENS to tokens
                )
            }
            TYPE_CANARY, TYPE_FIRE_RED_AED -> {
                val encoder = pickFile(fileNames, "encoder") ?: return null
                val decoder = pickFile(fileNames, "decoder", exclude = setOf("merged")) ?: return null
                linkedMapOf(
                    ROLE_ENCODER to encoder,
                    ROLE_DECODER to decoder,
                    ROLE_TOKENS to tokens
                )
            }
            else -> null
        }
    }

    fun detect(fileNames: List<String>, hint: String? = null): Detection {
        val relevantCount = fileNames.count { isModelFile(it) || isTokenFile(it) }
        if (relevantCount == 0) return Detection.Unknown
        val matching = supportedTypes.mapNotNull { type ->
            planFiles(type, fileNames)?.let { type to it.size }
        }
        val exact = matching.filter { it.second == relevantCount }.map { it.first }
        val candidates = exact.ifEmpty { matching.map { it.first } }
        if (candidates.isEmpty()) return Detection.Unknown
        if (candidates.size == 1) return Detection.Detected(candidates.single())
        val narrowed = narrowTypes(candidates, hint)
        return if (narrowed != null) Detection.Detected(narrowed) else Detection.Ambiguous(candidates)
    }

    fun effectiveModelType(
        modelType: String,
        displayName: String,
        partNames: List<String>
    ): String {
        if (modelType != TYPE_DEFAULT) return modelType
        return when (val detection = detect(partNames, displayName)) {
            is Detection.Detected -> detection.modelType
            else -> modelType
        }
    }

    fun isComplete(dir: File, files: Map<String, String>, modelType: String = TYPE_DEFAULT): Boolean {
        val requiredComplete = requiredRoles(modelType).all { role ->
            val file = files[role]?.let { File(dir, it) } ?: return@all false
            file.isFile && if (role == ROLE_TOKENS) {
                file.length() > MIN_TOKENS_BYTES
            } else {
                file.length() > 0L
            }
        }
        val auxiliaryComplete = files.filterKeys { isAuxiliaryRole(it) }.all { (_, name) ->
            File(dir, name).isFile && File(dir, name).length() > 0L
        }
        return requiredComplete && auxiliaryComplete
    }

    fun isComplete(dir: File, info: SherpaModelInfo): Boolean =
        isComplete(dir, info.files, info.modelType)

    fun isUsable(dir: File, info: SherpaModelInfo): Boolean =
        isComplete(dir, info) && runCatching { validateMetadata(dir, info) }.isSuccess

    fun validateMetadata(dir: File, info: SherpaModelInfo) {
        val (role, requiredKeys, expectedValue) = when (info.modelType) {
            TYPE_NEMO -> Triple(
                ROLE_ENCODER,
                listOf("vocab_size", "subsampling_factor", "pred_rnn_layers", "pred_hidden"),
                null
            )
            TYPE_SENSE_VOICE -> Triple(
                ROLE_MODEL,
                listOf(
                    "comment", "vocab_size", "lfr_window_size", "lfr_window_shift", "normalize_samples",
                    "with_itn", "without_itn", "lang_auto", "lang_zh", "lang_en", "lang_ja",
                    "lang_ko", "lang_yue", "neg_mean", "inv_stddev"
                ),
                null
            )
            TYPE_DOLPHIN -> Triple(ROLE_MODEL, listOf("vocab_size", "mean", "invstd"), null)
            TYPE_NEMO_CTC -> Triple(
                ROLE_MODEL,
                listOf("vocab_size", "subsampling_factor", "normalize_type"),
                null
            )
            TYPE_CANARY -> Triple(
                ROLE_ENCODER,
                listOf("model_type", "vocab_size", "normalize_type", "subsampling_factor", "feat_dim"),
                "EncDecMultiTaskModel"
            )
            TYPE_FIRE_RED_AED -> Triple(
                ROLE_ENCODER,
                listOf(
                    "num_decoder_layers", "num_head", "head_dim", "sos", "eos", "max_len",
                    "cmvn_mean", "cmvn_inv_stddev"
                ),
                null
            )
            TYPE_FIRE_RED_CTC -> Triple(
                ROLE_MODEL,
                listOf("model_type", "cmvn_mean", "cmvn_inv_stddev"),
                "fire-red-asr-2-ctc"
            )
            else -> return
        }
        val file = info.files[role]?.let { File(dir, it) }
            ?: throw IllegalArgumentException("Model file is missing")
        val data = readOnnxTail(file)
            ?: throw IllegalArgumentException("Cannot read ONNX metadata from ${file.name}")
        val missing = requiredKeys.filter { key ->
            indexOfBytes(data, key.toByteArray(Charsets.UTF_8)) < 0
        }
        require(missing.isEmpty()) {
            "The selected model type does not match ${file.name}: missing ONNX metadata (${missing.joinToString()})"
        }
        if (expectedValue != null) {
            val actual = onnxMetadataValue(data, "model_type")
            require(actual == expectedValue) {
                "The selected model type does not match ${file.name}: model_type is ${actual ?: "missing"}"
            }
        }
    }

    fun buildConfig(
        dir: File,
        info: SherpaModelInfo,
        provider: String = "cpu",
        languageHint: String? = null
    ): OfflineRecognizerConfig {
        val tokens = File(dir, info.files.getValue(ROLE_TOKENS)).absolutePath
        val modelConfig = when (info.modelType) {
            TYPE_SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath,
                    language = senseVoiceLanguage(info, languageHint),
                    useInverseTextNormalization = true
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = File(dir, info.files.getValue(ROLE_ENCODER)).absolutePath,
                    mergedDecoder = File(dir, info.files.getValue(ROLE_MERGED_DECODER)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_CANARY -> OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = File(dir, info.files.getValue(ROLE_ENCODER)).absolutePath,
                    decoder = File(dir, info.files.getValue(ROLE_DECODER)).absolutePath,
                    srcLang = canaryLanguage(info, languageHint),
                    tgtLang = canaryLanguage(info, languageHint),
                    usePnc = true
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_DOLPHIN -> OfflineModelConfig(
                dolphin = OfflineDolphinModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_OMNILINGUAL -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_ZIPFORMER_CTC -> OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_FIRE_RED_AED -> OfflineModelConfig(
                fireRedAsr = OfflineFireRedAsrModelConfig(
                    encoder = File(dir, info.files.getValue(ROLE_ENCODER)).absolutePath,
                    decoder = File(dir, info.files.getValue(ROLE_DECODER)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            TYPE_FIRE_RED_CTC -> OfflineModelConfig(
                fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(
                    model = File(dir, info.files.getValue(ROLE_MODEL)).absolutePath
                ),
                tokens = tokens,
                numThreads = 4,
                provider = provider
            )
            else -> OfflineModelConfig(
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
            featConfig = FeatureConfig(
                sampleRate = 16_000,
                featureDim = if (info.modelType == TYPE_CANARY) 128 else 80
            ),
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
            modelType = if (info.modelType == TYPE_STREAMING_ZIPFORMER2) "zipformer2" else ""
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

    fun canaryLanguage(info: SherpaModelInfo, languageHint: String?): String {
        val hint = languageHint?.trim()?.lowercase()
        if (hint != null && hint in setOf("en", "es", "de", "fr")) {
            return hint
        }
        return info.languages?.singleOrNull()?.lowercase()?.takeIf { it in setOf("en", "es", "de", "fr") }
            ?: "en"
    }

    fun runNativeProbe(dir: File, info: SherpaModelInfo): String? {
        return try {
            if (isStreaming(info.modelType)) {
                val recognizer = OnlineRecognizer(null, buildOnlineConfig(dir, info))
                val stream = try {
                    recognizer.createStream()
                } catch (e: Throwable) {
                    recognizer.release()
                    throw e
                }
                try {
                    stream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    recognizer.getResult(stream)
                } finally {
                    stream.release()
                    recognizer.release()
                }
            } else {
                val recognizer = OfflineRecognizer(null, buildConfig(dir, info))
                val stream = try {
                    recognizer.createStream()
                } catch (e: Throwable) {
                    recognizer.release()
                    throw e
                }
                try {
                    stream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream)
                } finally {
                    stream.release()
                    recognizer.release()
                }
            }
            null
        } catch (e: Throwable) {
            e.message ?: "Sherpa could not load this model"
        }
    }

    suspend fun importModel(
        context: Context,
        displayName: String,
        modelType: String,
        languages: List<String>?,
        parts: Map<String, Uri>
    ): SherpaModelInfo {
        val app = context.applicationContext
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Enter a name" }
        require(isSupportedType(modelType)) { "Choose a supported model type" }
        val roles = requiredRoles(modelType)
        val missing = roles.filterNot { parts.containsKey(it) }
        require(missing.isEmpty()) { "Choose all model files" }
        val selectedRoles = roles + parts.keys.filter { isAuxiliaryRole(it) }
        val sourceUris = selectedRoles.map { parts.getValue(it) }
        require(sourceUris.toSet().size == sourceUris.size) { "Choose different model files" }
        val sourceNames = selectedRoles.associateWith { role ->
            (queryName(app, parts.getValue(role)) ?: "part-$role")
                .substringAfterLast('/')
                .substringAfterLast('\\')
        }
        require(sourceNames.values.toSet().size == sourceNames.size) { "Selected model files must have different names" }
        val id = uniqueSlug(app, name)
        val target = dirFor(app, id)
        val dir = File(target.parentFile, ".$id.import").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        try {
            val names = selectedRoles.associateWith { role ->
                val extension = if (role == ROLE_TOKENS) ".txt" else ".onnx"
                copyPart(app, parts.getValue(role), dir, extension, role)
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
            validateMetadata(dir, info)
            validateExternalData(dir, info)
            validateInProbeProcess(app, dir, info)
            writeSidecar(dir, info)
            if (!dir.renameTo(target)) {
                throw RuntimeException("Cannot finalize model import")
            }
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

    private fun copyPart(context: Context, uri: Uri, dir: File, extension: String, role: String): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw RuntimeException("Cannot open file")
        val fileName = (queryName(context, uri) ?: "part-$role$extension")
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val safe = when {
            isAuxiliaryRole(role) -> fileName
            fileName.endsWith(extension, ignoreCase = true) || fileName.endsWith(".ort", ignoreCase = true) -> fileName
            else -> fileName + extension
        }
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
        val displayName = json.optString("displayName", dir.name)
        val storedType = json.optString("modelType", TYPE_DEFAULT)
        if (!isSupportedType(storedType)) return@runCatching null
        val fileNames = buildList {
            for (role in files.keys()) {
                add(files.getString(role))
            }
        }
        val modelType = when (val detection = detect(fileNames, displayName)) {
            is Detection.Detected -> if (storedType == TYPE_DEFAULT) detection.modelType else storedType
            else -> if (storedType == TYPE_DEFAULT) legacyModelType(files) else storedType
        }
        val names = buildMap {
            requiredRoles(modelType).forEach { role -> put(role, files.getString(role)) }
            files.keys().forEach { role ->
                if (isAuxiliaryRole(role)) put(role, files.getString(role))
            }
        }
        val langs = json.optString("languages", "")
            .split(',', ' ')
            .map { it.trim().lowercase() }
            .filter { it.length in 2..3 }
            .distinct()
            .takeIf { it.isNotEmpty() }
        SherpaModelInfo(
            id = dir.name,
            displayName = displayName,
            modelType = modelType,
            languages = langs,
            files = names,
            totalBytes = names.values.sumOf { File(dir, it).length() }
        )
    }.getOrNull()

    private fun legacyModelType(files: JSONObject): String = when {
        files.has(ROLE_MODEL) -> TYPE_SENSE_VOICE
        files.has(ROLE_MERGED_DECODER) -> TYPE_MOONSHINE
        files.has(ROLE_ENCODER) && files.has(ROLE_DECODER) && !files.has(ROLE_JOINER) -> TYPE_CANARY
        else -> TYPE_DEFAULT
    }

    private fun pickModelFile(fileNames: List<String>): String? {
        val models = fileNames.filter(::isModelFile).filter { name ->
            val tokens = nameTokens(name)
            tokens.none { it in setOf("encoder", "decoder", "joiner", "joint", "merged") }
        }
        return models.firstOrNull { "model" in nameTokens(it) } ?: models.firstOrNull()
    }

    private fun pickFile(
        fileNames: List<String>,
        vararg markers: String,
        exclude: Set<String> = emptySet()
    ): String? = fileNames
        .filter(::isModelFile)
        .firstOrNull { name ->
            val tokens = nameTokens(name)
            markers.any { it in tokens } && tokens.none { it in exclude }
        }

    private fun pickTokensFile(fileNames: List<String>): String? {
        val candidates = fileNames.filter { name ->
            isTokenFile(name) && nameTokens(name).any { it == "tokens" || it == "vocab" }
        }
        return candidates.firstOrNull { "tokens" in nameTokens(it) } ?: candidates.firstOrNull()
    }

    private fun isModelFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return (lower.endsWith(".onnx") || lower.endsWith(".ort")) && !isAuxiliaryFile(lower)
    }

    private fun isTokenFile(fileName: String): Boolean =
        fileName.lowercase().endsWith(".txt") && nameTokens(fileName).any {
            it == "tokens" || it == "vocab"
        }

    private fun nameTokens(fileName: String): Set<String> =
        fileName.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    private fun narrowTypes(candidates: List<String>, hint: String?): String? {
        if (candidates.size == 1) return candidates.single()
        val terminal = hint.orEmpty().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val tokens = terminal.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        val matched = candidates.filter { matchesTypeHint(it, tokens) }
        if (matched.size == 1) return matched.single()
        return null
    }

    private fun matchesTypeHint(modelType: String, tokens: Set<String>): Boolean = when (modelType) {
        TYPE_NEMO -> ("nemo" in tokens || "gigaam" in tokens || "parakeet" in tokens) && "ctc" !in tokens
        TYPE_STREAMING_TRANSDUCER -> ("streaming" in tokens && "zipformer2" !in tokens) ||
            ("chunk" in tokens && "16" in tokens && "left" in tokens)
        TYPE_STREAMING_ZIPFORMER2 -> "zipformer2" in tokens
        TYPE_SENSE_VOICE -> "sensevoice" in tokens || ("sense" in tokens && "voice" in tokens)
        TYPE_MOONSHINE -> "moonshine" in tokens || ("encoder" in tokens && "merged" in tokens)
        TYPE_CANARY -> "canary" in tokens
        TYPE_DOLPHIN -> "dolphin" in tokens
        TYPE_OMNILINGUAL -> "omnilingual" in tokens
        TYPE_NEMO_CTC -> "nemo" in tokens || "gigaam" in tokens || "parakeet" in tokens
        TYPE_ZIPFORMER_CTC -> "zipformer" in tokens && "ctc" in tokens
        TYPE_FIRE_RED_AED, TYPE_FIRE_RED_CTC ->
            "firered" in tokens || ("fire" in tokens && "red" in tokens)
        else -> false
    }

    private suspend fun validateInProbeProcess(context: Context, dir: File, info: SherpaModelInfo) {
        withTimeout(600_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val completed = AtomicBoolean(false)
                var bound = false
                lateinit var connection: ServiceConnection
                lateinit var clientMessenger: Messenger
                val remoteBinder = AtomicReference<IBinder?>(null)
                val pollHandler = Handler(Looper.getMainLooper())

                fun unbind() {
                    if (bound) {
                        bound = false
                        runCatching { context.unbindService(connection) }
                    }
                }

                fun succeed() {
                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                        unbind()
                        continuation.resume(Unit)
                    }
                }

                fun fail(message: String) {
                    if (completed.compareAndSet(false, true) && continuation.isActive) {
                        unbind()
                        continuation.resumeWithException(IllegalArgumentException(message))
                    }
                }

                val pollTask = object : Runnable {
                    override fun run() {
                        if (!continuation.isActive) return
                        if (remoteBinder.get()?.pingBinder() != true) {
                            fail("Sherpa validation process stopped while loading the model")
                        } else {
                            pollHandler.postDelayed(this, 250L)
                        }
                    }
                }

                clientMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(message: Message) {
                        when (message.what) {
                            2 -> succeed()
                            3 -> fail(message.obj?.toString() ?: "Sherpa validation failed")
                        }
                    }
                })

                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (service == null) {
                            fail("Sherpa validation service returned no binder")
                            return
                        }
                        remoteBinder.set(service)
                        pollHandler.post(pollTask)
                        val request = Message.obtain().apply {
                            what = 1
                            replyTo = clientMessenger
                            data = probeBundle(dir.absolutePath, info)
                        }
                        try {
                            Messenger(service).send(request)
                        } catch (e: Exception) {
                            fail(e.message ?: "Sherpa validation service stopped")
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        remoteBinder.set(null)
                        fail("Sherpa validation process stopped while loading the model")
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        remoteBinder.set(null)
                        fail("Sherpa validation process rejected the model")
                    }
                }
                try {
                    bound = context.bindService(
                        Intent(context, SherpaValidationService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE
                    )
                } catch (e: Exception) {
                    fail(e.message ?: "Cannot start Sherpa validation")
                    return@suspendCancellableCoroutine
                }
                if (!bound) {
                    fail("Cannot start Sherpa validation")
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    remoteBinder.set(null)
                    unbind()
                }
            }
        }
    }

    private fun probeBundle(dirPath: String, info: SherpaModelInfo): Bundle = Bundle().apply {
        putString("dir", dirPath)
        putString("id", info.id)
        putString("displayName", info.displayName)
        putString("modelType", info.modelType)
        putStringArray("languages", info.languages?.toTypedArray())
        putStringArray("roles", info.files.keys.toTypedArray())
        putStringArray("fileNames", info.files.values.toTypedArray())
    }

    private fun validateExternalData(dir: File, info: SherpaModelInfo) {
        val references = requiredRoles(info.modelType)
            .filter { it != ROLE_TOKENS }
            .mapNotNull { info.files[it] }
            .filter { it.endsWith(".onnx", ignoreCase = true) || it.endsWith(".ort", ignoreCase = true) }
            .flatMap { externalDataReferences(File(dir, it)) }
            .toSet()
        if (references.isEmpty()) return
        val copied = info.files.values.toSet()
        val missing = references.filter { it !in copied }
        require(missing.isEmpty()) {
            "Model references external data files that were not selected: ${missing.joinToString()}. Import it from a folder."
        }
    }

    private fun externalDataReferences(file: File): Set<String> {
        if (!file.isFile) return emptySet()
        val length = file.length()
        if (length == 0L) return emptySet()
        val head = readOnnxHead(file, ONNX_METADATA_SCAN_BYTES)
        val tail = readOnnxTail(file)
        val chunks = listOfNotNull(head, tail).distinct()
        if (chunks.isEmpty()) return emptySet()
        val references = mutableSetOf<String>()
        val pattern = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-/]{0,511}\\.(?:data|weights|onnx_data)", RegexOption.IGNORE_CASE)
        for (data in chunks) {
            val text = String(data, Charsets.US_ASCII)
            pattern.findAll(text).forEach { references += it.value }
        }
        return references.filter { reference ->
            reference.endsWith(".data", ignoreCase = true) ||
                reference.endsWith(".weights", ignoreCase = true) ||
                reference.endsWith(".onnx_data", ignoreCase = true)
        }.toSet()
    }

    private fun readOnnxHead(file: File, maxBytes: Long): ByteArray? {
        if (!file.isFile || file.length() == 0L) return null
        val count = minOf(maxBytes, file.length()).toInt()
        val data = ByteArray(count)
        return runCatching {
            RandomAccessFile(file, "r").use { fileInput ->
                fileInput.seek(0L)
                fileInput.readFully(data)
            }
            data
        }.getOrNull()
    }

    private fun readOnnxTail(file: File): ByteArray? {
        if (!file.isFile || file.length() == 0L) return null
        val length = file.length()
        val start = maxOf(0L, length - ONNX_METADATA_SCAN_BYTES)
        val data = ByteArray((length - start).toInt())
        return runCatching {
            RandomAccessFile(file, "r").use { fileInput ->
                fileInput.seek(start)
                fileInput.readFully(data)
            }
            data
        }.getOrNull()
    }

    private fun indexOfBytes(data: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty() || needle.size > data.size) return -1
        var index = maxOf(0, fromIndex)
        val last = data.size - needle.size
        while (index <= last) {
            var offset = 0
            while (offset < needle.size && data[index + offset] == needle[offset]) offset++
            if (offset == needle.size) return index
            index++
        }
        return -1
    }

    private fun onnxMetadataValue(data: ByteArray, key: String): String? {
        val needle = key.toByteArray(Charsets.UTF_8)
        var index = indexOfBytes(data, needle)
        while (index >= 0) {
            parseLengthPrefixedValue(data, index + needle.size)?.let { return it }
            index = indexOfBytes(data, needle, index + 1)
        }
        return null
    }

    private fun parseLengthPrefixedValue(data: ByteArray, start: Int): String? {
        if (start >= data.size || data[start] != 0x12.toByte()) return null
        var position = start + 1
        var length = 0L
        var shift = 0
        var complete = false
        while (position < data.size) {
            val value = data[position++].toInt() and 0xFF
            length = length or ((value and 0x7F).toLong() shl shift)
            if (value and 0x80 == 0) {
                complete = true
                break
            }
            shift += 7
            if (shift > 56) return null
        }
        if (!complete || length < 1L || length > data.size - position) return null
        return String(data, position, length.toInt(), Charsets.UTF_8)
    }

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

class SherpaValidationService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val reply = message.replyTo
            val result = Message.obtain()
            result.what = 2
            try {
                val data = requireNotNull(message.data)
                val roles = requireNotNull(data.getStringArray("roles"))
                val names = requireNotNull(data.getStringArray("fileNames"))
                val info = SherpaModelInfo(
                    id = data.getString("id").orEmpty(),
                    displayName = data.getString("displayName").orEmpty(),
                    modelType = data.getString("modelType").orEmpty(),
                    languages = data.getStringArray("languages")?.toList(),
                    files = roles.zip(names).toMap(),
                    totalBytes = 0L
                )
                val error = SherpaModel.runNativeProbe(File(data.getString("dir").orEmpty()), info)
                if (error != null) {
                    result.what = 3
                    result.obj = error
                }
            } catch (e: Throwable) {
                result.what = 3
                result.obj = e.message ?: "Sherpa validation failed"
            }
            runCatching { reply?.send(result) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return false
    }
}
