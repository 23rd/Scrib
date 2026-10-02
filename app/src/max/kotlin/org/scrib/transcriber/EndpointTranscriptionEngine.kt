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
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class EndpointTranscriptionEngine(
    private val appContext: Context,
    private val settings: EndpointSettings
) : TranscriptionEngine {

    fun matches(other: EndpointSettings): Boolean =
        other.apiKey == settings.apiKey && other.model == settings.model &&
            other.normalizedBaseUrl == settings.normalizedBaseUrl

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
            val text = post(wav, language, null, prompt)
            TranscribedSegment(Dictionary.applyReplacements(appContext, text), language)
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
            val text = post(wav, languageHint, cancellation)
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

            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return emptyList()
            }
            return listOf(
                TranscriptSegment(
                    startMs = 0L,
                    endMs = audioDurationMs,
                    text = trimmed,
                    startsParagraph = true
                )
            )
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

    private fun post(
        wav: ByteArray,
        languageHint: String?,
        cancellation: CancellationToken?,
        prompt: String? = null
    ): String {
        val boundary = "----ScribBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val connection = open()

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setChunkedStreamingMode(0)

            connection.outputStream.use { out ->
                writeField(out, boundary, "model", settings.model)
                if (!languageHint.isNullOrEmpty()) {
                    writeField(out, boundary, "language", languageHint)
                }
                if (!prompt.isNullOrBlank()) {
                    writeField(out, boundary, "prompt", prompt)
                }
                writeFile(out, boundary, "file", wav)
                out.write("--$boundary--\r\n".toByteArray())
                out.flush()
            }

            if (cancellation?.isCancelled == true) {
                throw CancelledException()
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                throw failure(connection, status)
            }

            val body = BufferedInputStream(connection.inputStream).use { it.readBytes() }
            return parse(body)
        } catch (e: CancelledException) {
            throw e
        } catch (e: EndpointException) {
            throw e
        } catch (e: IOException) {
            throw EndpointException("Cannot reach ${settings.host}: ${e.message ?: "connection failed"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(): HttpURLConnection {
        val connection = URL(settings.transcriptionsUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun failure(connection: HttpURLConnection, status: Int): EndpointException {
        val body = runCatching {
            connection.errorStream?.use { String(it.readBytes()) } ?: ""
        }.getOrDefault("")
        val detail = runCatching {
            JSONObject(body).optString("error").ifEmpty { JSONObject(body).optString("detail") }
        }.getOrDefault("")
        val shown = detail.ifEmpty { body.take(ERROR_BODY_CHARS).trim() }
        val message = when (status) {
            401, 403 -> "The server rejected the API key (HTTP $status). $shown"
            404 -> "No transcriptions endpoint at ${settings.transcriptionsUrl} (HTTP 404)."
            413 -> "The recording is too large for this server (HTTP 413)."
            in 500..599 -> "The server failed (HTTP $status). $shown"
            else -> "The server returned HTTP $status. $shown"
        }
        return EndpointException(message)
    }

    private fun preview(body: ByteArray): String =
        String(body, 0, minOf(ERROR_BODY_CHARS, body.size)).trim()

    private fun parse(body: ByteArray): String {
        val text = try {
            JSONObject(String(body)).optString("text")
        } catch (e: Exception) {
            throw EndpointException("Unreadable answer: ${preview(body)}")
        }
        if (text.isEmpty() && body.isNotEmpty()) {
            throw EndpointException("The server returned no text: ${preview(body)}")
        }
        return text
    }

    private fun writeField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                .toByteArray()
        )
    }

    private fun writeFile(out: java.io.OutputStream, boundary: String, name: String, wav: ByteArray) {
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        out.write(wav)
        out.write("\r\n".toByteArray())
    }

    private companion object {
        const val SAMPLE_RATE = 16000

        const val ENGINE_ID = "openai-compatible-endpoint"
        const val ENGINE_VERSION = "1"

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 600_000

        const val ERROR_BODY_CHARS = 200

        const val USER_AGENT = "Scrib"

    }
}

class EndpointException(message: String) : RuntimeException(message)
