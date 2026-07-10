package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest

interface TranscriptionEngine {

    fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    )

    fun transcribeToText(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onPartial: (String) -> Unit
    ): String

    fun capabilities(): TranscriberCapabilities

    companion object {
        const val CONTRACT_VERSION = 1

        @Volatile
        private var instance: TranscriptionEngine? = null

        fun get(context: Context): TranscriptionEngine {
            return instance ?: synchronized(this) {
                instance ?: WhisperTranscriptionEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
