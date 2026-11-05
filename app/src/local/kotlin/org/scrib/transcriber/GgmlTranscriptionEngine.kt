package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgmlTranscriptionEngine(private val appContext: Context) : TranscriptionEngine {

    @Volatile
    private var model: TranscribeModel? = null

    @Volatile
    private var session: TranscribeSession? = null

    @Volatile
    private var loadedPath: String? = null

    override fun transcribe(
        audio: ParcelFileDescriptor,
        request: TranscriptionRequest?,
        callback: ITranscriptionCallback,
        cancellation: CancellationToken
    ) {
        try {
            val range = request.rangeOrNull()
            val segments = transcribeToSegments(
                audio,
                request?.languageHint,
                cancellation,
                onPartial = { partial ->
                    try {
                        callback.onTranscriptionProgress(partial)
                    } catch (ignore: Exception) {
                    }
                },
                range = range
            ).filterOverlapping(range)
            callback.emitSegments(segments)
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
        AudioStream(request, callback) { samples, sampleCount, language, prompt, cancellation ->
            val abort = TranscribeAbort.create()
            try {
                cancellation.onCancel { abort.cancel() }
                val live = session()
                val audio = RunAudio.whole(samples, sampleCount)
                val text = runUtterance(live, model()!!.info, audio, 0, sampleCount, language, abort).text
                val spoken = live.detectedLanguage ?: language
                TranscribedSegment(Dictionary.applyReplacements(appContext, text), spoken)
            } finally {
                abort.close()
            }
        }

    override fun transcribeToSegments(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit,
        range: AudioRange?
    ): List<TranscriptSegment> {
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val decodeStartedAt = SystemClock.elapsedRealtime()
            val pcm = AudioDecoder.decodeToPcm16kMono(audio, cancellation, appContext.cacheDir, range)
            try {
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
                        },
                        // The decoded buffer starts at this moment of the original file; RunAudio
                        // adds it once, where buffer-relative times turn into original ones.
                        rangeStartMs = range?.startMs?.coerceAtLeast(0L) ?: 0L
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
                pcm.close()
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
        onModelLoad: (TranscriptionMetrics) -> Unit,
        rangeStartMs: Long
    ): List<TranscriptSegment> {
        val abort = TranscribeAbort.create()
        cancellation.onCancel { abort.cancel() }
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val activeKey = ModelManager.activeModelKey(appContext) ?: throw ModelNotAvailableException()
            val alreadyLoaded = loadedPath == activeKey && session != null
            val beforeLoad = processMemory(appContext)
            val loadStartedAt = SystemClock.elapsedRealtime()
            val live = session()
            val info = model()!!.info
            val hinted = languageHint?.takeIf { it.isNotEmpty() && it in info.languages }
            val afterLoad = processMemory(appContext)
            onModelLoad(
                TranscriptionMetrics(
                    modelLoadMs = if (alreadyLoaded) 0L else SystemClock.elapsedRealtime() - loadStartedAt,
                    modelPssMb = afterLoad.pssMb,
                    modelMemoryDeltaMb = if (alreadyLoaded) 0 else maxOf(0, afterLoad.pssMb - beforeLoad.pssMb)
                )
            )
            val audio = runAudio(pcm, info, rangeStartMs)
            val window = windowSamples(info)
            if (window in 1 until audio.sampleCount) {
                return transcribeWindows(
                    live, info, audio, window, hinted, abort, cancellation, onProgress, onPartial
                )
            }
            onProgress(0)
            val result = runUtterance(live, info, audio, 0, audio.sampleCount, hinted, abort, onPartial)
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            onProgress(100)
            return finishSegments(
                audio.segmentsOf(result, 0, audio.sampleCount) { text ->
                    Dictionary.applyReplacements(appContext, text)
                },
                emptyList()
            )
        } finally {
            abort.close()
        }
    }

    private fun windowSamples(info: TranscribeModelInfo): Int =
        if (info.longForm || info.streaming) 0 else WINDOW_SAMPLES

    private fun transcribeWindows(
        live: TranscribeSession,
        info: TranscribeModelInfo,
        audio: RunAudio,
        window: Int,
        language: String?,
        abort: TranscribeAbort,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit
    ): List<TranscriptSegment> {
        var windows = (audio.sampleCount + window - 1) / window
        if (windows > 1 && audio.sampleCount - (windows - 1) * window < MIN_TRANSCRIBABLE_WINDOW_SAMPLES) {
            windows -= 1
        }
        val segments = ArrayList<TranscriptSegment>(windows)
        val cumulative = StringBuilder()
        for (i in 0 until windows) {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val offset = i * window
            val length = if (i == windows - 1) audio.sampleCount - offset else window
            onProgress(i * 100 / windows)
            val result = runUtterance(
                live, info, audio, offset, length, language, abort,
                onPartial = { partial ->
                    cumulative.append(partial)
                    onPartial(cumulative.toString())
                }
            )
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            segments.addAll(
                audio.segmentsOf(result, offset, length) { text ->
                    Dictionary.applyReplacements(appContext, text)
                }
            )
        }
        return finishSegments(segments, emptyList())
    }

    private fun runAudio(pcm: DecodedAudio, info: TranscribeModelInfo, rangeStartMs: Long): RunAudio {
        val path = ModelManager.vadModelPath(appContext)
        if (path == null || info.streaming || !ModelManager.activeModelUsesVad(appContext)) {
            return RunAudio.whole(pcm.samples, pcm.sampleCount, rangeStartMs)
        }
        val vad = TranscribeVad.open(path) ?: return RunAudio.whole(pcm.samples, pcm.sampleCount, rangeStartMs)
        val spans = try {
            vad.spans(pcm.samples, pcm.sampleCount)
        } catch (e: Throwable) {
            Log.w("GgmlEngine", "Silence skipping failed, transcribing everything", e)
            return RunAudio.whole(pcm.samples, pcm.sampleCount, rangeStartMs)
        }
        if (spans.isEmpty()) {
            return RunAudio.whole(pcm.samples, pcm.sampleCount, rangeStartMs)
        }
        val speech = spans.sumOf { (it.endMs - it.startMs).toDouble() }
        Log.d(
            "GgmlEngine",
            "Skipping silence: ${spans.size} stretch(es), ${(speech / 1000).toInt()}s of " +
                "${pcm.sampleCount * 1000L / SAMPLE_RATE / 1000}s"
        )
        // Packing speech into its own buffer needs one more native allocation on top of the
        // decoded audio; failing that must degrade to transcribing everything, not kill the run.
        return try {
            RunAudio.compact(pcm.samples, pcm.sampleCount, spans, rangeStartMs)
        } catch (e: Throwable) {
            Log.w("GgmlEngine", "Packing the speech spans failed, transcribing everything", e)
            null
        } ?: RunAudio.whole(pcm.samples, pcm.sampleCount, rangeStartMs)
    }

    private fun runUtterance(
        live: TranscribeSession,
        info: TranscribeModelInfo,
        audio: RunAudio,
        offset: Int,
        length: Int,
        language: String?,
        abort: TranscribeAbort,
        onPartial: ((String) -> Unit)? = null
    ): RunResult {
        val samples = audio.slice(offset, length)
        if (!info.streaming) {
            TranscribeSession.check(live.run(samples, length, language, null, false, abort))
            return RunResult(live.fullText.orEmpty(), live.segments())
        }
        live.streamBegin(language, null, false, abort)
        try {
            live.streamFeed(samples, length)
            live.streamFinalize()
        } catch (e: Throwable) {
            live.streamReset()
            throw e
        }
        val text = live.fullText ?: live.committedText.orEmpty()
        onPartial?.invoke(text)
        return RunResult(text, live.segments())
    }

    @Synchronized
    override fun releaseModel() {
        TranscribeVad.release()
        session?.release()
        session = null
        model?.release()
        model = null
        loadedPath = null
    }

    override fun capabilities(): TranscriberCapabilities {
        val activeFile = ModelManager.activeFileName(appContext)
        val info = loadedModelInfo()
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = ENGINE_ID
        capabilities.engineVersion = appVersion()
        capabilities.supportedLanguages = info?.languages?.takeIf { it.isNotEmpty() }?.toTypedArray()
        capabilities.autoDetectLanguage = info?.languageDetect ?: true
        capabilities.cancellable = true
        capabilities.modelReady = activeFile != null
        capabilities.streaming = true
        return capabilities
    }

    private fun loadedModelInfo(): TranscribeModelInfo? = try {
        model?.info
    } catch (ignore: Throwable) {
        null
    }

    private fun appVersion(): String? = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    } catch (ignore: Exception) {
        null
    }

    @Synchronized
    private fun session(): TranscribeSession {
        session?.let { return it }
        val loaded = model()
        return loaded.openSession(CpuConfig.preferredThreadCount).also { session = it }
    }

    @Synchronized
    private fun model(): TranscribeModel {
        val key = ModelManager.activeModelKey(appContext) ?: throw ModelNotAvailableException()
        model?.let {
            if (loadedPath == key) {
                return it
            }
            releaseModel()
        }
        val active = ModelManager.openActiveModel(appContext) ?: throw ModelNotAvailableException()
        val loaded = TranscribeModel.load(active.path)
        model = loaded
        loadedPath = active.key
        return loaded
    }

    // internal only so the range re-base stays unit-testable without a loaded model.
    internal data class RunResult(val text: String, val segments: List<TranscribeSegmentData>)

    // internal only so the range re-base stays unit-testable without a loaded model.
    internal class RunAudio private constructor(
        private val samples: ByteBuffer,
        val sampleCount: Int,
        private val baseMs: Long,
        private val spanStarts: IntArray?,
        private val spanOffsets: IntArray?
    ) {

        fun slice(offset: Int, length: Int): ByteBuffer {
            val view = samples.duplicate().order(ByteOrder.nativeOrder())
            view.position(offset * 4)
            view.limit((offset + length) * 4)
            return view.slice().order(ByteOrder.nativeOrder())
        }

        fun segmentsOf(
            result: RunResult,
            offset: Int,
            length: Int,
            replace: (String) -> String
        ): List<TranscriptSegment> {
            if (result.text.isBlank()) {
                return emptyList()
            }
            val segments = result.segments.filter { it.text.isNotBlank() }
            if (segments.isEmpty()) {
                return listOf(TranscriptSegment(originalMs(offset), originalMs(offset + length), replace(result.text)))
            }
            return segments.map { segment ->
                val base = offset * 1000L / SAMPLE_RATE
                TranscriptSegment(
                    originalMsAt(base + segment.startMs),
                    originalMsAt(base + segment.endMs),
                    replace(segment.text),
                    words = segment.words.map { word ->
                        TranscriptWord(
                            originalMsAt(base + word.startMs),
                            originalMsAt(base + word.endMs),
                            replace(word.text)
                        )
                    }
                )
            }
        }

        private fun originalMs(sample: Int): Long = originalMsAt(sample.toLong() * 1000L / SAMPLE_RATE)

        // Every segment and word timestamp passes through here exactly once. baseMs — where the
        // decoded buffer begins in the original file — is added here and nowhere else, after the
        // VAD spans have folded buffer time back to decoded-file time.
        private fun originalMsAt(ms: Long): Long {
            val starts = spanStarts
            val offsets = spanOffsets
            if (starts == null || offsets == null) {
                return baseMs + ms
            }
            val sample = (ms * SAMPLE_RATE / 1000).coerceIn(0, sampleCount.toLong())
            var span = 0
            for (i in starts.indices) {
                if (starts[i] <= sample) {
                    span = i
                } else {
                    break
                }
            }
            return baseMs + offsets[span] * 1000L / SAMPLE_RATE + (sample - starts[span]) * 1000L / SAMPLE_RATE
        }

        companion object {
            fun whole(samples: ByteBuffer, sampleCount: Int, baseMs: Long = 0L) =
                RunAudio(samples, sampleCount, baseMs, null, null)

            fun compact(
                samples: ByteBuffer,
                sampleCount: Int,
                spans: List<VadSpan>,
                baseMs: Long = 0L
            ): RunAudio? {
                val source = samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                val bounds = ArrayList<IntArray>(spans.size)
                var kept = 0L
                for (span in spans) {
                    val from = (span.startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, sampleCount)
                    val to = (span.endMs * SAMPLE_RATE / 1000).toInt().coerceIn(from, sampleCount)
                    if (to > from) {
                        bounds.add(intArrayOf(from, to))
                        kept += to - from
                    }
                }
                if (bounds.isEmpty() || kept >= sampleCount * 0.9) {
                    return null
                }
                val packed = ByteBuffer.allocateDirect((kept * 4).toInt()).order(ByteOrder.nativeOrder())
                val target = packed.asFloatBuffer()
                val starts = IntArray(bounds.size)
                val offsets = IntArray(bounds.size)
                var at = 0
                for (i in bounds.indices) {
                    val from = bounds[i][0]
                    val to = bounds[i][1]
                    starts[i] = at
                    offsets[i] = from
                    target.put(at, source, from, to - from)
                    at += to - from
                }
                return RunAudio(packed, at, baseMs, starts, offsets)
            }
        }
    }

    private companion object {
        const val ENGINE_ID = "transcribe.cpp"

        const val SAMPLE_RATE = 16000

        const val WINDOW_SAMPLES = SAMPLE_RATE * 30

        const val MIN_TRANSCRIBABLE_WINDOW_SAMPLES = SAMPLE_RATE
    }
}
