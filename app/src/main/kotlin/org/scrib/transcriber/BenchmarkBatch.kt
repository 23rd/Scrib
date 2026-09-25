package org.scrib.transcriber

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BenchmarkModelKind {
    WHISPER,
    SHERPA
}

data class BenchmarkModelTarget(
    val id: String,
    val displayName: String,
    val kind: BenchmarkModelKind
)

data class BenchmarkBatchState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val currentModel: String? = null,
    val failed: Int = 0,
    val cancelled: Boolean = false
)

object BenchmarkBatchGate {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    @Synchronized
    fun acquire(): Boolean {
        if (_active.value) return false
        _active.value = true
        return true
    }

    @Synchronized
    fun release() {
        _active.value = false
    }
}

object BenchmarkRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BenchmarkBatchState())
    val state: StateFlow<BenchmarkBatchState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var cancelled = false

    fun installedTargets(context: Context): List<BenchmarkModelTarget> {
        val app = context.applicationContext
        val installed = ModelManager.installedFileNames(app).toSet()
        val standard = ModelCatalog.MODELS
            .filter { it.fileName in installed }
            .map { BenchmarkModelTarget(it.fileName, it.displayName, BenchmarkModelKind.WHISPER) }
        val custom = installed
            .filter { ModelCatalog.byFileName(it) == null }
            .sorted()
            .map { fileName ->
                BenchmarkModelTarget(
                    id = fileName,
                    displayName = fileName.removePrefix("ggml-").removeSuffix(".bin"),
                    kind = BenchmarkModelKind.WHISPER
                )
            }
        val sherpa = SherpaPlugins.plugin
            ?.modelRows(app, null)
            .orEmpty()
            .filter { it.state == RowState.Installed || it.state == RowState.Active }
            .map {
                BenchmarkModelTarget(it.id, it.name, BenchmarkModelKind.SHERPA)
            }
        return standard + custom + sherpa
    }

    fun start(context: Context, uri: Uri, name: String): Boolean {
        if (TranscriptionRun.running || !BenchmarkBatchGate.acquire()) return false
        if (TranscriptionRun.running) {
            BenchmarkBatchGate.release()
            return false
        }
        val app = context.applicationContext
        val targets = try {
            installedTargets(app)
        } catch (e: Throwable) {
            BenchmarkBatchGate.release()
            return false
        }
        if (targets.isEmpty()) {
            BenchmarkBatchGate.release()
            return false
        }
        val originalStoredSelection = ModelManager.storedActiveName(app)
        val originalSkipSilence = ModelManager.skipSilence(app)
        cancelled = false
        _state.value = BenchmarkBatchState(running = true, total = targets.size)
        job = scope.launch {
            var completed = 0
            var failed = 0
            try {
                for (target in targets) {
                    if (cancelled) break
                    _state.value = BenchmarkBatchState(
                        running = true,
                        completed = completed,
                        total = targets.size,
                        currentModel = target.displayName,
                        failed = failed
                    )
                    try {
                        releaseEngines(app)
                        ModelManager.setSkipSilence(app, false)
                        ModelManager.setActive(app, target.id)
                        if (!isActiveTarget(app, target)) {
                            failed++
                        } else {
                            when (runOne(app, uri, name)) {
                                TranscriptionRunResult.SUCCESS -> Unit
                                TranscriptionRunResult.CANCELLED -> cancelled = true
                                TranscriptionRunResult.FAILED,
                                TranscriptionRunResult.REJECTED -> failed++
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        failed++
                    }
                    if (cancelled) break
                    completed++
                    _state.value = BenchmarkBatchState(
                        running = true,
                        completed = completed,
                        total = targets.size,
                        currentModel = target.displayName,
                        failed = failed
                    )
                }
            } finally {
                try {
                    releaseEngines(app)
                    if (originalStoredSelection != null) {
                        ModelManager.setActive(app, originalStoredSelection)
                    } else {
                        ModelManager.clearStoredActiveSelection(app)
                    }
                    ModelManager.setSkipSilence(app, originalSkipSilence)
                } finally {
                    _state.value = BenchmarkBatchState(
                        running = false,
                        completed = completed,
                        total = targets.size,
                        failed = failed,
                        cancelled = cancelled
                    )
                    job = null
                    BenchmarkBatchGate.release()
                }
            }
        }
        return true
    }

    fun cancel() {
        if (!BenchmarkBatchGate.isActive) return
        cancelled = true
        TranscriptionRun.cancelForBenchmark()
    }

    private suspend fun runOne(context: Context, uri: Uri, name: String): TranscriptionRunResult {
        if (cancelled) return TranscriptionRunResult.CANCELLED
        val result = CompletableDeferred<TranscriptionRunResult>()
        val started = TranscriptionRun.startForBenchmark(context, uri, name, uri) {
            result.complete(it)
        }
        if (!started) return TranscriptionRunResult.REJECTED
        if (cancelled) TranscriptionRun.cancelForBenchmark()
        return try {
            result.await()
        } finally {
            TranscriptionRun.clearState()
        }
    }

    private fun isActiveTarget(context: Context, target: BenchmarkModelTarget): Boolean =
        when (target.kind) {
            BenchmarkModelKind.WHISPER ->
                SherpaPlugins.plugin?.selectedId(context) == null && ModelManager.activeFileName(context) == target.id
            BenchmarkModelKind.SHERPA ->
                SherpaPlugins.plugin?.selectedId(context) == target.id
        }

    private fun releaseEngines(context: Context) {
        val app = context.applicationContext
        runCatching { TranscriptionEngine.releaseWhisperInstance() }
        SherpaPlugins.plugin?.let { plugin ->
            runCatching { plugin.engine(app).releaseModel() }
        }
    }
}
