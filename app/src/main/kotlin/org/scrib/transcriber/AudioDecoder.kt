package org.scrib.transcriber

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {

    private const val TARGET_RATE = 16000

    // Decodes whatever the platform supports (Opus/OGG voice notes, AAC/MP4 round videos) from the
    // file descriptor into 16 kHz mono float32 PCM, the format whisper expects.
    fun decodeToPcm16kMono(pfd: ParcelFileDescriptor): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(pfd.fileDescriptor)

            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    inputFormat = format
                    break
                }
            }
            val format = inputFormat ?: throw RuntimeException("No audio track found")
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels =
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk, 0, info.size)
                        pcm.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            return toMono16k(pcm.toByteArray(), sampleRate, channels.coerceAtLeast(1))
        } finally {
            try {
                codec?.stop()
            } catch (ignore: Exception) {
            }
            try {
                codec?.release()
            } catch (ignore: Exception) {
            }
            extractor.release()
        }
    }

    private fun toMono16k(bytes: ByteArray, sampleRate: Int, channels: Int): FloatArray {
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = shorts.limit() / channels
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                acc += shorts.get(i * channels + c).toInt()
            }
            mono[i] = (acc.toFloat() / channels) / 32768f
        }
        return resample(mono, sampleRate, TARGET_RATE)
    }

    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) {
            return input
        }
        val outLen = (input.size.toLong() * dstRate / srcRate).toInt()
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = input[idx]
            val b = if (idx + 1 < input.size) input[idx + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
