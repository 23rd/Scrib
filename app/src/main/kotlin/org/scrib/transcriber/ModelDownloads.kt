package org.scrib.transcriber

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Model transfers belong to the app, not to the activity or its ViewModel. All book-keeping is
// serialized on Main, while the HTTP stream and file writes run on IO.
internal object ModelDownloads {
    data class Transfer(val model: WhisperModel?, val percent: Int = 0)
    data class Status(val message: String, val error: Boolean)
    data class Snapshot(
        val active: Map<String, Transfer> = emptyMap(),
        val failed: Set<String> = emptySet(),
        val vadPercent: Int = -1,
        val status: Status? = null
    )

    private const val VAD = "@vad"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val _state = MutableStateFlow(Snapshot())
    val state = _state.asStateFlow()

    fun model(context: Context, model: WhisperModel, activate: Boolean, label: String?) {
        val app = context.applicationContext
        val id = model.fileName
        val status = label?.let { Status(app.getString(R.string.status_ellipsis, it), false) }
        start(
            app, id, Transfer(model), status,
            transfer = { report, cancelled -> ModelManager.download(app, model, report, cancelled) },
            completed = {
                if (activate) ModelManager.setActive(app, id)
                label?.let { Status(app.getString(R.string.status_ready_active, model.displayName), false) }
            },
            tick = { p -> label?.takeIf { p >= 0 }?.let { Status(app.getString(R.string.status_pct, it, p), false) } },
            failure = { Status(app.getString(R.string.status_download_failed_named, model.displayName), true) }
        )
    }

    fun vad(context: Context, sherpa: Boolean) {
        val app = context.applicationContext
        start(
            app, VAD, Transfer(null), null,
            transfer = { report, cancelled ->
                if (sherpa) ModelManager.downloadSherpaVad(app, report, cancelled)
                else ModelManager.downloadVad(app, report, cancelled)
            },
            completed = { ModelManager.setSkipSilence(app, true); null },
            tick = { null },
            failure = { Status(app.getString(R.string.status_vad_failed), true) }
        )
    }

    private fun start(
        app: Context,
        id: String,
        item: Transfer,
        initialStatus: Status?,
        transfer: ((Long, Long) -> Unit, () -> Boolean) -> Unit,
        completed: () -> Status?,
        tick: (Int) -> Status?,
        failure: () -> Status
    ) {
        if (jobs.containsKey(id)) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            var status: Status? = null
            var failed = false
            try {
                withContext(Dispatchers.IO) {
                    var reported = 0
                    transfer({ done, total ->
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (reported != percent) {
                            reported = percent
                            scope.launch {
                                if (jobs[id] === job && job.isActive) {
                                    val current = _state.value
                                    _state.value = current.copy(
                                        active = current.active + (id to item.copy(percent = percent)),
                                        vadPercent = if (id == VAD) maxOf(0, percent) else current.vadPercent,
                                        status = tick(percent) ?: current.status
                                    )
                                }
                            }
                        }
                    }, { !isActive })
                }
                status = completed()
                StateLog.log("model download finished: ${item.model?.fileName ?: id}")
            } catch (_: CancellationException) {
                if (initialStatus != null) status = Status("", false)
                StateLog.log("model download cancelled: $id")
            } catch (_: ModelManager.CancelledDownloadException) {
                if (initialStatus != null) status = Status("", false)
                StateLog.log("model download cancelled: $id")
            } catch (e: Exception) {
                failed = true
                status = failure()
                // Do not log the source URL: custom download URLs can carry credentials.
                Log.w("ScribDownload", "Model download failed (${e.javaClass.simpleName})")
                StateLog.log("model download failed: $id (${e.javaClass.simpleName})")
            } finally {
                jobs.remove(id)
                val previous = _state.value
                _state.value = previous.copy(
                    active = previous.active - id,
                    failed = if (failed && id != VAD) previous.failed + id else previous.failed,
                    vadPercent = if (id == VAD) -1 else previous.vadPercent,
                    status = status ?: previous.status
                )
            }
        }
        jobs[id] = job
        val previous = _state.value
        _state.value = previous.copy(
            active = previous.active + (id to item),
            failed = previous.failed - id,
            vadPercent = if (id == VAD) 0 else previous.vadPercent,
            status = initialStatus ?: previous.status
        )
        ModelDownloadService.start(app)
        StateLog.log("model download started: $id")
        job.start()
    }

    fun cancel(id: String) { jobs[id]?.cancel() }
    fun cancelVad() = cancel(VAD)
    fun cancelAll() { jobs.values.toList().forEach { it.cancel() } }

    fun clearFailure(id: String) {
        val previous = _state.value
        _state.value = previous.copy(failed = previous.failed - id)
    }
}
