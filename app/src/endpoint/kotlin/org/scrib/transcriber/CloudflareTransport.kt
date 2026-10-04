package org.scrib.transcriber

interface EndpointTransport {

    val endpointUrl: String

    fun modelOptions(settings: EndpointSettings): List<String>

    fun transcribe(
        settings: EndpointSettings,
        wav: ByteArray,
        languageHint: String?,
        prompt: String?,
        cancellation: CancellationToken?
    ): Transcript
}

data class Transcript(
    val text: String,
    val words: List<WordTiming> = emptyList()
)

data class WordTiming(val word: String, val startMs: Long, val endMs: Long)

val CLOUDFLARE_WHISPER_MODELS = listOf(
    "@cf/openai/whisper",
    "@cf/openai/whisper-large-v3-turbo"
)

class CloudflareTransport : EndpointTransport {

    override val endpointUrl: String
        get() = "https://api.cloudflare.com/client/v4/accounts/{account}/ai/run/{model}"

    override fun modelOptions(settings: EndpointSettings): List<String> = CLOUDFLARE_WHISPER_MODELS

    override fun transcribe(
        settings: EndpointSettings,
        wav: ByteArray,
        languageHint: String?,
        prompt: String?,
        cancellation: CancellationToken?
    ): Transcript {
        val account = settings.accountId
        val customBase = if (account.startsWith("http://") || account.startsWith("https://")) {
            account.trimEnd('/')
        } else {
            null
        }
        if (customBase == null && !ACCOUNT_ID.matches(account)) {
            throw EndpointException(
                "A Cloudflare account id is 32 hex characters, like ${settings.maskAccount()} — " +
                    "or give the address of your own Worker."
            )
        }
        val model = settings.model.ifBlank { CLOUDFLARE_WHISPER_MODELS.first() }
        val language = if (languageHint.isNullOrEmpty()) "" else
            "&language=" + java.net.URLEncoder.encode(languageHint, "UTF-8")
        val url = if (customBase != null) {
            "$customBase/client/v4/accounts/x/ai/run/$model$language"
        } else {
            "https://api.cloudflare.com/client/v4/accounts/$account/ai/run/$model$language"
        }

        val connection = EndpointHttp.open(url, settings.apiKey)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(wav.size)
            connection.outputStream.use { it.write(wav) }

            if (cancellation?.isCancelled == true) {
                throw CancelledException()
            }

            val status = connection.responseCode
            val body = EndpointHttp.body(connection, status)
            val json = EndpointHttp.json(body, url)

            if (!json.optBoolean("success", true)) {
                throw EndpointException(EndpointHttp.cloudflareErrors(json, status, url))
            }
            if (status !in 200..299) {
                throw EndpointException("Cloudflare returned HTTP $status: ${EndpointHttp.preview(body)}")
            }

            val result = json.optJSONObject("result")
                ?: throw EndpointException("Cloudflare sent no result: ${EndpointHttp.preview(body)}")
            return Transcript(
                text = result.optString("text"),
                words = parseWords(result.optJSONArray("words"))
            )
        } catch (e: CancelledException) {
            throw e
        } catch (e: EndpointException) {
            throw e
        } catch (e: Exception) {
            throw EndpointException("Cannot reach Cloudflare: ${e.message ?: "connection failed"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseWords(array: org.json.JSONArray?): List<WordTiming> {
        if (array == null) {
            return emptyList()
        }
        val words = ArrayList<WordTiming>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val word = entry.optString("word")
            if (word.isEmpty()) {
                continue
            }
            words.add(
                WordTiming(
                    word = word,
                    startMs = (entry.optDouble("start", 0.0) * 1000.0).toLong(),
                    endMs = (entry.optDouble("end", 0.0) * 1000.0).toLong()
                )
            )
        }
        return words
    }

    private companion object {
        val ACCOUNT_ID = Regex("[0-9a-fA-F]{32}")
    }
}
