package org.scrib.transcriber

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AudioDecoder {

    private const val TARGET_RATE = 16000

    // Decodes whatever the platform supports (Opus/OGG voice notes, AAC/MP4 round videos) from the
    // file descriptor into 16 kHz mono float32 PCM. Uses async MediaCodec so decoding runs at the
    // codec's full speed instead of polling with per-packet timeouts.
    fun decodeToPcm16kMono(pfd: ParcelFileDescriptor, cancellation: CancellationToken? = null): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val handlerThread = HandlerThread("fgt-decode")
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

            val pcm = ByteArrayOutputStream()
            val done = CountDownLatch(1)
            val rate = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_RATE
            )
            val channels = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            )

            handlerThread.start()
            codec = MediaCodec.createDecoderByType(mime)
            codec.setCallback(object : MediaCodec.Callback() {
                private var inputDone = false

                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    if (inputDone || cancellation?.isCancelled == true) {
                        return
                    }
                    val buffer = try {
                        mc.getInputBuffer(index)
                    } catch (e: Exception) {
                        null
                    } ?: return
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        mc.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (cancellation?.isCancelled == true) {
                        mc.releaseOutputBuffer(index, false)
                        done.countDown()
                        return
                    }
                    if (info.size > 0) {
                        val buffer = mc.getOutputBuffer(index)
                        if (buffer != null) {
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk, 0, info.size)
                            pcm.write(chunk)
                        }
                    }
                    mc.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        done.countDown()
                    }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, newFormat: MediaFormat) {
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate[0] = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels[0] = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    done.countDown()
                }
            }, Handler(handlerThread.looper))
            codec.configure(format, null, null, 0)
            codec.start()

            cancellation?.onCancel { done.countDown() }
            done.await(120, TimeUnit.SECONDS)

            return toMono16k(pcm.toByteArray(), rate[0], channels[0].coerceAtLeast(1))
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
            handlerThread.quitSafely()
        }
    }

    private fun toMono16k(bytes: ByteArray, sampleRate: Int, channels: Int): FloatArray {
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuffer.limit())
        shortBuffer.get(samples)
        val frames = samples.size / channels
        val mono = FloatArray(frames)
        if (channels == 1) {
            for (i in 0 until frames) {
                mono[i] = samples[i] / 32768f
            }
        } else {
            for (i in 0 until frames) {
                var acc = 0
                for (c in 0 until channels) {
                    acc += samples[i * channels + c]
                }
                mono[i] = (acc.toFloat() / channels) / 32768f
            }
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
