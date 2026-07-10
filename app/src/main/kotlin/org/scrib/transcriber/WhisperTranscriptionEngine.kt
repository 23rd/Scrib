package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import com.whispercpp.whisper.WhisperAbortFlag
import com.whispercpp.whisper.WhisperContext
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest

class WhisperTranscriptionEngine(private val appContext: Context) : TranscriptionEngine {

    @Volatile
    private var whisper: WhisperContext? = null

    @Volatile
    private var loadedPath: String? = null

    override fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    ) {
        try {
            val text = transcribeToText(audio, request?.languageHint, cancellation) { partial ->
                try {
                    callback.onTranscriptionProgress(partial)
                } catch (ignore: Exception) {
                }
            }
            callback.onTranscriptionResult(text)
        } catch (e: CancelledException) {
            callback.onTranscriptionError(makeError(ErrorType.CANCELLED, null))
        } catch (e: ModelNotAvailableException) {
            callback.onTranscriptionError(makeError(ErrorType.MODEL_NOT_AVAILABLE, null))
        } catch (e: DecodeException) {
            callback.onTranscriptionError(makeError(ErrorType.DECODE_FAILED, e.message))
        } catch (e: Throwable) {
            callback.onTranscriptionError(makeError(ErrorType.UNEXPECTED, e.message))
        }
    }

    override fun transcribeToText(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onPartial: (String) -> Unit
    ): String {
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val pcm = AudioDecoder.decodeToPcm16kMono(audio, cancellation)
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            if (pcm.isEmpty()) {
                throw DecodeException("No audio decoded")
            }
            return transcribePcm(pcm, languageHint, cancellation, onPartial)
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun transcribePcm(
        pcm: FloatArray,
        languageHint: String?,
        cancellation: CancellationToken,
        onPartial: (String) -> Unit
    ): String {
        val abortFlag = WhisperAbortFlag()
        cancellation.onCancel { abortFlag.cancel() }
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val language = if (languageHint.isNullOrEmpty()) null else languageHint
            val text = whisperContext().transcribeData(pcm, language, abortFlag) { partial ->
                onPartial(partial.trim())
            }.trim()
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            return text
        } finally {
            abortFlag.close()
        }
    }

    override fun capabilities(): TranscriberCapabilities {
        val activeFile = ModelManager.activeFileName(appContext)
        val englishOnly = activeFile != null && ModelCatalog.isEnglishOnly(activeFile)
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = ENGINE_ID
        capabilities.engineVersion = appVersion()
        capabilities.supportedLanguages = if (englishOnly) arrayOf("en") else languages()
        capabilities.autoDetectLanguage = !englishOnly
        capabilities.cancellable = true
        capabilities.modelReady = activeFile != null
        return capabilities
    }

    private fun languages(): Array<String>? = try {
        WhisperContext.supportedLanguages().toTypedArray()
    } catch (ignore: Throwable) {
        null
    }

    private fun appVersion(): String? = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (ignore: Exception) {
        null
    }

    @Synchronized
    private fun whisperContext(): WhisperContext {
        val model = ModelManager.activeModelFile(appContext) ?: throw ModelNotAvailableException()
        val path = model.absolutePath
        whisper?.let {
            if (loadedPath == path) {
                return it
            }
            it.release()
            whisper = null
            loadedPath = null
        }
        return WhisperContext.createContextFromFile(path).also {
            whisper = it
            loadedPath = path
        }
    }

    private fun makeError(type: Byte, message: String?): TranscriptionError {
        val error = TranscriptionError()
        error.type = type
        error.message = message
        return error
    }

    private class CancelledException : RuntimeException()

    private class ModelNotAvailableException : RuntimeException()

    private class DecodeException(message: String) : RuntimeException(message)

    private companion object {
        const val ENGINE_ID = "whisper.cpp"
    }
}
