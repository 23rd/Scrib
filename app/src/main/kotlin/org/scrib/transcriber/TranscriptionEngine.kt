package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest

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
        onPartial: (String) -> Unit
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
        private var instance: TranscriptionEngine? = null

        fun get(context: Context): TranscriptionEngine {
            return instance ?: synchronized(this) {
                instance ?: WhisperTranscriptionEngine(context.applicationContext).also { instance = it }
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
