package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {

    private const val MIN_VALID_SIZE = 1_000_000L
    private const val PREFS = "models"
    private const val KEY_ACTIVE = "activeModelFile"
    private const val KEY_SKIP_SILENCE = "skipSilence"
    private const val LEGACY_MODEL = "ggml-tiny-q5_1.bin"

    // The VAD model is a fraction of the size of even the smallest whisper one, so it cannot be
    // held to the same floor.
    private const val MIN_VALID_VAD_SIZE = 500_000L

    fun modelsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        migrateLegacy(context, dir)
        return dir
    }

    private fun migrateLegacy(context: Context, dir: File) {
        val legacy = File(context.getExternalFilesDir(null), LEGACY_MODEL)
        val moved = File(dir, LEGACY_MODEL)
        if (valid(legacy) && !moved.exists()) {
            if (!legacy.renameTo(moved)) {
                legacy.copyTo(moved, overwrite = true)
                legacy.delete()
            }
        }
    }

    private fun valid(file: File, minSize: Long = MIN_VALID_SIZE): Boolean =
        file.exists() && file.length() > minSize

    fun fileFor(context: Context, fileName: String): File = File(modelsDir(context), fileName)

    fun isInstalled(context: Context, model: WhisperModel): Boolean = valid(fileFor(context, model.fileName))

    // The VAD model sits in the same directory but transcribes nothing, so it is kept out of every
    // list: left in, it would be offered as a model to switch to and, on a fresh install, would be
    // picked as the active one.
    fun installedFileNames(context: Context): List<String> =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".bin") && it.name != ModelCatalog.VAD_FILE && valid(it) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun installedCustomFileNames(context: Context): List<String> =
            return stored
        }
        val installed = installedFileNames(context)
        return installed.firstOrNull { it == LEGACY_MODEL } ?: installed.firstOrNull()
    }

    fun activeModelFile(context: Context): File? {
        val name = activeFileName(context) ?: return null
        return fileFor(context, name)
    }

    fun activeDisplayName(context: Context): String? {
    internal fun storedActiveName(context: Context): String? =
    internal fun clearActiveSelection(context: Context, fileName: String) {

    fun isAvailable(context: Context): Boolean = hasActiveModel(context)

    fun setActive(context: Context, fileName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE, fileName).apply()
    }

    fun delete(context: Context, fileName: String) {
        if (SherpaPlugins.plugin?.deleteModel(context, fileName) == true) {
            return
        }
        fileFor(context, fileName).delete()
        clearActiveSelection(context, fileName)
    }

    @Synchronized
    fun download(
        context: Context,
        model: WhisperModel,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val dest = fileFor(context, model.fileName)
        if (valid(dest)) {
            return dest
        }
        downloadUrl(model.url, dest, onProgress, isCancelled)
        if (activeFileName(context) == null) {
            setActive(context, model.fileName)
        }
        return dest
    }

    fun isVadInstalled(context: Context): Boolean =
        valid(fileFor(context, ModelCatalog.VAD_FILE), MIN_VALID_VAD_SIZE)

    fun skipSilence(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SKIP_SILENCE, false)

    fun setSkipSilence(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SKIP_SILENCE, enabled).apply()
    }

    // The path to hand whisper, or null when silence is to be decoded along with everything else.
    // The model can go missing after the setting was turned on — a cleared app storage, a restore
    // onto another phone — so the file is checked, not just the preference.
    fun vadModelPath(context: Context): String? {
        if (!skipSilence(context) || !isVadInstalled(context)) {
            return null
        }
        return fileFor(context, ModelCatalog.VAD_FILE).absolutePath
    }

    @Synchronized
    fun downloadVad(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val dest = fileFor(context, ModelCatalog.VAD_FILE)
        if (valid(dest, MIN_VALID_VAD_SIZE)) {
            return dest
        }
        downloadUrl(ModelCatalog.VAD_URL, dest, onProgress, isCancelled, MIN_VALID_VAD_SIZE)
        isCancelled: () -> Boolean,
        minSize: Long = MIN_VALID_SIZE
    ) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.absolutePath + ".part")
        temp.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) {
                            throw CancelledDownloadException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        if (temp.length() <= minSize) {
            temp.delete()
            throw RuntimeException("Downloaded file is too small")
        }
        if (dest.exists()) {
            dest.delete()
        }
        if (!temp.renameTo(dest)) {
            temp.copyTo(dest, overwrite = true)
            temp.delete()
        }
    }

    fun customModelFromUrl(url: String): WhisperModel {
        val trimmed = url.trim().replace("/blob/", "/resolve/")
        require(trimmed.startsWith("https://")) { "Enter a full https:// link to a .bin model" }
        val fileName = trimmed.substringBefore('?').substringAfterLast('/')
        require(fileName.endsWith(".bin")) { "Link must point to a .bin ggml model" }
        return ModelCatalog.customModel(fileName, trimmed)
    }

    fun importFromUri(context: Context, uri: Uri, suggestedName: String?): String {
        val name = (suggestedName ?: "imported-model.bin").let {
            if (it.endsWith(".bin")) it else "$it.bin"
        }
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
        if (activeFileName(context) == null) {
            setActive(context, name)
        }
        return name
    }

    class CancelledDownloadException : RuntimeException()
}
