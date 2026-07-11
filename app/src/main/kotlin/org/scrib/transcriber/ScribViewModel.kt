package org.scrib.transcriber

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class RowState { NotDownloaded, Downloading, Installed, Active, Failed }

data class ModelRow(
    val id: String,
    val name: String,
    val subtitle: String,
    val tier: Int,
    val note: String?,
    val custom: Boolean,
    val state: RowState,
    val progress: Int
)

data class ScribUiState(
    val firstRun: Boolean,
    val activeName: String?,
    val standard: List<ModelRow>,
    val custom: List<ModelRow>,
    val statusMsg: String,
    val statusError: Boolean
)

data class TranscribeUi(
    val fileName: String,
    val text: String,
    val running: Boolean,
    val error: String?
)

class ScribViewModel(app: Application) : AndroidViewModel(app) {

    private data class Progress(val pct: Int, val model: WhisperModel?)

    private val downloads = ConcurrentHashMap<String, Progress>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var statusMsg = ""
    @Volatile private var statusError = false

    private val ctx get() = getApplication<Application>()

    private val _state = MutableStateFlow(build())
    val state: StateFlow<ScribUiState> = _state

    private fun push() { _state.value = build() }

    private fun build(): ScribUiState {
        val installed = ModelManager.installedFileNames(ctx).toHashSet()
        val active = ModelManager.activeFileName(ctx)
        fun stateOf(f: String): RowState = when {
            downloads.containsKey(f) -> RowState.Downloading
            failed.contains(f) -> RowState.Failed
            installed.contains(f) -> if (f == active) RowState.Active else RowState.Installed
            else -> RowState.NotDownloaded
        }
        val standard = ModelCatalog.MODELS.map { m ->
            ModelRow(
                id = m.fileName,
                name = m.displayName,
                subtitle = (if (m.multilingual) "multilingual" else "English only") + " · ≈" + (m.approxBytes / 1_000_000) + " MB",
                tier = m.tier,
                note = if (m.recommended) "recommended" else null,
                custom = false,
                state = stateOf(m.fileName),
                progress = downloads[m.fileName]?.pct ?: 0
            )
        }
        val customFiles = LinkedHashSet<String>()
        customFiles.addAll(ModelManager.installedCustomFileNames(ctx))
        downloads.forEach { (f, p) -> if (p.model?.custom == true) customFiles.add(f) }
        val custom = customFiles.map { f ->
            ModelRow(
                id = f,
                name = f.removePrefix("ggml-").removeSuffix(".bin"),
                subtitle = (if (ModelCatalog.isEnglishOnly(f)) "English only" else "multilingual") + " · custom",
                tier = downloads[f]?.model?.tier ?: 3,
                note = null,
                custom = true,
                state = stateOf(f),
                progress = downloads[f]?.pct ?: 0
            )
        }
        val activeFriendly = active?.let { f ->
            ModelCatalog.byFileName(f)?.displayName ?: f.removePrefix("ggml-").removeSuffix(".bin")
        }
        return ScribUiState(
            firstRun = installed.isEmpty() && downloads.isEmpty(),
            activeName = activeFriendly,
            standard = standard,
            custom = custom,
            statusMsg = statusMsg,
            statusError = statusError
        )
    }

    fun download(fileName: String) {
        val model = ModelCatalog.byFileName(fileName) ?: downloads[fileName]?.model ?: return
        startDownload(model)
    }

    private fun startDownload(model: WhisperModel, activateOnComplete: Boolean = false, statusLabel: String? = null) {
        val f = model.fileName
        if (downloads.containsKey(f)) return
        failed.remove(f)
        downloads[f] = Progress(0, model)
        if (statusLabel != null) { statusMsg = "$statusLabel…"; statusError = false }
        push()
        val job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.download(ctx, model,
                        onProgress = { done, total ->
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (downloads[f]?.pct != pct) {
                                downloads[f] = Progress(pct, model)
                                if (statusLabel != null && pct >= 0) {
                                    statusMsg = "$statusLabel… $pct%"
                                    statusError = false
                                }
                                push()
                            }
                        },
                        isCancelled = { !isActive })
                }
                downloads.remove(f); jobs.remove(f)
                if (activateOnComplete) ModelManager.setActive(ctx, f)
                if (statusLabel != null) { statusMsg = "${model.displayName} is ready and active."; statusError = false }
                push()
            } catch (e: ModelManager.CancelledDownloadException) {
                downloads.remove(f); jobs.remove(f)
                if (statusLabel != null) statusMsg = ""
                push()
            } catch (e: Throwable) {
                downloads.remove(f); jobs.remove(f); failed.add(f)
                statusMsg = "Download failed for ${model.displayName} — check your connection and tap Retry."
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
        ModelManager.setActive(ctx, fileName)
        statusMsg = ""; statusError = false
        push()
    }

    fun setupForLanguage(language: LanguageOption) {
        val f = language.recommendedFileName
        val model = ModelCatalog.byFileName(f) ?: return
        if (ModelManager.installedFileNames(ctx).contains(f)) {
            ModelManager.setActive(ctx, f)
            statusMsg = "${language.name}: ${model.displayName} is ready and active."
            statusError = false
            push()
        } else {
            startDownload(model, activateOnComplete = true, statusLabel = "${language.name}: downloading ${model.displayName}")
        }
    }

    fun delete(fileName: String) {
        ModelManager.delete(ctx, fileName)
        failed.remove(fileName)
        push()
    }

    fun addCustom(url: String) {
        val model = try {
            ModelManager.customModelFromUrl(url)
        } catch (e: Exception) {
            statusMsg = e.message ?: "Invalid link"; statusError = true; push(); return
        }
        statusMsg = "Fetching from " + hostOf(model.url) + " — streaming to app storage."
        statusError = false
        startDownload(model)
    }

    fun importModel(uri: Uri, suggestedName: String?) {
        statusMsg = "Importing…"; statusError = false; push()
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { ModelManager.importFromUri(ctx, uri, suggestedName) }
                statusMsg = "Imported $name from device."; statusError = false
            } catch (e: Throwable) {
                statusMsg = "Import failed: ${e.message}"; statusError = true
            }
            push()
        }
    }

    fun selfTest() {
        val active = ModelManager.activeModelFile(ctx)
        if (active == null) {
            statusMsg = "Self-test needs an active model — tap Use on one first."
            statusError = true; push(); return
        }
        statusMsg = "Running self-test…"; statusError = false; push()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val whisper = WhisperContext.createContextFromFile(active.absolutePath)
                    val audio = ctx.assets.open("jfk.wav").use { WavDecoder.decode(it) }
                    val text = whisper.transcribeData(audio, "en")
                    whisper.release()
                    "Self-test ✓ — " + text.trim()
                } catch (e: Throwable) {
                    "Self-test failed: " + e.message
                }
            }
            statusMsg = result
            statusError = result.startsWith("Self-test failed")
            push()
        }
    }

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host ?: "huggingface.co"
    } catch (e: Exception) {
        "huggingface.co"
    }

    @Volatile
    private var transcribeToken: CancellationToken? = null

    private val _transcription = MutableStateFlow<TranscribeUi?>(null)
    val transcription: StateFlow<TranscribeUi?> = _transcription

    fun transcribeFile(uri: Uri, displayName: String?) {
        if (transcribeToken != null) return
        val token = CancellationToken()
        transcribeToken = token
        _transcription.value = TranscribeUi(displayName ?: "audio", "", running = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw RuntimeException("Can't open the file")
                val text = TranscriptionEngine.get(ctx).transcribeToText(pfd, "", token) { partial ->
                    setTranscription { it?.copy(text = partial) }
                }
                if (token.isCancelled) {
                    setTranscription { null }
                } else {
                    setTranscription { it?.copy(text = text.ifBlank { "No speech recognized." }, running = false) }
                }
            } catch (e: Throwable) {
                if (token.isCancelled) {
                    setTranscription { null }
                } else {
                    setTranscription { it?.copy(running = false, error = e.message ?: "Transcription failed") }
                }
            } finally {
                transcribeToken = null
            }
        }
    }

    fun cancelTranscription() {
        transcribeToken?.cancel()
        _transcription.value = null
    }

    fun dismissTranscription() {
        _transcription.value = null
    }

    private fun setTranscription(f: (TranscribeUi?) -> TranscribeUi?) {
        _transcription.value = f(_transcription.value)
    }
}
