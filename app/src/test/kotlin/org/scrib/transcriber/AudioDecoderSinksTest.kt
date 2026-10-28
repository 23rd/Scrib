package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteOrder

class AudioDecoderSinksTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun inMemorySinkGrowsBeyondItsInitialCapacity() {
        val sink = AudioDecoder.InMemorySampleSink(1024)
        val samples = FloatArray(5000) { it.toFloat() }
        sink.add(samples, samples.size)

        assertEquals(samples.size, sink.sampleCount())
        val audio = sink.result()
        val buffer = audio.samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in samples.indices) {
            assertEquals(samples[i], buffer.get(i), 1e-6f)
        }
    }

    @Test
    fun mappedSinkGrowsAcrossRemapBoundariesAndBacksTheResultByAFile() {
        val dir = temp.newFolder("scratch")
        val sink = AudioDecoder.MappedSampleSink(dir, 1024)
        // Each chunk is small on purpose: the sink must remap several times on the way to 50k.
        val chunk = FloatArray(700) { it.toFloat() }
        var written = 0
        for (i in 0 until 72) {
            chunk.indices.forEach { chunk[it] = (written + it).toFloat() }
            sink.add(chunk, chunk.size)
            written += chunk.size
        }

        assertEquals(written, sink.sampleCount())
        val audio = sink.result()
        assertTrue(audio.samples.isDirect)
        assertEquals(written, audio.sampleCount)
        val buffer = audio.samples.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in intArrayOf(0, 699, 700, 42_000, written - 1)) {
            assertEquals(i.toFloat(), buffer.get(i), 1e-6f)
        }

        val scratch = dir.listFiles()!!.single { it.name.startsWith("decode-") }
        assertTrue(scratch.exists())
        audio.close()
        assertFalse("Closing the decoded audio deletes the scratch file", scratch.exists())
    }

    @Test
    fun mappedSinkDiscardDeletesTheScratchFile() {
        val dir = temp.newFolder("scratch")
        val sink = AudioDecoder.MappedSampleSink(dir, 1024)
        sink.add(FloatArray(100), 100)
        val scratch = dir.listFiles()!!.single { it.name.startsWith("decode-") }
        sink.discard()
        assertFalse(scratch.exists())
    }
}
