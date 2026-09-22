package org.scrib.transcriber

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class RowState { NotDownloaded, Downloading, Installed, Active, Failed }

data class ModelRow(
    val id: String,
    val name: String,
    val badge: String,
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
    val sherpaRow: ModelRow?,
    val custom: List<ModelRow>,
    val statusMsg: String,
    val statusError: Boolean,
    val skipSilence: Boolean,
    // Progress of the one-off VAD model download, or -1 when nothing is being fetched.
    val vadProgress: Int,
)

// A take in progress: how long it has been running and the recent microphone levels the meter
// draws, newest last.
data class RecordingUi(val elapsedMs: Long, val levels: List<Float>)

data class TranscribeUi(
    val fileName: String,
    val text: String,
    val running: Boolean,
    val error: String?,
    val sourceUri: Uri? = null,
    val segments: List<TranscriptSegment> = emptyList(),
    val format: TranscriptFormat = TranscriptFormat.TXT,
    // How far whisper is through the audio, or -1 while the file is still being decoded.
    val percent: Int = -1,
    val etaMs: Long? = null
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
    @Volatile private var vadPct = -1
    private var vadJob: Job? = null

    private val extProgress = ConcurrentHashMap<String, Int>()
    private val extJobs = ConcurrentHashMap<String, Job>()
    private val extFailed = ConcurrentHashMap.newKeySet<String>()

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
                id = m.fileName, name = m.displayName,
                badge = str(if (m.multilingual) R.string.badge_multilingual else R.string.badge_english_only),
                multilingual = m.multilingual,
                sizeMb = (m.approxBytes / 1_000_000).toInt(), tier = m.tier,
                recommended = m.recommended, custom = false,
                state = stateOf(m.fileName), progress = downloads[m.fileName]?.pct ?: 0
            )
        }
        val customFiles = LinkedHashSet<String>()
        customFiles.addAll(ModelManager.installedCustomFileNames(ctx))
        downloads.forEach { (f, p) -> if (p.model?.custom == true) customFiles.add(f) }
        val custom = customFiles.map { f ->
            val multi = !ModelCatalog.isEnglishOnly(f)
            ModelRow(
                id = f, name = f.removePrefix("ggml-").removeSuffix(".bin"),
                badge = str(if (multi) R.string.badge_multilingual else R.string.badge_english_only),
                multilingual = multi, sizeMb = 0,
                tier = downloads[f]?.model?.tier ?: 3, recommended = false, custom = true,
                state = stateOf(f), progress = downloads[f]?.pct ?: 0
            )
        }
        val activeFriendly = ModelManager.activeDisplayName(ctx)
        val plugin = SherpaPlugins.plugin
        val sherpaId = plugin?.modelId
        val sherpaRow = plugin?.modelRow(
            ctx, active,
            progress = sherpaId?.let { extProgress[it] } ?: 0,
            downloading = sherpaId != null && extJobs.containsKey(sherpaId),
            failed = sherpaId != null && extFailed.contains(sherpaId)
        )
        val sherpaEmpty = sherpaRow == null || sherpaRow.state == RowState.NotDownloaded
        return ScribUiState(
            firstRun = installed.isEmpty() && downloads.isEmpty() && extJobs.isEmpty() && sherpaEmpty,
            activeName = activeFriendly, standard = standard, sherpaRow = sherpaRow, custom = custom,
            statusMsg = statusMsg, statusError = statusError,
            skipSilence = ModelManager.skipSilence(ctx), vadProgress = vadPct,
        )
    }

    fun download(fileName: String) {
        val plugin = SherpaPlugins.plugin
        if (plugin != null && fileName == plugin.modelId) {
            startExternalDownload(plugin, fileName)
            return
        }
        val model = ModelCatalog.byFileName(fileName) ?: downloads[fileName]?.model ?: return
        startDownload(model)
    }

    private fun startExternalDownload(plugin: SherpaPlugin, id: String) {
        if (extJobs.containsKey(id)) {
            return
        }
        extFailed.remove(id)
        extProgress[id] = 0
        push()
        val job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    plugin.downloadModel(ctx, id,
                        onProgress = { done, total ->
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (extProgress[id] != pct) {
                                extProgress[id] = pct
                                push()
                            }
                        },
                        isCancelled = { !isActive })
                }
                extProgress.remove(id)
                extJobs.remove(id)
                push()
            } catch (e: ModelManager.CancelledDownloadException) {
                extProgress.remove(id)
                extJobs.remove(id)
                push()
            } catch (e: Throwable) {
                extProgress.remove(id)
                extJobs.remove(id)
                extFailed.add(id)
                push()
            }
        }
        extJobs[id] = job
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
        if (extJobs.containsKey(fileName)) {
            extJobs.remove(fileName)?.cancel()
            extProgress.remove(fileName)
            push()
            return
        }
        jobs.remove(fileName)?.cancel()
        downloads.remove(fileName)
        push()
    }

    // Skipping silence needs a model of its own, fetched the first time it is asked for. The
    // setting only goes on once that has landed, so it can never point at a model that isn't there.
    fun setSkipSilence(enabled: Boolean) {
        if (!enabled) {
            vadJob?.cancel(); vadJob = null; vadPct = -1
            ModelManager.setSkipSilence(ctx, false)
            push(); return
        }
        if (ModelManager.isVadInstalled(ctx)) {
            ModelManager.setSkipSilence(ctx, true)
            push(); return
        }
        if (vadJob != null) return
        vadPct = 0
        push()
        vadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.downloadVad(ctx,
                        onProgress = { done, total ->
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (vadPct != pct) {
                                vadPct = pct
                                push()
                            }
                        },
                        isCancelled = { !isActive })
                }
                ModelManager.setSkipSilence(ctx, true)
            } catch (e: ModelManager.CancelledDownloadException) {
            } catch (e: Throwable) {
                statusMsg = str(R.string.status_vad_failed); statusError = true
            }
            vadPct = -1; vadJob = null
            push()
        }
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
        SherpaPlugins.plugin?.selfTestNotice(ctx)?.let {
            statusMsg = it
            statusError = false; push(); return
        }
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

    // The run itself lives outside the view model so that leaving the app cannot take it down.
    val transcription: StateFlow<TranscribeUi?> = TranscriptionRun.state

    init {
        TranscriptionRun.restore(ctx)
    }

    fun transcribeFile(uri: Uri, displayName: String?) {
        TranscriptionRun.start(ctx, uri, displayName ?: "audio", source = uri)
    }

    fun setTranscriptFormat(format: TranscriptFormat) {
        TranscriptionRun.setFormat(format)
    }

    fun saveTranscript(uri: Uri) {
        val text = transcription.value?.formatted ?: return
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
        TranscriptionRun.cancel(ctx)
    }

    fun dismissTranscription() {
        TranscriptionRun.dismiss(ctx)
    }

    private val recorder = VoiceRecorder(ctx)

    private val _recording = MutableStateFlow<RecordingUi?>(null)
    val recording: StateFlow<RecordingUi?> = _recording

    private var recordJob: Job? = null
    private var recordFile: File? = null

    fun startRecording() {
        if (_recording.value != null || TranscriptionRun.running) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(R.string.record_denied); return
        }
        val file = File(recordingsDir(), str(R.string.record_name, stamp()) + RECORDING_EXTENSION)
        try {
            recorder.start(file)
        } catch (e: Throwable) {
            toast(R.string.record_failed); return
        }
        recordFile = file
        _recording.value = RecordingUi(0, emptyList())
        val startedAt = SystemClock.elapsedRealtime()
        recordJob = viewModelScope.launch {
            while (true) {
                delay(LEVEL_INTERVAL_MS)
                val level = recorder.level()
                val current = _recording.value ?: break
                _recording.value = current.copy(
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    levels = (current.levels + level).takeLast(LEVEL_HISTORY)
                )
            }
        }
    }

    // Stopping hands the take straight to transcription — that is the only reason it was recorded.
    fun stopRecording() {
        val file = endRecording() ?: return
        if (!recorder.stop() || file.length() == 0L) {
            file.delete()
            toast(R.string.record_empty); return
        }
        TranscriptionRun.start(ctx, Uri.fromFile(file), file.nameWithoutExtension, source = null) { file.delete() }
    }

    fun cancelRecording() {
        val file = endRecording() ?: return
        recorder.stop()
        file.delete()
    }

    private fun endRecording(): File? {
        recordJob?.cancel()
        recordJob = null
        _recording.value = null
        val file = recordFile
        recordFile = null
        return file
    }

    // Nothing is kept between runs: a leftover here is from a take whose process died mid-recording.
    private fun recordingsDir(): File {
        val dir = File(ctx.cacheDir, "recordings")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return dir
    }

    private fun stamp(): String = SimpleDateFormat(STAMP_PATTERN, Locale.ROOT).format(Date())

    // The status box sits far below the record button; a take that never started has to say so
    // where the user is looking.
    private fun toast(id: Int) = Toast.makeText(ctx, str(id), Toast.LENGTH_SHORT).show()

    override fun onCleared() {
        super.onCleared()
        cancelRecording()
    }

    private companion object {
        const val LEVEL_INTERVAL_MS = 100L
        const val LEVEL_HISTORY = 28
        const val RECORDING_EXTENSION = ".m4a"

        // Doubles as the name on screen and on the saved transcript, so no colon and no dot.
        const val STAMP_PATTERN = "yyyy-MM-dd HH-mm"
    }
}
