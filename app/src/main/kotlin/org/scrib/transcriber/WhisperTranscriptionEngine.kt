package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.whispercpp.whisper.WhisperAbortFlag
import com.whispercpp.whisper.WhisperContext
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
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
            // The contract hands clients plain text; the timings stay in the app's own screen.
            val segments = transcribeToSegments(
                audio,
                request?.languageHint,
                cancellation,
                onPartial = { partial ->
                    try {
                        callback.onTranscriptionProgress(partial)
                    } catch (ignore: Exception) {
                    }
                }
            )
            callback.onTranscriptionResult(segments.format(TranscriptFormat.TXT))
        } catch (e: CancelledException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.CANCELLED))
        } catch (e: ModelNotAvailableException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.MODEL_NOT_AVAILABLE))
        } catch (e: DecodeException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.DECODE_FAILED, e.message))
        } catch (e: Throwable) {
            callback.onTranscriptionError(transcriptionError(ErrorType.UNEXPECTED, e.message))
        }
    }

    override fun openStream(request: StreamRequest?, callback: ITranscriptionCallback): AudioStream =
        AudioStream(request, callback) { samples, sampleCount, language, prompt, abortFlag ->
            val chunk = whisperContext().transcribeChunk(samples, sampleCount, language, prompt, abortFlag)
            TranscribedSegment(Dictionary.applyReplacements(appContext, chunk.text), chunk.language)
        }

    override fun transcribeToSegments(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit
    ): List<TranscriptSegment> {
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val decodeStartedAt = SystemClock.elapsedRealtime()
            val pcm = AudioDecoder.decodeToPcm16kMono(audio, cancellation)
            val decodeMs = SystemClock.elapsedRealtime() - decodeStartedAt
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            if (pcm.sampleCount == 0) {
                throw DecodeException("No audio decoded")
            }
            val audioDurationMs = pcm.sampleCount.toLong() * 1000L / SAMPLE_RATE
            onMetrics(TranscriptionMetrics(audioDurationMs = audioDurationMs, decodeMs = decodeMs))
            var modelLoadMs = 0L
            var modelPssMb = 0
            var modelMemoryDeltaMb = 0
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            fun reportProgress(percent: Int) {
                onProgress(percent)
                onMetrics(
                    TranscriptionMetrics(
                        audioDurationMs = audioDurationMs,
                        decodeMs = decodeMs,
                        inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt,
                        modelLoadMs = modelLoadMs,
                        modelPssMb = modelPssMb,
                        modelMemoryDeltaMb = modelMemoryDeltaMb
                    )
                )
            }
            return try {
                transcribePcm(
                    pcm,
                    languageHint,
                    cancellation,
                    ::reportProgress,
                    onPartial,
                    onModelLoad = { metrics ->
                        modelLoadMs = metrics.modelLoadMs
                        modelPssMb = metrics.modelPssMb
                        modelMemoryDeltaMb = metrics.modelMemoryDeltaMb
                        onMetrics(metrics)
                    }
                )
            } finally {
                onMetrics(
                    TranscriptionMetrics(
                        audioDurationMs = audioDurationMs,
                        decodeMs = decodeMs,
                        inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt,
                        modelLoadMs = modelLoadMs,
                        modelPssMb = modelPssMb,
                        modelMemoryDeltaMb = modelMemoryDeltaMb
                    )
                )
            }
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun transcribePcm(
        pcm: DecodedAudio,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onModelLoad: (TranscriptionMetrics) -> Unit
    ): List<TranscriptSegment> {
        val abortFlag = WhisperAbortFlag()
        cancellation.onCancel { abortFlag.cancel() }
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val language = if (languageHint.isNullOrEmpty()) null else languageHint
            val activePath = ModelManager.activeModelFile(appContext)?.absolutePath
            val alreadyLoaded = whisper != null && loadedPath == activePath
            val beforeLoad = processMemory(appContext)
            val loadStartedAt = SystemClock.elapsedRealtime()
            val context = whisperContext()
            val afterLoad = processMemory(appContext)
            onModelLoad(
                TranscriptionMetrics(
                    modelLoadMs = if (alreadyLoaded) 0L else SystemClock.elapsedRealtime() - loadStartedAt,
                    modelPssMb = afterLoad.pssMb,
                    modelMemoryDeltaMb = if (alreadyLoaded) 0 else maxOf(0, afterLoad.pssMb - beforeLoad.pssMb)
                )
            )
            val window = context.audioWindowSamples
            if (window > 0 && pcm.sampleCount > window) {
                throw DecodeException(
                    appContext.getString(R.string.transcribe_model_window, window / SAMPLE_RATE)
                )
            }
            val result = context.transcribeBuffer(
                pcm.samples, pcm.sampleCount, language, abortFlag,
                vadModelPath = ModelManager.vadModelPath(appContext),
                onSegment = { partial -> onPartial(partial.trim()) },
                onProgress = onProgress
            )
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val segments = result.segments
                .filter { it.text.isNotBlank() }
                .map { TranscriptSegment(it.startMs, it.endMs, Dictionary.applyReplacements(appContext, it.text)) }
            return markParagraphs(segments, result.speech.map { SpeechSpan(it.startMs, it.endMs) })
        } finally {
            abortFlag.close()
        }
    }

    @Synchronized
    override fun releaseModel() {
        whisper?.release()
        whisper = null
        loadedPath = null
    }

    override fun capabilities(): TranscriberCapabilities {
        val activeFile = ModelManager.activeFileName(appContext)
        val parakeet = activeModelIsParakeet()
        val englishOnly = !parakeet && activeFile != null && ModelCatalog.isEnglishOnly(activeFile)
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = if (parakeet) PARAKEET_ENGINE_ID else ENGINE_ID
        capabilities.engineVersion = appVersion()
        capabilities.supportedLanguages = when {
            parakeet -> ModelCatalog.PARAKEET_LANGUAGES.toTypedArray()
            englishOnly -> arrayOf("en")
            else -> languages()
        }
        capabilities.autoDetectLanguage = !englishOnly
        capabilities.cancellable = true
        capabilities.modelReady = activeFile != null
        capabilities.streaming = true
        return capabilities
    }

    private fun activeModelIsParakeet(): Boolean {
        val model = ModelManager.activeModelFile(appContext) ?: return false
        return try {
            WhisperContext.isParakeetModel(model.absolutePath)
        } catch (ignore: Throwable) {
            false
        }
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

    private companion object {
        const val ENGINE_ID = "whisper.cpp"
        const val PARAKEET_ENGINE_ID = "parakeet.cpp"

        const val SAMPLE_RATE = 16000
    }
}
