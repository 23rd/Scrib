package org.scrib.transcriber

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionRequest

class EndpointTranscriptionEngine(
    private val appContext: Context,
    private val settings: EndpointSettings
) : TranscriptionEngine {

    fun matches(other: EndpointSettings): Boolean =
        other.apiKey == settings.apiKey && other.model == settings.model &&
            other.provider == settings.provider && other.normalizedBaseUrl == settings.normalizedBaseUrl

    private val transport: EndpointTransport get() = settings.transport

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
        val language = request?.languageHint?.takeIf { it.isNotEmpty() }
        return AudioStream(request, callback) { samples, sampleCount, _, prompt, _ ->
            val wav = WavWriter.encode(samples, sampleCount, SAMPLE_RATE)
            val transcript = transport.transcribe(settings, wav, language, prompt, null)
            TranscribedSegment(Dictionary.applyReplacements(appContext, transcript.text), language)
        }
    }

    override fun transcribeToSegments(
        audio: ParcelFileDescriptor,
        languageHint: String?,
        cancellation: CancellationToken,
        onProgress: (Int) -> Unit,
        onPartial: (String) -> Unit,
        onMetrics: (TranscriptionMetrics) -> Unit
    ): List<TranscriptSegment> {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val pcm = try {
                AudioDecoder.decodeToPcm16kMono(audio, cancellation)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Throwable) {
                throw DecodeException(e.message ?: "Cannot decode the audio")
            }
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            if (pcm.sampleCount == 0) {
                throw DecodeException("No audio decoded")
            }

            val audioDurationMs = pcm.sampleCount.toLong() * 1000L / SAMPLE_RATE
            val decodeMs = SystemClock.elapsedRealtime() - startedAt
            onMetrics(TranscriptionMetrics(audioDurationMs = audioDurationMs, decodeMs = decodeMs))
            onProgress(0)

            val wav = try {
                WavWriter.encode(pcm.samples, pcm.sampleCount, SAMPLE_RATE)
            } catch (e: IllegalArgumentException) {
                throw DecodeException(e.message ?: "Recording is too long")
            }
            if (cancellation.isCancelled) {
                throw CancelledException()
            }

            onProgress(20)
            val transcript = transport.transcribe(settings, wav, languageHint, null, cancellation)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            onProgress(100)
            onMetrics(
                TranscriptionMetrics(
                    audioDurationMs = audioDurationMs,
                    decodeMs = decodeMs,
                    inferenceMs = elapsedMs - decodeMs,
                    totalMs = elapsedMs
                )
            )

            val trimmed = transcript.text.trim()
            if (trimmed.isEmpty()) {
                return emptyList()
            }
            return segments(transcript, audioDurationMs)
        } finally {
            try {
                audio.close()
            } catch (ignore: Exception) {
            }
        }
    }

    override fun releaseModel() {
    }

    override fun capabilities(): TranscriberCapabilities {
        val capabilities = TranscriberCapabilities()
        capabilities.contractVersion = TranscriptionEngine.CONTRACT_VERSION
        capabilities.engineId = ENGINE_ID
        capabilities.engineVersion = ENGINE_VERSION
        capabilities.streaming = true
        return capabilities
    }

    private fun segments(transcript: Transcript, audioDurationMs: Long): List<TranscriptSegment> {
        val text = transcript.text.trim()
        val words = transcript.words
        if (words.isEmpty()) {
            return listOf(
                TranscriptSegment(
                    startMs = 0L,
                    endMs = audioDurationMs,
                    text = text,
                    startsParagraph = true
                )
            )
        }

        val segments = ArrayList<TranscriptSegment>()
        var lineStart = -1L
        var lineEnd = -1L
        var builder = StringBuilder()
        var previousEnd = -1L

        fun flush() {
            if (builder.isEmpty()) {
                return
            }
            segments.add(
                TranscriptSegment(
                    startMs = lineStart,
                    endMs = lineEnd,
                    text = builder.toString(),
                    startsParagraph = previousEnd < 0L
                )
            )
            builder = StringBuilder()
            previousEnd = lineEnd
        }

        for (word in words) {
            val gap = if (previousEnd < 0L) 0L else word.startMs - previousEnd
            val tooLong = builder.isNotEmpty() && word.startMs - lineStart >= MAX_SEGMENT_MS
            val wouldRunOver = builder.isNotEmpty() && builder.length + word.word.length + 1 > MAX_LINE_CHARS
            if (builder.isNotEmpty() && (gap >= SEGMENT_GAP_MS || tooLong || wouldRunOver)) {
                flush()
            }
            if (builder.isEmpty()) {
                lineStart = word.startMs
            }
            if (builder.isNotEmpty()) {
                builder.append(' ')
            }
            builder.append(word.word)
            lineEnd = word.endMs
        }
        flush()

        return segments.ifEmpty {
            listOf(TranscriptSegment(0L, audioDurationMs, text, startsParagraph = true))
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000

        const val ENGINE_ID = "openai-compatible-endpoint"
        const val ENGINE_VERSION = "1"


        const val READ_TIMEOUT_MS = 600_000

        const val SEGMENT_GAP_MS = 700L

        const val MAX_LINE_CHARS = 84

        const val MAX_SEGMENT_MS = 6000L

    }
}

class EndpointException(message: String) : RuntimeException(message)
