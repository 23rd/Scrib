package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {

    private const val MIN_VALID_SIZE = 1_000_000L
    private const val PREFS = "models"
    private const val KEY_ACTIVE = "activeModelFile"
    private const val KEY_EXTERNAL_PATH = "externalModelPath"
    private const val KEY_EXTERNAL_NAME = "externalModelName"
    private const val KEY_EXTERNAL_SIZE = "externalModelSize"
    private const val KEY_SKIP_SILENCE = "skipSilence"
    private const val KEY_LIVE_STATS = "liveStats"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PINNED = "pinnedModels"
    private const val KEY_KEYBOARD_STATS = "keyboardStats"
    private const val KEY_KB_SESSIONS = "kbSessions"
    private const val KEY_KB_WORDS = "kbWords"
    private const val KEY_KB_TIME_MS = "kbTimeMs"
    private const val LEGACY_MODEL = "ggml-tiny-q5_1.bin"

    const val EXTERNAL_PREFIX = "external:"

    // The VAD model is a fraction of the size of even the smallest speech one, so it cannot be
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
            ?.filter {
                it.isFile && (it.name.endsWith(".bin") || it.name.endsWith(".gguf")) &&
                    it.name != ModelCatalog.VAD_FILE && valid(it)
            }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun installedCustomFileNames(context: Context): List<String> =
        installedFileNames(context).filter { ModelCatalog.byFileName(it) == null }

    fun activeFileName(context: Context): String? {
        if (EndpointPlugins.plugin?.isActive(context) == true) {
            return null
        }
        SherpaPlugins.plugin?.selectedId(context)?.let {
            return it
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ACTIVE, null)
        if (stored != null) {
            if (stored.startsWith(EXTERNAL_PREFIX)) {
                if (externalModel(context) != null) {
                    return stored
                }
            } else if (valid(fileFor(context, stored))) {
                return stored
            }
        }
        val installed = installedFileNames(context)
        return installed.firstOrNull { it == LEGACY_MODEL } ?: installed.firstOrNull()
    }

    fun activeModelFile(context: Context): File? {
        val name = activeFileName(context) ?: return null
        if (SherpaPlugins.plugin?.selectedId(context) != null || name.startsWith(EXTERNAL_PREFIX)) {
            return null
        }
        return fileFor(context, name)
    }

    fun activeDisplayName(context: Context): String? {
        EndpointPlugins.plugin?.takeIf { it.isActive(context) }?.let {
            return it.displayName(context)
        }
        SherpaPlugins.plugin?.displayName(context)?.let {
            return it
        }
        val name = activeFileName(context) ?: return null
        if (name.startsWith(EXTERNAL_PREFIX)) {
            return name.removePrefix(EXTERNAL_PREFIX)
        }
        return ModelCatalog.byFileName(name)?.displayName
            ?: name.removePrefix("ggml-").removeSuffix(".bin").removeSuffix(".gguf")
    }

    class ActiveModel internal constructor(val key: String, val path: String)

    data class SharedModelUse(val name: String, val inPlace: Boolean)

    fun externalModel(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_EXTERNAL_PATH, null) ?: return null
        val name = prefs.getString(KEY_EXTERNAL_NAME, null) ?: return null
        return name to path
    }

    fun externalModelSize(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_EXTERNAL_SIZE, 0L)

    fun useSharedModel(context: Context, uri: Uri, suggestedName: String?): SharedModelUse {
        val path = sharedModelPath(context, uri)
        if (path != null) {
            val file = File(path)
            if (file.isFile && file.canRead() && file.length() > MIN_VALID_SIZE) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_EXTERNAL_PATH, path)
                    .putString(KEY_EXTERNAL_NAME, file.name)
                    .putLong(KEY_EXTERNAL_SIZE, file.length())
                    .apply()
                setActive(context, EXTERNAL_PREFIX + file.name)
                return SharedModelUse(file.name, true)
            }
        }
        return SharedModelUse(importFromUri(context, uri, suggestedName), false)
    }

    private fun sharedModelPath(context: Context, uri: Uri): String? {
        if (uri.authority == "com.android.externalstorage.documents") {
            val parts = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
            if (parts.size == 2 && parts[0] == "primary") {
                return File(Environment.getExternalStorageDirectory(), parts[1]).absolutePath
            }
        }
        return try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex("_data")
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (ignore: Throwable) {
            null
        }
    }

    private fun clearExternalModel(context: Context) {
        val name = externalModel(context)?.first ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_EXTERNAL_PATH).remove(KEY_EXTERNAL_NAME).remove(KEY_EXTERNAL_SIZE).apply()
        clearActiveSelection(context, EXTERNAL_PREFIX + name)
    }

    fun activeModelKey(context: Context): String? {
        if (EndpointPlugins.plugin?.isActive(context) == true) {
            return null
        }
        SherpaPlugins.plugin?.selectedId(context)?.let {
            return it
        }
        val name = activeFileName(context) ?: return null
        return if (name.startsWith(EXTERNAL_PREFIX)) name else fileFor(context, name).absolutePath
    }

    fun openActiveModel(context: Context): ActiveModel? {
        val name = activeFileName(context) ?: return null
        if (SherpaPlugins.plugin?.selectedId(context) != null) {
            return null
        }
        if (name.startsWith(EXTERNAL_PREFIX)) {
            val external = externalModel(context) ?: return null
            val file = File(external.second)
            if (!file.isFile || !file.canRead()) {
                return null
            }
            return ActiveModel(name, external.second)
        }
        val path = fileFor(context, name).absolutePath
        return ActiveModel(path, path)
    }

    internal fun storedActiveName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null)

    internal fun clearActiveSelection(context: Context, fileName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE, null) == fileName) {
            prefs.edit().remove(KEY_ACTIVE).apply()
        }
    }

    internal fun clearStoredActiveSelection(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ACTIVE).apply()
    }

    fun hasActiveModel(context: Context): Boolean {
        val plugin = SherpaPlugins.plugin
        if (plugin?.selectedId(context) != null) {
            return plugin.hasActiveModel(context)
        }
        if (EndpointPlugins.plugin?.isActive(context) == true) {
            return true
        }
        if (activeFileName(context)?.startsWith(EXTERNAL_PREFIX) == true) {
            return true
        }
        return activeModelFile(context) != null
    }

    fun isAvailable(context: Context): Boolean = hasActiveModel(context)

    fun setActive(context: Context, fileName: String) {
        EndpointPlugins.plugin?.let { if (it.isActive(context)) it.setActive(context, false) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE, fileName).apply()
        if (skipSilence(context)) {
            val installed = if (SherpaPlugins.plugin?.selectedId(context) != null) {
                isSherpaVadInstalled(context)
            } else {
                isVadInstalled(context)
            }
            if (!installed) {
                setSkipSilence(context, false)
            }
        }
    }

    fun delete(context: Context, fileName: String) {
        if (SherpaPlugins.plugin?.deleteModel(context, fileName) == true) {
            return
        }
        if (fileName.startsWith(EXTERNAL_PREFIX)) {
            clearExternalModel(context)
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

    fun isSherpaVadInstalled(context: Context): Boolean =
        valid(fileFor(context, ModelCatalog.SHERPA_VAD_FILE), MIN_VALID_VAD_SIZE)

    fun skipSilence(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SKIP_SILENCE, false)

    fun setSkipSilence(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SKIP_SILENCE, enabled).apply()
    }

    // Sampling the process means two binder calls into system_server, so it costs real time on a
    // phone with little memory to spare. On by default — the figures are the whole point of the
    // benchmark history — but switchable, so its weight on a run can be measured by turning it off.
    fun liveStats(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE_STATS, true)

    fun setLiveStats(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LIVE_STATS, enabled).apply()
    }

    fun keyboardStats(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEYBOARD_STATS, false)

    fun setKeyboardStats(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_KEYBOARD_STATS, enabled).apply()
    }

    data class KeyboardUsage(val sessions: Long, val words: Long, val timeMs: Long)

    fun keyboardUsage(context: Context): KeyboardUsage {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return KeyboardUsage(
            sessions = prefs.getLong(KEY_KB_SESSIONS, 0),
            words = prefs.getLong(KEY_KB_WORDS, 0),
            timeMs = prefs.getLong(KEY_KB_TIME_MS, 0)
        )
    }

    fun addKeyboardSession(context: Context) {
        if (!keyboardStats(context)) return
        addKeyboardCount(context, KEY_KB_SESSIONS, 1)
    }

    fun addKeyboardWords(context: Context, text: String) {
        if (!keyboardStats(context)) return
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words > 0) {
            addKeyboardCount(context, KEY_KB_WORDS, words.toLong())
        }
    }

    fun addKeyboardTime(context: Context, ms: Long) {
        if (!keyboardStats(context) || ms <= 0) return
        addKeyboardCount(context, KEY_KB_TIME_MS, ms)
    }

    private fun addKeyboardCount(context: Context, key: String, amount: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(key, prefs.getLong(key, 0) + amount).apply()
    }

    fun language(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)

    fun setLanguage(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, code).apply()
    }

    fun clearLanguage(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LANGUAGE).apply()
    }

    fun pinnedModels(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED, emptySet()).orEmpty()

    fun togglePinned(context: Context, fileName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pinned = prefs.getStringSet(KEY_PINNED, emptySet()).orEmpty().toMutableSet()
        if (!pinned.add(fileName)) {
            pinned.remove(fileName)
        }
        prefs.edit().putStringSet(KEY_PINNED, pinned).apply()
    }

    // The path to hand the engine, or null when silence is to be decoded along with everything else.
    // The model can go missing after the setting was turned on — a cleared app storage, a restore
    // onto another phone — so the file is checked, not just the preference.
    fun vadModelPath(context: Context): String? {
        if (!skipSilence(context) || !isVadInstalled(context)) {
            return null
        }
        return fileFor(context, ModelCatalog.VAD_FILE).absolutePath
    }

    fun sherpaVadModelPath(context: Context): String? {
        if (!skipSilence(context) || !isSherpaVadInstalled(context)) {
            return null
        }
        return fileFor(context, ModelCatalog.SHERPA_VAD_FILE).absolutePath
    }

    fun activeModelUsesVad(context: Context): Boolean {
        if (EndpointPlugins.plugin?.isActive(context) == true) {
            return false
        }
        if (SherpaPlugins.plugin?.selectedId(context) != null) {
            return true
        }
        val active = openActiveModel(context) ?: return true
        return try {
            LocalModelsPlugins.plugin?.usesVadModel(active.path) ?: true
        } catch (ignore: Throwable) {
            true
        }
    }

    @Synchronized
    fun downloadSherpaVad(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val dest = fileFor(context, ModelCatalog.SHERPA_VAD_FILE)
        if (valid(dest, MIN_VALID_VAD_SIZE)) {
            return dest
        }
        downloadUrl(ModelCatalog.SHERPA_VAD_URL, dest, onProgress, isCancelled, MIN_VALID_VAD_SIZE)
        return dest
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
        return dest
    }

    internal fun downloadUrl(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
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
        require(trimmed.startsWith("https://")) { "Enter a full https:// link to a model file" }
        val fileName = trimmed.substringBefore('?').substringAfterLast('/')
        require(isModelFile(fileName)) { "Link must point to a .bin or .gguf model" }
        return ModelCatalog.customModel(fileName, trimmed)
    }

    fun isModelFile(fileName: String): Boolean =
        fileName.endsWith(".bin") || fileName.endsWith(".gguf")

    fun importFromUri(context: Context, uri: Uri, suggestedName: String?): String {
        val name = (suggestedName ?: "imported-model.gguf").let {
            if (isModelFile(it)) it else "$it.gguf"
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
