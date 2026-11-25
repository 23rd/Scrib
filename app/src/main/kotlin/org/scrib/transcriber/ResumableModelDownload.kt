package org.scrib.transcriber

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

/** One user-requested transfer. Only a provably reusable prefix survives a transport failure. */
internal object ResumableModelDownload {
    private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    private val strongTag = Regex(""""[\x21\x23-\x7E]*"""")

    private data class Prefix(
        val source: String,
        val resource: String,
        val tag: String,
        val total: Long,
        val length: Long,
        val digest: String
    )
    private data class Body(val offset: Long, val total: Long)

    fun download(
        url: String,
        dest: File,
        minSize: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        dest.parentFile?.mkdirs()
        // Distinct from the staging file used when importing a model from local storage.
        val part = File(dest.path + ".download-part")
        val metadata = File(dest.path + ".download-meta")
        val source = sha256(url.toByteArray())
        fun discard() {
            // Remove the metadata first; an unpaired partial is never reused.
            metadata.delete()
            part.delete()
        }
        fun checkCancelled() {
            if (isCancelled()) throw ModelManager.CancelledDownloadException()
        }

        try {
            checkCancelled()
            var prefix = loadPrefix(metadata, part, source, minSize)
            checkCancelled()
            if (prefix == null) discard()
            while (true) {
                checkCancelled()
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                prefix?.let {
                    connection.setRequestProperty("Range", "bytes=${it.length}-")
                    connection.setRequestProperty("If-Range", it.tag)
                }
                try {
                    val code = connection.responseCode
                    checkCancelled()
                    // Include the final URL (even its query) in the validator's resource scope.
                    // Keep only its hash on disk; redirected/custom URLs can contain credentials.
                    val resource = sha256(connection.url.toExternalForm().toByteArray())
                    val tag = connection.getHeaderField("ETag")?.takeIf(strongTag::matches)
                    val identity = connection.getHeaderField("Content-Encoding")
                        ?.equals("identity", ignoreCase = true) ?: true
                    val length = connection.contentLengthLong
                    val body = when {
                        code == HttpURLConnection.HTTP_OK && identity -> {
                            discard()
                            val chunked = connection.getHeaderField("Transfer-Encoding")
                                ?.equals("chunked", ignoreCase = true) == true
                            if ((length >= 0 && length <= minSize) || (length < 0 && !chunked)) {
                                throw IOException("Model response has no valid length or framing")
                            }
                            Body(0, length)
                        }
                        code == HttpURLConnection.HTTP_PARTIAL && prefix != null && identity &&
                            prefix.resource == resource && prefix.tag == tag &&
                            matchesRange(connection, prefix, length) -> Body(prefix.length, prefix.total)
                        prefix != null && (code == HttpURLConnection.HTTP_PARTIAL || code == 412 || code == 416) -> {
                            // Doesn't describe the exact suffix, or server refused our Range.
                            // At most one new request without Range, never append to this response.
                            discard()
                            prefix = null
                            continue
                        }
                        else -> throw IOException("Unexpected model response: HTTP $code")
                    }

                    // From here onward, only a known transport interruption may save a new receipt.
                    metadata.delete()
                    var written = body.offset
                    try {
                        onProgress(written, body.total)
                        val input = try {
                            connection.inputStream
                        } catch (e: IOException) {
                            throw InterruptedTransfer(e)
                        }
                        input.use { inputStream ->
                            FileOutputStream(part, body.offset > 0).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkCancelled()
                                    val count = try {
                                        inputStream.read(buffer)
                                    } catch (e: IOException) {
                                        throw InterruptedTransfer(e)
                                    }
                                    if (count < 0) break
                                    checkCancelled()
                                    if (body.total >= 0 && count > body.total - written) {
                                        throw IOException("Model response exceeded its declared length")
                                    }
                                    output.write(buffer, 0, count)
                                    written += count
                                    onProgress(written, body.total)
                                }
                            }
                        }
                        checkCancelled()
                        if (body.total >= 0 && written != body.total) {
                            throw InterruptedTransfer(EOFException("Incomplete model response"))
                        }
                        if (written <= minSize || part.length() != written) {
                            throw IOException("Invalid downloaded model size")
                        }
                        // Same directory, atomic promotion only. Don't expose an incomplete model by copying.
                        checkCancelled()
                        if (!part.renameTo(dest)) throw IOException("Could not install downloaded model")
                        return
                    } catch (e: InterruptedTransfer) {
                        checkCancelled()
                        if (tag != null && written in 1 until body.total && part.length() == written) {
                            try {
                                savePrefix(metadata, Prefix(source, resource, tag, body.total, written, sha256(part)))
                                checkCancelled()
                            } catch (e: IOException) {
                                discard()
                            }
                        } else {
                            discard()
                        }
                        throw e.cause
                    } catch (e: Exception) {
                        discard()
                        throw e
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } finally {
            if (isCancelled()) discard()
        }
    }

    private class InterruptedTransfer(override val cause: IOException) : IOException(cause)

    private fun matchesRange(connection: HttpURLConnection, prefix: Prefix, length: Long): Boolean {
        val range = contentRange.matchEntire(connection.getHeaderField("Content-Range") ?: return false)
            ?: return false
        return range.groupValues[1].toLongOrNull() == prefix.length &&
            range.groupValues[2].toLongOrNull() == prefix.total - 1 &&
            range.groupValues[3].toLongOrNull() == prefix.total &&
            (length < 0 || length == prefix.total - prefix.length)
    }

    private fun loadPrefix(meta: File, part: File, source: String, minSize: Long): Prefix? {
        if (!meta.isFile || meta.length() !in 1..2048 || !part.isFile) return null
        return try {
            val values = Properties().apply { meta.inputStream().use { load(it) } }
            val prefix = Prefix(
                values.getProperty("source") ?: return null,
                values.getProperty("resource") ?: return null,
                values.getProperty("tag") ?: return null,
                values.getProperty("total")?.toLongOrNull() ?: return null,
                values.getProperty("length")?.toLongOrNull() ?: return null,
                values.getProperty("sha256") ?: return null
            )
            prefix.takeIf {
                it.source == source && strongTag.matches(it.tag) && it.total > minSize &&
                    it.length in 1 until it.total && it.length == part.length() && it.digest == sha256(part)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun savePrefix(meta: File, prefix: Prefix) {
        val values = Properties().apply {
            setProperty("source", prefix.source)
            setProperty("resource", prefix.resource)
            setProperty("tag", prefix.tag)
            setProperty("total", prefix.total.toString())
            setProperty("length", prefix.length.toString())
            setProperty("sha256", prefix.digest)
        }
        meta.outputStream().use { values.store(it, null) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
