package org.scrib.transcriber

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class ResumableModelDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(8192) { (it % 253).toByte() }
    private val updated = ByteArray(8192) { ((it + 12) % 253).toByte() }
    private val offset = 2048
    private val tag = "\"v1\""
    private val nextTag = "\"v2\""

    @Test fun retryAppendsExactSuffixAfterSocketClosesEarly() = scripted(
        cut(), suffix()
    ) { server, dest ->
        interrupt(server, dest)
        assertEquals(offset.toLong(), filePart(dest).length())
        val progress = mutableListOf<Pair<Long, Long>>()
        retry(server, dest, progress = { done, total -> progress.add(done to total) })
        assertEquals(offset.toLong() to payload.size.toLong(), progress.first())
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        assertRetryHeaders(server)
        assertComplete(dest, payload)
    }

    @Test fun serverIgnoringRangeReusesFullResponseFromStart() = scripted(
        cut(), full(payload, tag)
    ) { server, dest ->
        interrupt(server, dest)
        retry(server, dest)
        assertRetryHeaders(server)
        assertComplete(dest, payload)
    }

    @Test fun changedObjectCanReplacePrefixWithFullResponse() = scripted(
        cut(), full(updated, nextTag)
    ) { server, dest ->
        interrupt(server, dest)
        retry(server, dest)
        assertRetryHeaders(server)
        assertComplete(dest, updated)
    }

    @Test fun wrongTagOrInvalidRangeMayNeverAppendAndMustStartOver() {
        for (invalid in listOf(suffix(nextTag), suffix(start = offset - 1), Response("416 Range Not Satisfiable"))) {
            scripted(cut(), invalid, full(updated, nextTag)) { server, dest ->
                interrupt(server, dest)
                retry(server, dest)
                assertRetryHeaders(server)
                assertNull(server.requests.remove()["range"])
                assertComplete(dest, updated)
            }
        }
    }

    @Test fun weakOrMissingValidatorDoesNotSavePrefix() {
        for (weak in listOf("W/$tag", null)) {
            scripted(cut(weak), full(payload, tag)) { server, dest ->
                interrupt(server, dest)
                assertFalse(filePart(dest).exists())
                retry(server, dest)
                assertNull(server.requests.remove()["range"])
                assertNull(server.requests.remove()["range"])
                assertComplete(dest, payload)
            }
        }
    }

    @Test fun sameLengthCorruptedDiskPrefixIsNotReused() = scripted(
        cut(), full(payload, tag)
    ) { server, dest ->
        interrupt(server, dest)
        filePart(dest).writeBytes(ByteArray(offset) { 42 })
        retry(server, dest)
        assertNull(server.requests.remove()["range"])
        assertNull(server.requests.remove()["range"])
        assertComplete(dest, payload)
    }

    @Test fun differentSourceUrlWithSameFileNameAndTagDoesNotReusePrefix() = scripted(
        cut(), full(updated, tag)
    ) { server, dest ->
        interrupt(server, dest)
        ResumableModelDownload.download(server.url + "?other", dest, 64, { _, _ -> }, { false })
        assertNull(server.requests.remove()["range"])
        assertNull(server.requests.remove()["range"])
        assertComplete(dest, updated)
    }

    @Test fun changedRedirectTargetMayNotReuseSameTagAndRange() = scripted(
        redirect("/release?a"), cut(),
        redirect("/release?b"), suffix(),
        redirect("/release?b"), full(updated, tag)
    ) { server, dest ->
        interrupt(server, dest)
        retry(server, dest)
        val hits = List(6) { server.requests.remove() }
        assertNull(hits[0]["range"])
        assertNull(hits[1]["range"])
        assertEquals("bytes=$offset-", hits[2]["range"])
        assertEquals("bytes=$offset-", hits[3]["range"])
        assertNull(hits[4]["range"])
        assertNull(hits[5]["range"])
        assertComplete(dest, updated)
    }

    @Test fun transferWithNoLengthOrChunkFramingCannotInstall() = scripted(
        Response("200 OK", payload, declared = null, tag = tag)
    ) { server, dest ->
        interrupt(server, dest)
        assertFalse(filePart(dest).exists())
        assertFalse(metadata(dest).exists())
    }

    @Test fun interruptedSuffixCanResumeAgainFromNewExactOffset() = scripted(
        cut(),
        suffix().copy(bytes = payload.copyOfRange(offset, offset * 2)),
        suffix(start = offset * 2)
    ) { server, dest ->
        interrupt(server, dest)
        interrupt(server, dest)
        assertEquals((offset * 2).toLong(), filePart(dest).length())
        retry(server, dest)
        assertRetryHeaders(server)
        assertEquals("bytes=${offset * 2}-", server.requests.remove()["range"])
        assertComplete(dest, payload)
    }

    @Test fun explicitCancellationDiscardsPrefixAndDoesNotAutoRetry() = scripted(
        cut(), suffix(), full(payload, tag)
    ) { server, dest ->
        interrupt(server, dest)
        var cancelled = false
        assertThrows(ModelManager.CancelledDownloadException::class.java) {
            retry(server, dest, { done, _ -> if (done > offset) cancelled = true }, { cancelled })
        }
        assertRetryHeaders(server)
        assertTrue(server.requests.isEmpty())
        assertFalse(dest.exists())
        assertFalse(filePart(dest).exists())
        assertFalse(metadata(dest).exists())
        retry(server, dest)
        assertNull(server.requests.remove()["range"])
        assertComplete(dest, payload)
    }

    @Test fun initialPartialResponseCannotInstallTruncatedFile() = scripted(suffix()) { server, dest ->
        interrupt(server, dest)
        assertFalse(filePart(dest).exists())
        assertFalse(metadata(dest).exists())
    }

    private fun interrupt(server: Fixture, dest: File) {
        assertThrows(IOException::class.java) { retry(server, dest) }
        assertFalse(dest.exists())
    }

    private fun retry(
        server: Fixture, dest: File,
        progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }
    ) = ResumableModelDownload.download(server.url, dest, 64, progress, cancelled)

    private fun assertRetryHeaders(server: Fixture) {
        assertNull(server.requests.remove()["range"])
        val retry = server.requests.remove()
        assertEquals("bytes=$offset-", retry["range"])
        assertEquals(tag, retry["if-range"])
        assertEquals("identity", retry["accept-encoding"])
    }

    private fun assertComplete(dest: File, expected: ByteArray) {
        assertArrayEquals(expected, dest.readBytes())
        assertFalse(filePart(dest).exists())
        assertFalse(metadata(dest).exists())
    }

    private fun filePart(dest: File) = File(dest.path + ".download-part")
    private fun metadata(dest: File) = File(dest.path + ".download-meta")

    private fun cut(tag: String? = this.tag): Response =
        full(payload, tag).copy(bytes = payload.copyOf(offset))

    private fun full(bytes: ByteArray, tag: String?): Response =
        Response("200 OK", bytes, bytes.size, tag)

    private fun redirect(location: String): Response = Response("302 Found", location = location)

    private fun suffix(tag: String = this.tag, start: Int = offset): Response =
        Response("206 Partial Content", payload.copyOfRange(start, payload.size), payload.size - start,
            tag, "bytes $start-${payload.lastIndex}/${payload.size}")

    private fun scripted(vararg responses: Response, action: (Fixture, File) -> Unit) {
        Fixture(responses).use { fixture -> action(fixture, File(folder.newFolder(), "model.bin")) }
    }

    private data class Response(
        val status: String,
        val bytes: ByteArray = byteArrayOf(),
        val declared: Int? = 0,
        val tag: String? = null,
        val range: String? = null,
        val location: String? = null
    )

    private class Fixture(responses: Array<out Response>) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/model.bin"
        val requests = LinkedBlockingQueue<Map<String, String>>()
        @Volatile private var failure: Exception? = null
        private val worker = thread(isDaemon = true) {
            try {
                for (response in responses) server.accept().use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(input.readLine().startsWith("GET "))
                    val headers = generateSequence { input.readLine()?.takeUnless { it.isEmpty() } }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    requests.add(headers)
                    val header = buildString {
                        append("HTTP/1.1 ${response.status}\r\nConnection: close\r\n")
                        response.declared?.let { append("Content-Length: $it\r\n") }
                        response.location?.let { append("Location: $it\r\n") }
                        response.tag?.let { append("ETag: $it\r\n") }
                        response.range?.let { append("Content-Range: $it\r\n") }
                        append("\r\n")
                    }
                    client.getOutputStream().apply {
                        write(header.toByteArray(Charsets.US_ASCII))
                        write(response.bytes)
                        flush()
                    }
                    // Closing with fewer bytes than declared simulates a real mid-stream interruption.
                }
            } catch (e: IOException) {
                if (!server.isClosed) failure = e
            }
        }

        override fun close() {
            server.close()
            worker.join(5_000)
            failure?.let { throw it }
        }
    }
}
