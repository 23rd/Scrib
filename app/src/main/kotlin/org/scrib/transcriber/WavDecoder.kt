package org.scrib.transcriber

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavDecoder {

    fun decode(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channel = buffer.getShort(22).toInt()
        buffer.position(44)
        val shortBuffer = buffer.asShortBuffer()
        val shortArray = ShortArray(shortBuffer.limit())
        shortBuffer.get(shortArray)
        return FloatArray(shortArray.size / channel) { index ->
            when (channel) {
                1 -> (shortArray[index] / 32767.0f).coerceIn(-1f, 1f)
                else -> ((shortArray[2 * index] + shortArray[2 * index + 1]) / 32767.0f / 2.0f).coerceIn(-1f, 1f)
            }
        }
    }
}
