package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaTranscriptionEngine private constructor(private val appContext: Context) : TranscriptionEngine {

    private val nativeLock = Any()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loadedId: String? = null

    override fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    ) {
        try {
            val segments = transcribeToSegments(audio, request?.languageHint, cancellation) { partial ->
                try {
                    callback.onTranscriptionProgress(partial)
                } catch (ignore: Exception) {
                }
            }
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

    override fun openStream(request: StreamRequest?, callback: ITranscriptionCallback): AudioStream {
        val language = activeLanguage()
        return AudioStream(request, callback) { samples, sampleCount, _, _, _ ->
            val text = Dictionary.applyReplacements(
                appContext, decodeWindow(copyWindow(samples, 0, sampleCount))
            )
            Log.d(TAG, "stream chunk: $sampleCount samples -> ${text.length} chars")
            TranscribedSegment(text, language)
        }
    }

    private fun activeLanguage(): String? {
        val id = ModelManager.activeFileName(appContext) ?: return null
        return SherpaModel.byId(appContext, id)?.languages?.firstOrNull()
    }

    override fun transcribeToSegments(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val pcm = AudioDecoder.decodeToPcm16kMono(audio, cancellation)
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            if (pcm.sampleCount == 0) {
                throw DecodeException("No audio decoded")
            }
            return transcribePcm(pcm.samples, pcm.sampleCount, cancellation, onProgress, onPartial)
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
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        synchronized(nativeLock) {
            ensureRecognizerLocked()
        }
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
                appContext, decodeWindow(copyWindow(samples, offset, length))
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

    private fun decodeWindow(samples: FloatArray): String {
        synchronized(nativeLock) {
            val active = ensureRecognizerLocked()
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
            recognizer = null
            loadedId = null
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
            SherpaModel.isComplete(SherpaModel.dirFor(appContext, info.id), info.files)
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

    private fun ensureRecognizerLocked(): OfflineRecognizer {
        val info = activeModelInfo()
            ?: throw ModelNotAvailableException()
        val dir = SherpaModel.dirFor(appContext, info.id)
        if (!SherpaModel.isComplete(dir, info.files)) {
            throw ModelNotAvailableException()
        }
        if (loadedId == info.id) {
            recognizer?.let {
                return it
            }
        } else {
            try {
                recognizer?.release()
            } catch (ignore: Exception) {
            }
            recognizer = null
            loadedId = null
        }
        var last: Exception? = null
        for (provider in listOf("nnapi", "cpu")) {
            try {
                val fresh = OfflineRecognizer(null, buildConfig(dir, info, provider))
                recognizer = fresh
                loadedId = info.id
                Log.i(TAG, "Sherpa provider=$provider — OK")
                return fresh
            } catch (e: Exception) {
                Log.w(TAG, "Sherpa provider '$provider' failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IllegalStateException("All Sherpa providers failed")
    }

    private fun buildConfig(dir: File, info: SherpaModelInfo, provider: String): OfflineRecognizerConfig {
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(dir, info.files.getValue(SherpaModel.ROLE_ENCODER)).absolutePath,
                decoder = File(dir, info.files.getValue(SherpaModel.ROLE_DECODER)).absolutePath,
                joiner = File(dir, info.files.getValue(SherpaModel.ROLE_JOINER)).absolutePath
            ),
            tokens = File(dir, info.files.getValue(SherpaModel.ROLE_TOKENS)).absolutePath,
            numThreads = 4,
            provider = provider,
            modelType = info.modelType
        )
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig
        )
    }

    companion object {
        private const val TAG = "SherpaEngine"

        const val ENGINE_ID = "sherpa-onnx"

        const val SAMPLE_RATE = 16000

        const val WINDOW_SAMPLES = SAMPLE_RATE * 30

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
