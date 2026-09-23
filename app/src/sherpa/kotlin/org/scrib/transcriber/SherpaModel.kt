package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONObject

// A user-added sherpa transducer model: four files plus a sidecar describing them. Nothing is
// hardcoded — the user brings encoder, decoder, joiner and tokens, e.g. downloaded from
// Hugging Face, and points the app at them through the file picker.
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
    const val ROLE_TOKENS = "tokens"

    const val TYPE_DEFAULT = ""
    const val TYPE_NEMO = "nemo_transducer"

    private const val SIDECAR = "model.json"

    private const val MIN_ONNX_BYTES = 1_000_000L
    private const val MIN_TOKENS_BYTES = 100L

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

    fun isComplete(dir: File, files: Map<String, String>): Boolean {
        val enc = File(dir, files[ROLE_ENCODER] ?: return false)
        val dec = File(dir, files[ROLE_DECODER] ?: return false)
        val join = File(dir, files[ROLE_JOINER] ?: return false)
        val tok = File(dir, files[ROLE_TOKENS] ?: return false)
        return enc.length() > MIN_ONNX_BYTES &&
            dec.length() > MIN_ONNX_BYTES &&
            join.length() > MIN_ONNX_BYTES &&
            tok.length() > MIN_TOKENS_BYTES
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
        val encoder = parts[ROLE_ENCODER] ?: throw RuntimeException("Choose all four files")
        val decoder = parts[ROLE_DECODER] ?: throw RuntimeException("Choose all four files")
        val joiner = parts[ROLE_JOINER] ?: throw RuntimeException("Choose all four files")
        val tokens = parts[ROLE_TOKENS] ?: throw RuntimeException("Choose all four files")
        val id = uniqueSlug(app, name)
        val dir = dirFor(app, id).also { it.mkdirs() }
        try {
            val names = mapOf(
                ROLE_ENCODER to copyPart(app, encoder, dir, ".onnx"),
                ROLE_DECODER to copyPart(app, decoder, dir, ".onnx"),
                ROLE_JOINER to copyPart(app, joiner, dir, ".onnx"),
                ROLE_TOKENS to copyPart(app, tokens, dir, ".txt")
            )
            val info = SherpaModelInfo(
                id = id,
                displayName = name,
                modelType = modelType,
                languages = languages?.takeIf { it.isNotEmpty() },
                files = names,
                totalBytes = names.values.sumOf { File(dir, it).length() }
            )
            writeSidecar(dir, info)
            if (!isComplete(dir, names)) {
                dir.deleteRecursively()
                throw RuntimeException("Files look too small to be a model")
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
        val names = mapOf(
            ROLE_ENCODER to files.getString(ROLE_ENCODER),
            ROLE_DECODER to files.getString(ROLE_DECODER),
            ROLE_JOINER to files.getString(ROLE_JOINER),
            ROLE_TOKENS to files.getString(ROLE_TOKENS)
        )
        val langs = json.optString("languages", "")
            .split(',', ' ')
            .map { it.trim().lowercase() }
            .filter { it.length in 2..3 }
            .distinct()
            .takeIf { it.isNotEmpty() }
        SherpaModelInfo(
            id = dir.name,
            displayName = json.optString("displayName", dir.name),
            modelType = json.optString("modelType", TYPE_DEFAULT),
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
