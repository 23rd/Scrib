package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest

data class TranscriptionMetrics(
    val audioDurationMs: Long = 0L,
    val decodeMs: Long = 0L,
    val inferenceMs: Long = 0L,
    val modelLoadMs: Long = 0L,
    val modelPssMb: Int = 0,
    val modelMemoryDeltaMb: Int = 0,
    val totalMs: Long = 0L,
    val pssMb: Int = 0,
    val peakPssMb: Int = 0,
    val freeRamMb: Int = 0
) {
    val hasData: Boolean get() = totalMs > 0L || audioDurationMs > 0L || inferenceMs > 0L || modelLoadMs > 0L
    val rtf: Double?
        get() = if (audioDurationMs > 0L) inferenceMs.toDouble() / audioDurationMs else null
}

interface TranscriptionEngine {

    fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    )

    fun transcribeToSegments(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit = {},
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit = {}
    ): List<TranscriptSegment>

    // The caller starts the returned stream and feeds it PCM as it is captured.
    fun openStream(request: StreamRequest?, callback: ITranscriptionCallback): AudioStream

    // Hands the model's native memory back until the next request needs it. Blocks while a
    // transcription is running, so it does not belong on the main thread.
    fun releaseModel()

    fun capabilities(): TranscriberCapabilities

    companion object {
        const val CONTRACT_VERSION = 3

        fun releaseAll(context: Context) {
            val app = context.applicationContext
            runCatching { LocalModelsPlugins.plugin?.engine(app)?.releaseModel() }
            runCatching { SherpaPlugins.plugin?.engine(app)?.releaseModel() }
            runCatching { EndpointPlugins.plugin?.engine(app)?.releaseModel() }
        }

        fun get(context: Context): TranscriptionEngine {
            val app = context.applicationContext
            val local = LocalModelsPlugins.plugin
            EndpointPlugins.plugin?.takeIf { it.isActive(app) }?.let { return it.engine(app) }
            val sherpa = SherpaPlugins.plugin
            if (sherpa != null && sherpa.selectedId(app) != null) {
                return sherpa.engine(app)
            }
            local?.let { return it.engine(app) }
            throw ModelNotAvailableException("No engine is set up")
        }
    }
}

class CancelledException : RuntimeException()

class ModelNotAvailableException(message: String? = null) : RuntimeException(message)

class DecodeException(message: String) : RuntimeException(message)

fun transcriptionError(type: Byte, message: String? = null): TranscriptionError {
    val error = TranscriptionError()
    error.type = type
    error.message = message
    return error
}

// One timed callback per decoded segment, so clients can show subtitles. Sent right before the
// terminal result; a client built against an older contract simply never receives them.
fun ITranscriptionCallback.emitSegments(segments: List<TranscriptSegment>) {
    for (segment in segments) {
        val text = segment.text.trim()
        if (text.isEmpty()) {
            continue
        }
        try {
            onTranscriptionSegment(segment.startMs, segment.endMs, text)
        } catch (ignore: Exception) {
        }
    }
}
