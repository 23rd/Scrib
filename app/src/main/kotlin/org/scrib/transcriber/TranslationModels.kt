package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import java.io.File

// translation models live in there own folder, seperate from speech ones
object TranslationModels {

    private const val PREFS = "translation"
    private const val KEY_ACTIVE = "activeModelFile"
    private const val KEY_SOURCE = "sourceLanguage"
    private const val KEY_TARGET = "targetLanguage"

    // anything smaller is a broken download
    private const val MIN_VALID_SIZE = 10_000_000L

    fun dir(context: Context): File {
        val dir = File(ModelManager.modelsDir(context), "translate")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun valid(file: File): Boolean = file.exists() && file.length() > MIN_VALID_SIZE

    fun fileFor(context: Context, fileName: String): File = File(dir(context), fileName)

    fun isInstalled(context: Context, model: TranslationModel): Boolean = valid(fileFor(context, model.fileName))

    fun installedFileNames(context: Context): List<String> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf") && valid(it) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun installedCustomFileNames(context: Context): List<String> =
        installedFileNames(context).filter { TranslationCatalog.byFileName(it) == null }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeFileName(context: Context): String? {
        val stored = prefs(context).getString(KEY_ACTIVE, null)
        if (stored != null && valid(fileFor(context, stored))) {
            return stored
        }
        return installedFileNames(context).firstOrNull()
    }

    fun activeModelPath(context: Context): String? {
        val name = activeFileName(context) ?: return null
        return fileFor(context, name).absolutePath
    }

    fun activeDisplayName(context: Context): String? {
        val name = activeFileName(context) ?: return null
        return TranslationCatalog.byFileName(name)?.displayName ?: name.removeSuffix(".gguf")
    }

    fun setActive(context: Context, fileName: String) {
        prefs(context).edit().putString(KEY_ACTIVE, fileName).apply()
    }

    fun delete(context: Context, fileName: String) {
        fileFor(context, fileName).delete()
        val p = prefs(context)
        if (p.getString(KEY_ACTIVE, null) == fileName) {
            p.edit().remove(KEY_ACTIVE).apply()
        }
    }

    @Synchronized
    fun download(
        context: Context,
        model: TranslationModel,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val dest = fileFor(context, model.fileName)
        if (valid(dest)) {
            return dest
        }
        ModelManager.downloadUrl(model.url, dest, onProgress, isCancelled, MIN_VALID_SIZE)
        if (prefs(context).getString(KEY_ACTIVE, null) == null) {
            setActive(context, model.fileName)
        }
        return dest
    }

    fun customModelFromUrl(url: String): TranslationModel {
        val trimmed = url.trim().replace("/blob/", "/resolve/")
        require(trimmed.startsWith("https://")) { "Enter a full https:// link to a model file" }
        val fileName = trimmed.substringBefore('?').substringAfterLast('/')
        require(fileName.endsWith(".gguf")) { "Link must point to a .gguf model" }
        return TranslationCatalog.customModel(fileName, trimmed)
    }

    fun importFromUri(context: Context, uri: Uri, suggestedName: String?): String {
        val name = (suggestedName ?: "imported-model.gguf").let { if (it.endsWith(".gguf")) it else "$it.gguf" }
        val dest = fileFor(context, name)
        val temp = File(dest.absolutePath + ".part")
        temp.delete()
        val input = context.contentResolver.openInputStream(uri) ?: throw RuntimeException("Cannot open file")
        input.use { source ->
            temp.outputStream().use { output -> source.copyTo(output) }
        }
        if (temp.length() <= MIN_VALID_SIZE) {
            temp.delete()
            throw RuntimeException("File is too small to be a model")
        }
        if (dest.exists()) {
            dest.delete()
        }
        if (!temp.renameTo(dest)) {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }
        if (prefs(context).getString(KEY_ACTIVE, null) == null) {
            setActive(context, name)
        }
        return name
    }

    // empty means auto detect, defualts to the transcription language
    fun sourceLanguage(context: Context): String =
        prefs(context).getString(KEY_SOURCE, null) ?: ModelManager.language(context).orEmpty()

    fun setSourceLanguage(context: Context, code: String) {
        prefs(context).edit().putString(KEY_SOURCE, code).apply()
    }

    fun targetLanguage(context: Context): String =
        prefs(context).getString(KEY_TARGET, null) ?: "en"

    fun setTargetLanguage(context: Context, code: String) {
        prefs(context).edit().putString(KEY_TARGET, code).apply()
    }
}
