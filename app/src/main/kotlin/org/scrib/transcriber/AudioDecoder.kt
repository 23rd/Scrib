package org.scrib.transcriber

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// 16 kHz mono float32 PCM in a direct buffer, ready to hand to native code. The samples live
// outside the Java heap: an hour of audio is ~230 MB, several times the heap growth limit.
class DecodedAudio(val samples: ByteBuffer, val sampleCount: Int)

// Where converted 16 kHz mono samples go: a decoded file, or a live stream's pending window.
interface PcmSink {
    fun add(sample: Float)
}

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16000

    // ~9 hours; keeps the backing buffer's byte size within Int range.
    private const val MAX_TOTAL_SAMPLES = 512_000_000

    // Five minutes, used when the container does not report a duration.
    private const val DEFAULT_CAPACITY_SAMPLES = TARGET_RATE * 300

    private const val STALL_TIMEOUT_NS = 30_000_000_000L

    // Decodes whatever the platform supports (Opus/OGG voice notes, AAC/MP4 round videos) from the
    // file descriptor into 16 kHz mono float32 PCM. Uses async MediaCodec so decoding runs at the
    // codec's full speed instead of polling with per-packet timeouts. Each output chunk is
    // downmixed and resampled as it arrives — the raw stream is never buffered anywhere, so the
    // memory needed does not depend on the source rate or channel count.
    fun decodeToPcm16kMono(pfd: ParcelFileDescriptor, cancellation: CancellationToken? = null): DecodedAudio {
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

            val sink = SampleSink(initialCapacity(format))
            val lock = Any()
            var converter: ChunkConverter? = null
            var finished = false
            val done = CountDownLatch(1)
            val lastActivity = AtomicLong(System.nanoTime())
            val rate = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_RATE
            )
            val channels = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            )
            val encoding = intArrayOf(
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            )

            handlerThread.start()
            codec = MediaCodec.createDecoderByType(mime)
            codec.setCallback(object : MediaCodec.Callback() {
                private var inputDone = false
                private var scratch = ByteArray(0)

                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    lastActivity.set(System.nanoTime())
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
                    lastActivity.set(System.nanoTime())
                    if (cancellation?.isCancelled == true) {
                        mc.releaseOutputBuffer(index, false)
                        done.countDown()
                        return
                    }
                    try {
                        if (info.size > 0) {
                            val buffer = mc.getOutputBuffer(index)
                            if (buffer != null) {
                                if (scratch.size < info.size) {
                                    scratch = ByteArray(info.size)
                                }
                                buffer.position(info.offset)
                                buffer.get(scratch, 0, info.size)
                                synchronized(lock) {
                                    if (!finished) {
                                        val active = converter ?: ChunkConverter(
                                            rate[0], channels[0].coerceAtLeast(1), encoding[0], sink
                                        ).also { converter = it }
                                        active.feed(scratch, info.size)
                                    }
                                }
                            }
                        }
                        mc.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            done.countDown()
                        }
                    } catch (t: Throwable) {
                        // Running out of memory on a callback thread must not crash the process;
                        // keep whatever is already decoded.
                        Log.w(TAG, "PCM conversion failed", t)
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
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding[0] = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "Codec error", e)
                    done.countDown()
                }
            }, Handler(handlerThread.looper))
            codec.configure(format, null, null, 0)
            codec.start()

            cancellation?.onCancel { done.countDown() }
            // No overall deadline: a long recording legitimately decodes for a long time. Give up
            // only when the codec stops making progress altogether.
            while (!done.await(1, TimeUnit.SECONDS)) {
                if (cancellation?.isCancelled == true) {
                    break
                }
                if (System.nanoTime() - lastActivity.get() > STALL_TIMEOUT_NS) {
                    Log.w(TAG, "Decoder stalled, keeping the decoded part")
                    break
                }
            }

            synchronized(lock) {
                finished = true
                converter?.flush()
                return sink.result()
            }
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

    private fun initialCapacity(format: MediaFormat): Int {
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
        if (durationUs <= 0) {
            return DEFAULT_CAPACITY_SAMPLES
        }
        val estimated = durationUs / 1_000_000.0 * TARGET_RATE
        return (estimated * 1.02 + 2 * TARGET_RATE).toLong()
            .coerceAtMost(MAX_TOTAL_SAMPLES.toLong())
            .toInt()
    }

    private fun allocateSamples(samples: Int): ByteBuffer =
        ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.nativeOrder())

    // Accumulates the converted samples in native memory, sized up front from the track duration
    // so the usual case is a single allocation with no growth copies.
    private class SampleSink(initialCapacity: Int) : PcmSink {
        private var buffer = try {
            allocateSamples(initialCapacity)
        } catch (e: OutOfMemoryError) {
            allocateSamples(DEFAULT_CAPACITY_SAMPLES)
        }
        private var floats = buffer.asFloatBuffer()

        override fun add(sample: Float) {
            if (!floats.hasRemaining()) {
                grow()
            }
            floats.put(sample)
        }

        fun result() = DecodedAudio(buffer, floats.position())

        private fun grow() {
            val current = floats.position()
            if (current >= MAX_TOTAL_SAMPLES) {
                throw RuntimeException("Audio is too long")
            }
            val next = allocateSamples(
                (current * 2L)
                    .coerceAtLeast(DEFAULT_CAPACITY_SAMPLES.toLong())
                    .coerceAtMost(MAX_TOTAL_SAMPLES.toLong())
                    .toInt()
            )
            val nextFloats = next.asFloatBuffer()
            val readable = floats.duplicate()
            readable.flip()
            nextFloats.put(readable)
            buffer = next
            floats = nextFloats
        }
    }

    // Converts decoder output chunks (interleaved PCM at the source rate) into 16 kHz mono
    // samples as they arrive. Chunks need not be frame-aligned: partial frames are carried over.
    class ChunkConverter(
        private val srcRate: Int,
        private val channels: Int,
        encoding: Int,
        private val sink: PcmSink,
    ) {
        private val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw RuntimeException("Unsupported PCM encoding $encoding")
        }
        private val floatInput = encoding == AudioFormat.ENCODING_PCM_FLOAT
        private val bytesPerFrame = bytesPerSample * channels
        private val carry = ByteArray(bytesPerFrame)
        private var carried = 0

        private val step = srcRate.toDouble() / TARGET_RATE
        private var hist = 0f
        private var frames = 0L
        private var emitted = 0L

        fun feed(bytes: ByteArray, length: Int) {
            var pos = 0
            if (carried > 0) {
                val take = minOf(bytesPerFrame - carried, length)
                System.arraycopy(bytes, pos, carry, carried, take)
                carried += take
                pos += take
                if (carried < bytesPerFrame) {
                    return
                }
                pushFrame(carry, 0)
                carried = 0
            }
            while (pos + bytesPerFrame <= length) {
                pushFrame(bytes, pos)
                pos += bytesPerFrame
            }
            if (pos < length) {
                System.arraycopy(bytes, pos, carry, 0, length - pos)
                carried = length - pos
            }
        }

        // Emits the tail samples whose interpolation window never completed. Safe to call twice.
        fun flush() {
            val total = frames * TARGET_RATE / srcRate
            while (emitted < total) {
                sink.add(hist)
                emitted++
            }
        }

        // Downmixes one frame and emits every 16 kHz sample whose linear-interpolation window is
        // now complete. Only the previous mono sample is needed as history: an output between
        // source samples idx and idx+1 is emitted the moment idx+1 arrives.
        private fun pushFrame(bytes: ByteArray, offset: Int) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += sampleAt(bytes, offset + c * bytesPerSample)
            }
            val cur = acc / channels
            while (true) {
                val srcPos = emitted * step
                val idx = srcPos.toLong()
                if (idx + 1 > frames) {
                    break
                }
                val frac = (srcPos - idx).toFloat()
                sink.add(hist + (cur - hist) * frac)
                emitted++
            }
            hist = cur
            frames++
        }

        private fun sampleAt(bytes: ByteArray, offset: Int): Float {
            val lo = bytes[offset].toInt() and 0xff
            val b1 = bytes[offset + 1].toInt() and 0xff
            if (!floatInput) {
                return ((b1 shl 8) or lo).toShort() / 32768f
            }
            val bits = lo or (b1 shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
            return java.lang.Float.intBitsToFloat(bits)
        }
    }
}
