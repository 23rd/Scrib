package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import com.whispercpp.whisper.WhisperContext

class WhisperTranscriptionEngine(private val appContext: Context) : TranscriptionEngine {

    @Volatile
    private var whisper: WhisperContext? = null

    override fun transcribe(
        audio: ParcelFileDescriptor,
        fileName: String?,
        languageHint: String?,
        callback: ITranscriptionCallback
    ) {
        try {
            val pcm = AudioDecoder.decodeToPcm16kMono(audio)
            if (pcm.isEmpty()) {
                callback.onTranscriptionError(makeError(ErrorType.DECODE_FAILED, "No audio decoded"))
                return
            }
            val language = if (languageHint.isNullOrEmpty()) null else languageHint
            val text = whisperContext().transcribeData(pcm, language) { partial ->
                try {
                    callback.onTranscriptionProgress(partial.trim())
                } catch (ignore: Exception) {
                }
            }.trim()
            callback.onTranscriptionResult(text)
        } catch (e: Throwable) {
            callback.onTranscriptionError(makeError(ErrorType.UNEXPECTED, e.message))
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
    }

    @Synchronized
    private fun whisperContext(): WhisperContext {
        whisper?.let { return it }
        val model = ModelManager.ensureModel(appContext)
        return WhisperContext.createContextFromFile(model.absolutePath).also { whisper = it }
    }

    private fun makeError(type: Byte, message: String?): TranscriptionError {
        val error = TranscriptionError()
        error.type = type
        error.message = message
        return error
    }
}
