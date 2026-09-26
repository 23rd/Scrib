package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaTranscriptionEngine private constructor(private val appContext: Context) : TranscriptionEngine {

    private val nativeLock = Any()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var onlineRecognizer: OnlineRecognizer? = null

    @Volatile
    private var loadedId: String? = null

    @Volatile
    private var loadedLanguage: String? = null

    override fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    ) {
        try {
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
            callback.onTranscriptionError(transcriptionError(ErrorType.MODEL_NOT_AVAILABLE, e.message))
        } catch (e: DecodeException) {
            callback.onTranscriptionError(transcriptionError(ErrorType.DECODE_FAILED, e.message))
        } catch (e: Throwable) {
            callback.onTranscriptionError(transcriptionError(ErrorType.UNEXPECTED, e.message))
        }
    }

    override fun openStream(request: StreamRequest?, callback: ITranscriptionCallback): AudioStream {
        val language = request?.languageHint ?: activeLanguage()
        return AudioStream(request, callback) { samples, sampleCount, _, _, _ ->
            val text = Dictionary.applyReplacements(
                appContext, decodeWindow(copyWindow(samples, 0, sampleCount), request?.languageHint)
            )
            Log.d(TAG, "stream chunk: $sampleCount samples -> ${text.length} chars")
            TranscribedSegment(text, language)
        }
    }

    private fun activeLanguage(): String? =
        activeModelInfo()?.languages?.singleOrNull()

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
            return transcribePcm(
                pcm.samples,
                pcm.sampleCount,
                languageHint,
                cancellation,
                onProgress,
                onPartial,
                onMetrics,
                audioDurationMs,
                decodeMs
            )
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun transcribePcm(
        samples: ByteBuffer,
        sampleCount: Int,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit,
        audioDurationMs: Long,
        decodeMs: Long
    ): List<TranscriptSegment> {
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        val inferenceStartedAt = SystemClock.elapsedRealtime()
        var modelLoadMs = 0L
        var modelPssMb = 0
        var modelMemoryDeltaMb = 0
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
        try {
            val beforeLoad = processMemory(appContext)
            val modelLoadStartedAt = SystemClock.elapsedRealtime()
            val alreadyLoaded = synchronized(nativeLock) {
                ensureRecognizerLocked(languageHint)
            }
            val afterLoad = processMemory(appContext)
            modelLoadMs = if (alreadyLoaded) 0L else SystemClock.elapsedRealtime() - modelLoadStartedAt
            modelPssMb = afterLoad.pssMb
            modelMemoryDeltaMb = if (alreadyLoaded) 0 else maxOf(0, afterLoad.pssMb - beforeLoad.pssMb)
            onMetrics(
                TranscriptionMetrics(
                    modelLoadMs = modelLoadMs,
                    modelPssMb = modelPssMb,
                    modelMemoryDeltaMb = modelMemoryDeltaMb
                )
            )
            val vadPath = ModelManager.sherpaVadModelPath(appContext)
            return if (vadPath != null && sampleCount > WINDOW_SAMPLES) {
                transcribeWithVad(samples, sampleCount, vadPath, languageHint, cancellation, ::reportProgress, onPartial)
            } else {
                transcribeWindows(samples, sampleCount, languageHint, cancellation, ::reportProgress, onPartial)
            }
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
    }

    private fun transcribeWindows(
        samples: ByteBuffer,
        sampleCount: Int,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        val windows = (sampleCount + WINDOW_SAMPLES - 1) / WINDOW_SAMPLES
        val segments = ArrayList<TranscriptSegment>(windows)
        val cumulative = StringBuilder()
        for (i in 0 until windows) {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val offset = i * WINDOW_SAMPLES
            val length = minOf(WINDOW_SAMPLES, sampleCount - offset)
            val text = Dictionary.applyReplacements(
                appContext, decodeWindow(copyWindow(samples, offset, length), languageHint)
            )
            if (text.isNotEmpty()) {
                val startMs = offset.toLong() * 1000 / SAMPLE_RATE
                val endMs = (offset + length).toLong() * 1000 / SAMPLE_RATE
                segments.add(TranscriptSegment(startMs, endMs, if (segments.isEmpty()) text else " $text"))
                if (cumulative.isNotEmpty()) {
                    cumulative.append(' ')
                }
                cumulative.append(text)
                onPartial(cumulative.toString())
            }
            onProgress(((i + 1) * 100) / windows)
        }
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        return segments
    }

    private fun transcribeWithVad(
        samples: ByteBuffer,
        sampleCount: Int,
        vadPath: String,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        val vad = try {
            Vad(
                null,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(model = vadPath),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu"
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Sherpa VAD could not load", e)
            return transcribeWindows(samples, sampleCount, languageHint, cancellation, onProgress, onPartial)
        }
        val segments = ArrayList<TranscriptSegment>()
        val speech = ArrayList<SpeechSpan>()
        val cumulative = StringBuilder()
        val input = samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun appendSegment(start: Int, samples: FloatArray) {
            if (samples.isEmpty()) {
                return
            }
            val text = Dictionary.applyReplacements(appContext, decodeWindow(samples, languageHint))
            if (text.isEmpty()) {
                return
            }
            val startMs = start.toLong() * 1000 / SAMPLE_RATE
            val endMs = (start + samples.size).toLong() * 1000 / SAMPLE_RATE
            segments.add(TranscriptSegment(startMs, endMs, if (segments.isEmpty()) text else " $text"))
            speech.add(SpeechSpan(startMs, endMs))
            if (cumulative.isNotEmpty()) {
                cumulative.append(' ')
            }
            cumulative.append(text)
            onPartial(cumulative.toString())
        }
        // The detector consumes 512 samples at a time, so a naive report here fires once every 32 ms
        // of audio — tens of thousands of times on a long recording, each one waking the metrics
        // sampler and the UI for a percent that has not moved. Only the changes are worth a callback.
        var reportedPercent = -1
        try {
            var offset = 0
            while (offset < sampleCount) {
                if (cancellation.isCancelled) {
                    throw CancelledException()
                }
                val length = minOf(VAD_WINDOW_SAMPLES, sampleCount - offset)
                val chunk = FloatArray(length)
                input.position(offset)
                input.get(chunk)
                vad.acceptWaveform(chunk)
                while (!vad.empty()) {
                    val segment = vad.front()
                    appendSegment(segment.start, segment.samples)
                    vad.pop()
                }
                offset += length
                val percent = ((offset.toLong() * 100) / sampleCount).toInt()
                if (percent != reportedPercent) {
                    reportedPercent = percent
                    onProgress(percent)
                }
            }
            vad.flush()
            while (!vad.empty()) {
                val segment = vad.front()
                appendSegment(segment.start, segment.samples)
                vad.pop()
            }
        } finally {
            vad.release()
        }
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        onProgress(100)
        return markParagraphs(segments, speech)
    }

    private fun decodeWindow(samples: FloatArray, languageHint: String? = null): String {
        synchronized(nativeLock) {
            val info = activeModelInfo() ?: throw ModelNotAvailableException()
            ensureRecognizerLocked(languageHint)
            if (SherpaModel.isStreaming(info.modelType)) {
                val active = onlineRecognizer ?: throw ModelNotAvailableException()
                val stream = active.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()
                    while (active.isReady(stream)) {
                        active.decode(stream)
                    }
                    return active.getResult(stream).text.trim()
                } finally {
                    try {
                        stream.release()
                    } catch (ignore: Exception) {
                    }
                }
            }
            val active = recognizer ?: throw ModelNotAvailableException()
            val stream = active.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                active.decode(stream)
                return active.getResult(stream).text.trim()
            } finally {
                try {
                    stream.release()
                } catch (ignore: Exception) {
                }
            }
        }
    }

    private fun copyWindow(samples: ByteBuffer, offset: Int, length: Int): FloatArray {
        val floats = samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        floats.position(offset)
        floats.limit(offset + length)
        val out = FloatArray(length)
        floats.get(out)
        return out
    }

    override fun releaseModel() {
        synchronized(nativeLock) {
            try {
                recognizer?.release()
            } catch (ignore: Exception) {
            }
            try {
                onlineRecognizer?.release()
            } catch (ignore: Exception) {
            }
            recognizer = null
            onlineRecognizer = null
            loadedId = null
            loadedLanguage = null
        }
    }

    override fun capabilities(): TranscriberCapabilities {
        val info = activeModelInfo()
        val languages = info?.languages
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = ENGINE_ID
        capabilities.engineVersion = appVersion()
        capabilities.supportedLanguages = languages?.toTypedArray()
        capabilities.autoDetectLanguage = languages == null || languages.size != 1
        capabilities.cancellable = true
        capabilities.modelReady = info != null &&
            SherpaModel.isComplete(SherpaModel.dirFor(appContext, info.id), info)
        capabilities.streaming = true
        return capabilities
    }

    private fun activeModelInfo(): SherpaModelInfo? {
        val id = ModelManager.activeFileName(appContext) ?: return null
        return SherpaModel.byId(appContext, id)
    }

    private fun appVersion(): String? = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (ignore: Exception) {
        null
    }

    private fun ensureRecognizerLocked(languageHint: String? = null): Boolean {
        val info = activeModelInfo()
            ?: throw ModelNotAvailableException()
        val dir = SherpaModel.dirFor(appContext, info.id)
        if (!SherpaModel.isComplete(dir, info)) {
            throw ModelNotAvailableException("Sherpa model files are incomplete")
        }
        try {
            SherpaModel.validateMetadata(dir, info)
        } catch (e: IllegalArgumentException) {
            throw ModelNotAvailableException(e.message)
        }
        val language = when (info.modelType) {
            SherpaModel.TYPE_SENSE_VOICE -> SherpaModel.senseVoiceLanguage(info, languageHint)
            SherpaModel.TYPE_CANARY -> SherpaModel.canaryLanguage(info, languageHint)
            else -> null
        }
        val streaming = SherpaModel.isStreaming(info.modelType)
        if (loadedId == info.id && loadedLanguage == language) {
            if (streaming && onlineRecognizer != null) {
                return true
            }
            if (!streaming && recognizer != null) {
                return true
            }
        }
        try {
            recognizer?.release()
        } catch (ignore: Exception) {
        }
        try {
            onlineRecognizer?.release()
        } catch (ignore: Exception) {
        }
        recognizer = null
        onlineRecognizer = null
        loadedId = null
        loadedLanguage = null
        var last: Exception? = null
        for (provider in listOf("nnapi", "cpu")) {
            try {
                if (streaming) {
                    onlineRecognizer = OnlineRecognizer(
                        null,
                        SherpaModel.buildOnlineConfig(dir, info, provider)
                    )
                } else {
                    recognizer = OfflineRecognizer(
                        null,
                        SherpaModel.buildConfig(dir, info, provider, languageHint)
                    )
                }
                loadedId = info.id
                loadedLanguage = language
                Log.i(TAG, "Sherpa provider=$provider — OK")
                return false
            } catch (e: Exception) {
                Log.w(TAG, "Sherpa provider '$provider' failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IllegalStateException("All Sherpa providers failed")
    }

    companion object {
        private const val TAG = "SherpaEngine"

        const val ENGINE_ID = "sherpa-onnx"

        const val SAMPLE_RATE = 16000

        const val WINDOW_SAMPLES = SAMPLE_RATE * 30
        const val VAD_WINDOW_SAMPLES = 512

        @Volatile
        private var instance: SherpaTranscriptionEngine? = null

        fun getInstance(context: Context): SherpaTranscriptionEngine {
            return instance ?: synchronized(this) {
                instance ?: SherpaTranscriptionEngine(context.applicationContext).also { instance = it }
            }
        }

        // Called when a model is deleted while loaded, so the dead files are never touched again.
        fun forgetInstance(id: String) {
            synchronized(this) {
                if (instance?.loadedId == id) {
                    instance?.releaseModel()
                }
            }
        }
    }
}
