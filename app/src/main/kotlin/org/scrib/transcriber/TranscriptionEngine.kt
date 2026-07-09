package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor

interface TranscriptionEngine {

    fun transcribe(
        audio: ParcelFileDescriptor,
        fileName: String?,
        languageHint: String?,
        callback: ITranscriptionCallback
    )

    companion object {
        @Volatile
        private var instance: TranscriptionEngine? = null

        fun get(context: Context): TranscriptionEngine {
            return instance ?: synchronized(this) {
                instance ?: WhisperTranscriptionEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
