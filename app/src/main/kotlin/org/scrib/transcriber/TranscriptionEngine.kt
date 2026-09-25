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
    val totalMs: Long = 0L,
    val pssMb: Int = 0,
    val peakPssMb: Int = 0,
    val freeRamMb: Int = 0
) {
    val hasData: Boolean get() = totalMs > 0L || audioDurationMs > 0L || inferenceMs > 0L
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
        const val CONTRACT_VERSION = 2

        @Volatile
        private var whisper: TranscriptionEngine? = null

        fun get(context: Context): TranscriptionEngine {
            val app = context.applicationContext
            val plugin = SherpaPlugins.plugin
            if (plugin != null && plugin.selectedId(app) != null) {
                return plugin.engine(app)
            }
            return whisper ?: synchronized(this) {
                whisper ?: WhisperTranscriptionEngine(context.applicationContext).also { whisper = it }
            }
        }
    }
}

class CancelledException : RuntimeException()

class ModelNotAvailableException : RuntimeException()

class DecodeException(message: String) : RuntimeException(message)

fun transcriptionError(type: Byte, message: String? = null): TranscriptionError {
    val error = TranscriptionError()
    error.type = type
    error.message = message
    return error
}
