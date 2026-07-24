package org.scrib.transcriber

import android.app.Application
import android.net.Uri
import android.widget.Toast
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
    val multilingual: Boolean,
    val sizeMb: Int,
    val tier: Int,
    val recommended: Boolean,
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
    val error: String?,
    val sourceUri: Uri? = null,
    val segments: List<TranscriptSegment> = emptyList(),
    val format: TranscriptFormat = TranscriptFormat.TXT
) {
    // What the screen shows and what Copy, Share and Save hand over. Falls back to the plain text
    // while the run is still going and when nothing was recognised.
    val formatted: String get() = if (segments.isEmpty()) text else segments.format(format)
}

class ScribViewModel(app: Application) : AndroidViewModel(app) {

    private data class Progress(val pct: Int, val model: WhisperModel?)

    private val downloads = ConcurrentHashMap<String, Progress>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var statusMsg = ""
    @Volatile private var statusError = false

    private val ctx get() = getApplication<Application>()
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

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
                id = m.fileName, name = m.displayName, multilingual = m.multilingual,
                sizeMb = (m.approxBytes / 1_000_000).toInt(), tier = m.tier,
                recommended = m.recommended, custom = false,
                state = stateOf(m.fileName), progress = downloads[m.fileName]?.pct ?: 0
            )
        }
        val customFiles = LinkedHashSet<String>()
        customFiles.addAll(ModelManager.installedCustomFileNames(ctx))
        downloads.forEach { (f, p) -> if (p.model?.custom == true) customFiles.add(f) }
        val custom = customFiles.map { f ->
            ModelRow(
                id = f, name = f.removePrefix("ggml-").removeSuffix(".bin"),
                multilingual = !ModelCatalog.isEnglishOnly(f), sizeMb = 0,
                tier = downloads[f]?.model?.tier ?: 3, recommended = false, custom = true,
                state = stateOf(f), progress = downloads[f]?.pct ?: 0
            )
        }
        val activeFriendly = active?.let { f ->
            ModelCatalog.byFileName(f)?.displayName ?: f.removePrefix("ggml-").removeSuffix(".bin")
        }
        return ScribUiState(
            firstRun = installed.isEmpty() && downloads.isEmpty(),
            activeName = activeFriendly, standard = standard, custom = custom,
            statusMsg = statusMsg, statusError = statusError
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
        if (statusLabel != null) { statusMsg = str(R.string.status_ellipsis, statusLabel); statusError = false }
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
                                    statusMsg = str(R.string.status_pct, statusLabel, pct)
                                    statusError = false
                                }
                                push()
                            }
                        },
                        isCancelled = { !isActive })
                }
                downloads.remove(f); jobs.remove(f)
                if (activateOnComplete) ModelManager.setActive(ctx, f)
                if (statusLabel != null) { statusMsg = str(R.string.status_ready_active, model.displayName); statusError = false }
                push()
            } catch (e: ModelManager.CancelledDownloadException) {
                downloads.remove(f); jobs.remove(f)
                if (statusLabel != null) statusMsg = ""
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
        ModelManager.setActive(ctx, fileName)
        statusMsg = ""; statusError = false
        push()
    }

    fun setupForLanguage(language: LanguageOption) {
        val f = language.recommendedFileName
        val model = ModelCatalog.byFileName(f) ?: return
        val langName = str(language.nameRes)
        if (ModelManager.installedFileNames(ctx).contains(f)) {
            ModelManager.setActive(ctx, f)
            statusMsg = str(R.string.status_lang_ready_active, langName, model.displayName)
            statusError = false
            push()
        } else {
            startDownload(
                model, activateOnComplete = true,
                statusLabel = str(R.string.status_lang_downloading, langName, model.displayName)
            )
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
            statusMsg = str(R.string.status_invalid_link); statusError = true; push(); return
        }
        statusMsg = str(R.string.status_fetching_from, hostOf(model.url))
        statusError = false
        startDownload(model)
    }

    fun importModel(uri: Uri, suggestedName: String?) {
        statusMsg = str(R.string.status_importing); statusError = false; push()
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { ModelManager.importFromUri(ctx, uri, suggestedName) }
                statusMsg = str(R.string.status_imported, name); statusError = false
            } catch (e: Throwable) {
                statusMsg = str(R.string.status_import_failed, e.message ?: ""); statusError = true
            }
            push()
        }
    }

    fun selfTest() {
        val active = ModelManager.activeModelFile(ctx)
        if (active == null) {
            statusMsg = str(R.string.status_selftest_needs_model)
            statusError = true; push(); return
        }
        statusMsg = str(R.string.status_running_selftest); statusError = false; push()
        viewModelScope.launch {
            val (msg, err) = withContext(Dispatchers.IO) {
                try {
                    val whisper = WhisperContext.createContextFromFile(active.absolutePath)
                    val audio = ctx.assets.open("jfk.wav").use { WavDecoder.decode(it) }
                    val text = whisper.transcribeData(audio, "en")
                    whisper.release()
                    str(R.string.status_selftest_ok, text.trim()) to false
                } catch (e: Throwable) {
                    str(R.string.status_selftest_failed, e.message ?: "") to true
                }
            }
            statusMsg = msg; statusError = err
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
        _transcription.value = TranscribeUi(displayName ?: "audio", "", running = true, error = null, sourceUri = uri)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw RuntimeException(str(R.string.transcribe_cant_open))
                val segments = TranscriptionEngine.get(ctx).transcribeToSegments(pfd, "", token) { partial ->
                    setTranscription { it?.copy(text = partial) }
                }
                val text = segments.format(TranscriptFormat.TXT)
                if (token.isCancelled) {
                    setTranscription { null }
                } else {
                    setTranscription {
                        it?.copy(
                            text = text.ifBlank { str(R.string.transcribe_no_speech) },
                            segments = segments, running = false
                        )
                    }
                }
            } catch (e: Throwable) {
                if (token.isCancelled) {
                    setTranscription { null }
                } else {
                    setTranscription { it?.copy(running = false, error = e.message ?: str(R.string.transcribe_failed)) }
                }
            } finally {
                transcribeToken = null
            }
        }
    }

    fun setTranscriptFormat(format: TranscriptFormat) {
        setTranscription { it?.copy(format = format) }
    }

    fun saveTranscript(uri: Uri) {
        val text = _transcription.value?.formatted ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                writeText(uri, text, "wt")
                true
            } catch (e: Throwable) {
                // Some providers reject the explicit truncate mode; retry with the default one.
                try {
                    writeText(uri, text, "w")
                    true
                } catch (e2: Throwable) {
                    false
                }
            }
            withContext(Dispatchers.Main) {
                val msg = if (ok) R.string.transcript_saved else R.string.transcript_save_failed
                Toast.makeText(ctx, str(msg), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeText(uri: Uri, text: String, mode: String) {
        val out = ctx.contentResolver.openOutputStream(uri, mode)
            ?: throw RuntimeException("Can't open $uri")
        out.bufferedWriter().use { it.write(text) }
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
