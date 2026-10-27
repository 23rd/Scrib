package org.scrib.transcriber

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class TranslateRun(
    val text: String,
    val running: Boolean,
    val error: String?,
    val elapsedMs: Long,
    val tokens: Int,
    val modelName: String?,
    // true untill the first token arrives
    val loading: Boolean = false,
    // detected source language when on auto
    val detectedLanguage: String? = null
)

data class TranslationUi(
    val activeName: String?,
    val rows: List<ModelRow>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val statusMsg: String,
    val statusError: Boolean,
    val run: TranslateRun?,
    val history: List<TranslationHistory.Entry>,
    val preload: Boolean
)

class TranslationViewModel(app: Application) : AndroidViewModel(app) {

    private data class Progress(val pct: Int, val model: TranslationModel)

    private val downloads = ConcurrentHashMap<String, Progress>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var statusMsg = ""
    @Volatile private var statusError = false
    @Volatile private var run: TranslateRun? = null
    private var translateJob: Job? = null
    private var cancellation: CancellationToken? = null

    private val ctx get() = getApplication<Application>()
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val _state = MutableStateFlow(build())
    val state: StateFlow<TranslationUi> = _state

    private fun push() { _state.value = build() }

    private fun build(): TranslationUi {
        val local = BuildConfig.LOCAL_MODELS
        val installed = if (local) TranslationModels.installedFileNames(ctx).toHashSet() else emptySet()
        val active = if (local) TranslationModels.activeFileName(ctx) else null
        fun stateOf(f: String): RowState = when {
            downloads.containsKey(f) -> RowState.Downloading
            failed.contains(f) -> RowState.Failed
            installed.contains(f) -> if (f == active) RowState.Active else RowState.Installed
            else -> RowState.NotDownloaded
        }
        val rows = ArrayList<ModelRow>()
        if (local) {
            TranslationCatalog.MODELS.forEach { m ->
                rows.add(
                    ModelRow(
                        id = m.fileName, name = m.displayName, badge = str(R.string.badge_multilingual),
                        multilingual = true, sizeMb = (m.approxBytes / 1_000_000).toInt(), tier = m.tier,
                        recommended = m.recommended, custom = false,
                        state = stateOf(m.fileName), progress = downloads[m.fileName]?.pct ?: 0
                    )
                )
            }
            val customFiles = LinkedHashSet<String>()
            customFiles.addAll(TranslationModels.installedCustomFileNames(ctx))
            downloads.forEach { (f, p) -> if (p.model.custom) customFiles.add(f) }
            customFiles.forEach { f ->
                rows.add(
                    ModelRow(
                        id = f, name = f.removeSuffix(".gguf"), badge = str(R.string.badge_custom),
                        multilingual = true, sizeMb = (TranslationModels.fileFor(ctx, f).length() / 1_000_000).toInt(),
                        tier = 3, recommended = false, custom = true,
                        state = stateOf(f), progress = downloads[f]?.pct ?: 0
                    )
                )
            }
        }
        return TranslationUi(
            activeName = if (local) TranslationModels.activeDisplayName(ctx) else null,
            rows = rows,
            sourceLanguage = TranslationModels.sourceLanguage(ctx),
            targetLanguage = TranslationModels.targetLanguage(ctx),
            statusMsg = statusMsg, statusError = statusError,
            run = run,
            history = history(),
            preload = TranslationModels.preload(ctx)
        )
    }

    fun refresh() = push()

    fun download(fileName: String) {
        val model = TranslationCatalog.byFileName(fileName) ?: downloads[fileName]?.model ?: return
        startDownload(model)
    }

    private fun startDownload(model: TranslationModel) {
        val f = model.fileName
        if (downloads.containsKey(f)) return
        failed.remove(f)
        downloads[f] = Progress(0, model)
        statusMsg = ""; statusError = false
        push()
        val job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    TranslationModels.download(ctx, model,
                        onProgress = { done, total ->
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (downloads[f]?.pct != pct) {
                                downloads[f] = Progress(pct, model)
                                push()
                            }
                        },
                        isCancelled = { !isActive })
                }
                downloads.remove(f); jobs.remove(f)
                push()
            } catch (e: ModelManager.CancelledDownloadException) {
                downloads.remove(f); jobs.remove(f)
                push()
            } catch (e: Throwable) {
                downloads.remove(f); jobs.remove(f); failed.add(f)
                statusMsg = str(R.string.status_download_failed_named, model.displayName)
                statusError = true
                push()
            }
        }
        jobs[f] = job
    }

    fun cancel(fileName: String) {
        jobs.remove(fileName)?.cancel()
        downloads.remove(fileName)
        push()
    }

    fun activate(fileName: String) {
        TranslationModels.setActive(ctx, fileName)
        releaseEngine()
        statusMsg = ""; statusError = false
        push()
    }

    fun delete(fileName: String) {
        TranslationModels.delete(ctx, fileName)
        releaseEngine()
        push()
    }

    fun addCustom(url: String) {
        val model = try {
            TranslationModels.customModelFromUrl(url)
        } catch (e: IllegalArgumentException) {
            statusMsg = e.message ?: str(R.string.status_invalid_link); statusError = true
            push()
            return
        }
        startDownload(model)
    }

    fun importModel(uri: Uri, suggestedName: String?) {
        statusMsg = str(R.string.status_importing); statusError = false
        push()
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { TranslationModels.importFromUri(ctx, uri, suggestedName) }
                statusMsg = str(R.string.status_imported, name); statusError = false
            } catch (e: Throwable) {
                statusMsg = str(R.string.status_import_failed, e.message ?: ""); statusError = true
            }
            push()
        }
    }

    fun setSourceLanguage(code: String) {
        TranslationModels.setSourceLanguage(ctx, code)
        push()
    }

    fun setTargetLanguage(code: String) {
        TranslationModels.setTargetLanguage(ctx, code)
        push()
    }

    fun swapLanguages() {
        val source = TranslationModels.sourceLanguage(ctx)
        if (source.isEmpty()) return
        TranslationModels.setSourceLanguage(ctx, TranslationModels.targetLanguage(ctx))
        TranslationModels.setTargetLanguage(ctx, source)
        push()
    }

    fun translate(text: String) {
        if (run?.running == true) return
        val plugin = TranslationPlugins.plugin
        val path = TranslationModels.activeModelPath(ctx)
        if (plugin == null || path == null) {
            statusMsg = str(R.string.translate_error_no_model); statusError = true
            push()
            return
        }
        if (text.isBlank()) {
            statusMsg = str(R.string.translate_error_empty); statusError = true
            push()
            return
        }
        statusMsg = ""; statusError = false
        val token = CancellationToken()
        cancellation = token
        val modelName = TranslationModels.activeDisplayName(ctx)
        val chosen = TranslationModels.sourceLanguage(ctx).ifEmpty { null }
        // on auto, detect first so the model can be told
        var detected = if (chosen == null) LanguageDetector.detect(text) else null
        var source = chosen ?: detected
        val target = TranslationModels.targetLanguage(ctx)
        run = TranslateRun("", running = true, error = null, elapsedMs = 0, tokens = 0, modelName = modelName, loading = true, detectedLanguage = detected)
        push()
        val start = SystemClock.elapsedRealtime()
        translateJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPush = 0L
            try {
                // both models rarely fit, drop the speech one for now
                TranscriptionEngine.releaseAll(ctx)
                val engine = plugin.engine(ctx)
                // word lists drew a blank, ask the model (not TranslateGemma, it only translates)
                if (source == null && TranslationCatalog.promptStyleFor(java.io.File(path).name) != PromptStyle.TranslateGemma) {
                    detected = runCatching { engine.detectLanguage(path, text, token) }.getOrNull()
                    source = detected
                    run = run?.copy(detectedLanguage = detected)
                    push()
                }
                val result = engine.translate(path, text, source, target, token) { partial ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPush >= 80) {
                        lastPush = now
                        run = run?.copy(text = partial, elapsedMs = now - start, loading = false)
                        push()
                    }
                }
                run = TranslateRun(
                    result.text, running = false, error = null,
                    elapsedMs = SystemClock.elapsedRealtime() - start, tokens = result.tokens, modelName = modelName,
                    detectedLanguage = detected
                )
                if (result.text.isNotBlank()) {
                    TranslationHistory.save(ctx, text, result.text, source.orEmpty(), target, modelName, detected = chosen == null)
                    historyCache = null
                }
            } catch (e: CancelledException) {
                run = run?.copy(running = false, loading = false, elapsedMs = SystemClock.elapsedRealtime() - start)
            } catch (e: Throwable) {
                run = run?.copy(
                    running = false, loading = false,
                    error = str(R.string.translate_error_failed, e.message ?: e.javaClass.simpleName)
                )
            }
            push()
        }
    }

    @Volatile private var warming = false

    // preload the model if theres enough spare ram
    fun warmUp() {
        if (!TranslationModels.preload(ctx)) return
        if (warming || run?.running == true || TranscriptionRun.state.value?.running == true) return
        val plugin = TranslationPlugins.plugin ?: return
        val path = TranslationModels.activeModelPath(ctx) ?: return
        val modelMb = java.io.File(path).length() / (1024L * 1024L)
        val freeMb = processMemory(ctx).freeRamMb
        if (freeMb < modelMb * 3 / 2 + WARM_UP_HEADROOM_MB) {
            android.util.Log.i(TAG, "Not preloading: $freeMb MB free for a $modelMb MB model")
            return
        }
        warming = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                plugin.engine(ctx).preload(path)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "Preload failed", e)
            } finally {
                warming = false
            }
        }
    }

    fun setPreload(enabled: Boolean) {
        TranslationModels.setPreload(ctx, enabled)
        push()
        if (enabled) warmUp() else releaseEngine()
    }

    fun cancelTranslate() {
        cancellation?.cancel()
    }

    fun dismissRun() {
        if (run?.running == true) return
        run = null
        push()
    }

    private var historyCache: List<TranslationHistory.Entry>? = null

    private fun history(): List<TranslationHistory.Entry> =
        historyCache ?: TranslationHistory.load(ctx).also { historyCache = it }

    fun deleteHistoryEntry(id: String) {
        TranslationHistory.delete(ctx, id)
        historyCache = null
        push()
    }

    fun clearHistory() {
        TranslationHistory.clearAll(ctx)
        historyCache = null
        push()
    }

    private fun releaseEngine() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { TranslationPlugins.plugin?.engine(ctx)?.releaseModel() }
        }
    }

    companion object {
        private const val TAG = "ScribTranslate"
        // headroom left for the rest of the system
        private const val WARM_UP_HEADROOM_MB = 768L
    }

    override fun onCleared() {
        cancellation?.cancel()
        jobs.values.forEach { it.cancel() }
        Thread { runCatching { TranslationPlugins.plugin?.engine(ctx)?.releaseModel() } }.start()
    }
}
