package org.scrib.transcriber

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {

    private const val HEADER_BYTES = 44

    private const val BITS_PER_SAMPLE = 16

    fun encode(samples: ByteBuffer, sampleCount: Int, sampleRate: Int): ByteArray {
        val dataBytes = sampleCount * (BITS_PER_SAMPLE / 8)
        require(dataBytes <= MAX_DATA_BYTES) { "Recording is too long to send" }

        val out = ByteArrayOutputStream(HEADER_BYTES + dataBytes)
        out.write("RIFF".toByteArray())
        out.writeIntLe(HEADER_BYTES - 8 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeIntLe(16)
        out.writeShortLe(1)                       // PCM
        out.writeShortLe(1)                       // mono
        out.writeIntLe(sampleRate)
        out.writeIntLe(sampleRate * (BITS_PER_SAMPLE / 8))   // byte rate
        out.writeShortLe(BITS_PER_SAMPLE / 8)                // block align
        out.writeShortLe(BITS_PER_SAMPLE)
        out.write("data".toByteArray())
        out.writeIntLe(dataBytes)

        val floats = samples.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val read = floats.duplicate()
        read.position(0)
        read.limit(sampleCount)
        val shorts = ShortArray(sampleCount)
        while (read.hasRemaining()) {
            shorts[read.position()] = (read.get() * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val bytes = ByteArray(dataBytes)
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) {
            view.putShort(i * 2, shorts[i])
        }
        out.writeBytes(bytes)
        return out.toByteArray()
    }

    fun silence(sampleRate: Int, millis: Int): ByteArray {
        val count = sampleRate * millis / 1000
        return encode(allocateZeros(count), count, sampleRate)
    }

    private fun allocateZeros(count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        while (floats.hasRemaining()) {
            floats.put(0f)
        }
        return buffer
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
        write(value shr 16 and 0xff)
        write(value shr 24 and 0xff)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
    }

    private const val MAX_DATA_BYTES = 25L * 1024L * 1024L
}
