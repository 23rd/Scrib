package org.scrib.transcriber

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OpenAiTransport : EndpointTransport {

    override val endpointUrl: String get() = "{base}/audio/transcriptions"

    override fun modelOptions(settings: EndpointSettings): List<String> = emptyList()

    override fun transcribe(
        settings: EndpointSettings,
        wav: ByteArray,
        languageHint: String?,
        prompt: String?,
        cancellation: CancellationToken?
    ): Transcript {
        val boundary = "----ScribBoundary${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val url = settings.transcriptionsUrl
        val connection = EndpointHttp.open(url, settings.apiKey)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
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
                writeFile(out, boundary, wav)
                out.write("--$boundary--\r\n".toByteArray())
                out.flush()
            }

            if (cancellation?.isCancelled == true) {
                throw CancelledException()
            }

            val status = connection.responseCode
            val body = EndpointHttp.body(connection, status)
            if (status !in 200..299) {
                throw EndpointException(EndpointHttp.openAiErrors(body, status, url))
            }
            val json = EndpointHttp.json(body, url)
            return Transcript(text = json.optString("text"))
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

    private fun writeField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                .toByteArray()
        )
    }

    private fun writeFile(out: java.io.OutputStream, boundary: String, wav: ByteArray) {
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        out.write(wav)
        out.write("\r\n".toByteArray())
    }
}

object EndpointHttp {

    const val CONNECT_TIMEOUT_MS = 20_000

    const val READ_TIMEOUT_MS = 600_000

    const val USER_AGENT = "Scrib"

    const val ERROR_BODY_CHARS = 200

    fun open(url: String, apiKey: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    fun body(connection: HttpURLConnection, status: Int): ByteArray {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.let { BufferedInputStream(it).use { input -> input.readBytes() } } ?: ByteArray(0)
    }

    fun json(body: ByteArray, url: String): JSONObject = try {
        JSONObject(String(body))
    } catch (e: Exception) {
        throw EndpointException("Unreadable answer from $url: ${preview(body)}")
    }

    fun preview(body: ByteArray): String =
        String(body, 0, minOf(ERROR_BODY_CHARS, body.size)).trim()

    fun openAiErrors(body: ByteArray, status: Int, url: String): String {
        val detail = runCatching {
            val json = JSONObject(String(body))
            when {
                json.optJSONObject("error") != null -> json.getJSONObject("error").optString("message")
                json.has("detail") -> json.optString("detail")
                else -> ""
            }
        }.getOrDefault("")
        val shown = detail.ifEmpty { preview(body) }
        return when (status) {
            401, 403 -> "The server rejected the API key (HTTP $status). $shown"
            404 -> "No transcriptions endpoint at $url (HTTP 404)."
            413 -> "The recording is too large for this server (HTTP 413)."
            in 500..599 -> "The server failed (HTTP $status). $shown"
            else -> "The server returned HTTP $status. $shown"
        }
    }

    fun cloudflareErrors(json: JSONObject, status: Int, url: String): String {
        val messages = ArrayList<String>()
        val errors = json.optJSONArray("errors")
        for (i in 0 until (errors?.length() ?: 0)) {
            val entry = errors!!.opt(i)
            val message = when (entry) {
                is JSONObject -> entry.optString("message")
                is String -> entry
                else -> ""
            }
            if (message.isNotEmpty()) {
                messages.add(message)
            }
        }
        val detail = messages.joinToString("; ")
        return when {
            detail.contains("authentication", ignoreCase = true) ->
                "Cloudflare rejected the API token. It needs the Workers AI permission."
            detail.isNotEmpty() -> "Cloudflare: $detail"
            else -> "Cloudflare returned HTTP $status from $url."
        }
    }
}
