package org.scrib.transcriber

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// A run outlives the screen that started it. The work sits on a process-wide scope rather than in
// the view model, so leaving the app neither cancels it nor drops the text already recognised, and
// the foreground service alongside keeps the process off the low-memory killer's list — an hour of
// audio is hundreds of megabytes of native buffers, which is what the system reclaims first.
object TranscriptionRun {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TranscribeUi?>(null)
    val state: StateFlow<TranscribeUi?> = _state

    @Volatile
    private var token: CancellationToken? = null

    val running: Boolean get() = token != null

    // A recording has no place in the user's storage to point the save dialog at, and its file is
    // the app's own — hence source stays null there and onFinished cleans the file up.
    fun start(context: Context, uri: Uri, name: String, source: Uri?, onFinished: () -> Unit = {}) {
        if (token != null) {
            return
        }
        val app = context.applicationContext
        val cancellation = CancellationToken()
        token = cancellation
        TranscriptStore.clear(app)
        _state.value = TranscribeUi(
            fileName = name,
            text = "",
            running = true,
            error = null,
            sourceUri = source,
            modelName = ModelManager.activeDisplayName(app)
        )
        TranscriptionForegroundService.start(app)
        scope.launch {
            val eta = EtaClock()
            val initialMemory = processMemory(app)
            val startedAt = SystemClock.elapsedRealtime()
            var audioDurationMs = 0L
            var decodeMs = 0L
            var inferenceMs = 0L
            var inferenceStartedAt = 0L
            var peakPssMb = initialMemory.pssMb
            var currentPssMb = initialMemory.pssMb
            var freeRamMb = initialMemory.freeRamMb
            val metricsLock = Any()

            fun currentMetrics(): TranscriptionMetrics = synchronized(metricsLock) {
                val now = SystemClock.elapsedRealtime()
                val memory = processMemory(app)
                currentPssMb = memory.pssMb
                freeRamMb = memory.freeRamMb
                peakPssMb = maxOf(peakPssMb, currentPssMb)
                val liveInferenceMs = if (inferenceStartedAt > 0L) {
                    maxOf(inferenceMs, now - inferenceStartedAt)
                } else {
                    inferenceMs
                }
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    decodeMs = decodeMs,
                    inferenceMs = liveInferenceMs,
                    totalMs = now - startedAt,
                    pssMb = currentPssMb,
                    peakPssMb = peakPssMb,
                    freeRamMb = freeRamMb
                )
            }

            fun publishMetrics() {
                val metrics = currentMetrics()
                _state.update { it?.copy(metrics = metrics) }
            }

            val metricsJob = launch {
                while (isActive) {
                    publishMetrics()
                    delay(METRICS_INTERVAL_MS)
                }
            }
            try {
                val pfd = app.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw RuntimeException(app.getString(R.string.transcribe_cant_open))
                val segments = TranscriptionEngine.get(app).transcribeToSegments(
                    pfd, "", cancellation,
                    onProgress = { pct ->
                        val left = eta.mark(pct)
                        _state.update { it?.copy(percent = pct, etaMs = left) }
                        publishMetrics()
                    },
                    onPartial = { partial -> _state.update { it?.copy(text = partial) } },
                    onMetrics = { metrics ->
                        synchronized(metricsLock) {
                            if (metrics.audioDurationMs > 0L) {
                                audioDurationMs = metrics.audioDurationMs
                                if (inferenceStartedAt == 0L) {
                                    inferenceStartedAt = SystemClock.elapsedRealtime()
                                }
                            }
                            if (metrics.decodeMs > 0L) {
                                decodeMs = metrics.decodeMs
                            }
                            if (metrics.inferenceMs > 0L) {
                                inferenceMs = metrics.inferenceMs
                            }
                        }
                        publishMetrics()
                    }
                )
                val text = segments.format(TranscriptFormat.TXT)
                if (cancellation.isCancelled) {
                    _state.value = null
                } else {
                    _state.update {
                        it?.copy(
                            text = text.ifBlank { app.getString(R.string.transcribe_no_speech) },
                            segments = segments, running = false, percent = 100, etaMs = null
                        )
                    }
                    publishMetrics()
                    if (segments.isNotEmpty()) {
                        _state.value?.let { TranscriptStore.save(app, it) }
                    }
                }
            } catch (e: Throwable) {
                if (cancellation.isCancelled) {
                    _state.value = null
                } else {
                    _state.update {
                        it?.copy(
                            running = false, etaMs = null,
                            error = e.message ?: app.getString(R.string.transcribe_failed)
                        )
                    }
                    publishMetrics()
                }
            } finally {
                metricsJob.cancel()
                token = null
                onFinished()
            }
        }
    }

    fun restore(context: Context) {
        if (token != null || _state.value != null) {
            return
        }
        TranscriptStore.load(context.applicationContext)?.let { _state.value = it }
    }

    fun cancel(context: Context) {
        token?.cancel()
        TranscriptStore.clear(context.applicationContext)
        _state.value = null
    }

    fun dismiss(context: Context) {
        TranscriptStore.clear(context.applicationContext)
        _state.value = null
    }

    fun setFormat(format: TranscriptFormat) {
        _state.update { it?.copy(format = format) }
    }
}

private data class ProcessMemory(
    val pssMb: Int,
    val freeRamMb: Int
)

private fun processMemory(context: Context): ProcessMemory {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val systemMemory = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(systemMemory)
    val processMemory = Debug.MemoryInfo()
    Debug.getMemoryInfo(processMemory)
    return ProcessMemory(
        pssMb = (processMemory.totalPss / 1024L).toInt(),
        freeRamMb = (systemMemory.availMem / (1024L * 1024L)).toInt()
    )
}

private const val METRICS_INTERVAL_MS = 500L

// Whisper reports how far through the audio it is, and it advances at a steady rate once the model
// is warm — so the time the run has taken to cover the percent so far carries over to what is left.
// The first report starts the clock: everything before it was decoding, at a different speed.
private class EtaClock {

    private var startedAt = 0L
    private var basePercent = -1

    fun mark(percent: Int): Long? {
        val now = SystemClock.elapsedRealtime()
        if (basePercent < 0) {
            if (percent in 0..99) {
                startedAt = now
                basePercent = percent
            }
            return null
        }
        val covered = percent - basePercent
        val elapsed = now - startedAt
        if (covered <= 0 || percent >= 100 || elapsed < SETTLE_MS) {
            return null
        }
        return elapsed * (100 - percent) / covered
    }

    private companion object {
        // Too early on, one slow window skews the estimate into the absurd.
        const val SETTLE_MS = 4000L
    }
}
