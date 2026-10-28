package org.scrib.transcriber

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// 16 kHz mono float32 PCM in a direct buffer, ready to hand to native code. Short recordings live
// in plain native memory; long ones spill into a scratch file mapped into the address space — an
// hour of audio is ~230 MB, several times the anonymous native memory a phone grants a process,
// while mapped pages count as page cache the kernel reclaims under pressure. The samples are
// handed over with close(): it deletes the scratch file behind the buffer.
class DecodedAudio(val samples: ByteBuffer, val sampleCount: Int, private val resource: AutoCloseable? = null) : AutoCloseable {
    override fun close() {
        try {
            resource?.close()
        } catch (ignore: Exception) {
        }
    }
}

// Where converted 16 kHz mono samples go: a decoded file, or a live stream's pending window.
interface PcmSink {
    fun add(sample: Float)

    fun add(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            add(samples[i])
        }
    }
}

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_RATE = 16000

    // ~9 hours; keeps the backing buffer's byte size within Int range.
    private const val MAX_TOTAL_SAMPLES = 512_000_000

    // Five minutes, used when the container does not report a duration.
    private const val DEFAULT_CAPACITY_SAMPLES = TARGET_RATE * 300

    // Sinks at or above this many samples go into a scratch file. Below it plain memory holds up
    // to a 64 MB buffer plus its one-copy growth, which every phone grants; above it the recording
    // ends up in the hundreds of megabytes where anonymous allocations start failing mid-decode —
    // and a failed growth used to be swallowed, truncating the transcript without any error.
    private const val SINK_FILE_THRESHOLD_SAMPLES = 16_000_000

    // Kept free on top of the scratch file's size, so writing the mapped pages does not run the
    // disk to zero while other apps are using it too.
    private const val SCRATCH_FREE_SPACE_MARGIN_BYTES = 128L * 1024 * 1024

    private const val STALL_TIMEOUT_NS = 30_000_000_000L

    private const val DRAIN_TIMEOUT_SECONDS = 5L

    // Decodes whatever the platform supports (Opus/OGG voice notes, AAC/MP4 round videos) from the
    // file descriptor into 16 kHz mono float32 PCM. Uses async MediaCodec so decoding runs at the
    // codec's full speed instead of polling with per-packet timeouts. Each output chunk is
    // downmixed and resampled as it arrives — the raw stream is never buffered anywhere, so the
    // memory needed does not depend on the source rate or channel count. A decode that stops
    // before the source's end raises DecodeStoppedException: a silent partial result would look
    // exactly like a transcription that lost its tail.
    fun decodeToPcm16kMono(
        pfd: ParcelFileDescriptor,
        cancellation: CancellationToken? = null,
        scratchDir: File? = null
    ): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handler: Handler? = null
        val handlerThread = HandlerThread("fgt-decode")
        val stopping = AtomicBoolean(false)
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
            val trackDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }

            val sink = openSink(format, scratchDir)
            try {
                val pending = LinkedBlockingQueue<Chunk>(16)
                val done = CountDownLatch(1)
                val lastActivity = AtomicLong(System.nanoTime())
                val outputEos = AtomicBoolean(false)
                val failure = AtomicReference<Throwable>()
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
                handler = Handler(handlerThread.looper)
                codec = MediaCodec.createDecoderByType(mime)
                codec.setCallback(object : MediaCodec.Callback() {
                    private var inputDone = false

                    override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                        lastActivity.set(System.nanoTime())
                        if (inputDone || stopping.get() || cancellation?.isCancelled == true) {
                            return
                        }
                        try {
                            val buffer = mc.getInputBuffer(index) ?: return
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                mc.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        } catch (e: Exception) {
                            // Codec state can change under us; throwing here would kill the process.
                            Log.w(TAG, "Feeding the decoder failed", e)
                            failure.compareAndSet(null, e)
                            done.countDown()
                        }
                    }

                    override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        lastActivity.set(System.nanoTime())
                        if (stopping.get()) {
                            return
                        }
                        if (cancellation?.isCancelled == true) {
                            mc.releaseOutputBuffer(index, false)
                            done.countDown()
                            return
                        }
                        try {
                            if (info.size > 0) {
                                val buffer = mc.getOutputBuffer(index)
                                if (buffer != null) {
                                    val bytes = ByteArray(info.size)
                                    buffer.position(info.offset)
                                    buffer.get(bytes)
                                    pending.put(Chunk(bytes, info.size))
                                }
                            }
                            mc.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos.set(true)
                                done.countDown()
                            }
                        } catch (t: Throwable) {
                            // Running out of memory on a callback thread must not crash the process;
                            // the run fails loudly below instead of passing a truncated recording.
                            Log.w(TAG, "Handing the decoded chunk to the converter failed", t)
                            failure.compareAndSet(null, t)
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
                        failure.compareAndSet(null, e)
                        done.countDown()
                    }
                }, handler)
                codec.configure(format, null, null, 0)
                codec.start()

                val consumer = Thread {
                    var active: ChunkConverter? = null
                    var healthy = true
                    while (healthy) {
                        val chunk = try {
                            pending.take()
                        } catch (e: InterruptedException) {
                            break
                        }
                        if (chunk.size < 0) {
                            break
                        }
                        try {
                            val conv = active ?: ChunkConverter(
                                rate[0], channels[0].coerceAtLeast(1), encoding[0], sink
                            ).also { active = it }
                            conv.feed(chunk.bytes, chunk.size)
                        } catch (t: Throwable) {
                            // A failed sink write is permanent (the buffer that refused the samples
                            // is full), so every later chunk would be dropped the same way — stop
                            // consuming and fail the run instead of returning a short recording.
                            Log.w(TAG, "PCM conversion failed", t)
                            failure.compareAndSet(null, t)
                            done.countDown()
                            healthy = false
                        }
                    }
                    if (healthy) {
                        try {
                            active?.flush()
                        } catch (t: Throwable) {
                            Log.w(TAG, "PCM conversion failed", t)
                            failure.compareAndSet(null, t)
                            done.countDown()
                        }
                    }
                }
                consumer.start()

                try {
                    cancellation?.onCancel { done.countDown() }
                    // No overall deadline: a long recording legitimately decodes for a long time. Give up
                    // only when the codec stops making progress altogether.
                    while (!done.await(1, TimeUnit.SECONDS)) {
                        if (cancellation?.isCancelled == true) {
                            break
                        }
                        if (System.nanoTime() - lastActivity.get() > STALL_TIMEOUT_NS) {
                            Log.w(TAG, "Decoder stalled, failing the run")
                            break
                        }
                    }

                    // A consumer that died on a failure leaves the bounded queue full — clear it,
                    // or putting the stop marker here would block on nobody draining it.
                    pending.clear()
                    pending.put(Chunk(ByteArray(0), -1))
                    consumer.join()
                    if (cancellation?.isCancelled == true) {
                        return sink.result()
                    }
                    failure.get()?.let {
                        throw DecodeStoppedException(sink.sampleCount().toLong() * 1000L / TARGET_RATE, trackDurationMs, it)
                    }
                    if (!outputEos.get()) {
                        throw DecodeStoppedException(sink.sampleCount().toLong() * 1000L / TARGET_RATE, trackDurationMs, null)
                    }
                    return sink.result()
                } finally {
                    consumer.interrupt()
                }
            } catch (t: Throwable) {
                sink.discard()
                throw t
            }
        } finally {
            // Callbacks use the codec and the extractor from the decode thread. Raise the flag,
            // then drain that thread, so none of them is in flight while we tear both down.
            stopping.set(true)
            awaitDrained(handler)
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

    // A long recording decodes into a scratch file, and a killed process can leave one behind;
    // nothing else writes into this directory with these names, so a day is a safe age cutoff.
    private fun openSink(format: MediaFormat, scratchDir: File?): DecodeSink {
        val estimated = initialCapacity(format)
        if (scratchDir == null || estimated <= SINK_FILE_THRESHOLD_SAMPLES) {
            return InMemorySampleSink(estimated)
        }
        val sink = MappedSampleSink(scratchDir, estimated)
        Log.i(TAG, "Decoding into a scratch file, ~" + (estimated.toLong() * 4 / 1024 / 1024) + " MB")
        return sink
    }

    private fun awaitDrained(handler: Handler?) {
        val drained = CountDownLatch(1)
        if (handler?.post { drained.countDown() } != true) {
            return
        }
        try {
            drained.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (ignore: InterruptedException) {
            Thread.currentThread().interrupt()
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

    private interface DecodeSink : PcmSink {
        fun sampleCount(): Int

        // Hands the accumulated samples over; the caller closes the result when done with them.
        fun result(): DecodedAudio

        // Throws the samples away and releases whatever backs them.
        fun discard()
    }

    private class Chunk(val bytes: ByteArray, val size: Int)

    // Accumulates the converted samples in native memory, sized up front from the track duration
    // so the usual case is a single allocation with no growth copies.
    internal class InMemorySampleSink(initialCapacity: Int) : DecodeSink {
        private var buffer = try {
            allocateSamples(initialCapacity)
        } catch (e: OutOfMemoryError) {
            allocateSamples(DEFAULT_CAPACITY_SAMPLES)
        }
        private var floats = buffer.asFloatBuffer()

        override fun sampleCount() = floats.position()

        override fun result() = DecodedAudio(buffer, floats.position())

        override fun discard() {
        }

        override fun add(sample: Float) {
            if (!floats.hasRemaining()) {
                grow(1)
            }
            floats.put(sample)
        }

        override fun add(samples: FloatArray, count: Int) {
            if (floats.remaining() < count) {
                grow(count)
            }
            floats.put(samples, 0, count)
        }

        private fun grow(need: Int) {
            val current = floats.position()
            if (current >= MAX_TOTAL_SAMPLES) {
                throw RuntimeException("Audio is too long")
            }
            val next = allocateSamples(
                (current.toLong() + need)
                    .coerceAtLeast(current * 2L)
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

    // The long-recording sink: a scratch file in the cache directory, mapped into the address
    // space whole. Writing faults the file's pages in as they are touched, so the recording costs
    // page cache — which the kernel reclaims — instead of anonymous memory that cannot grow back.
    internal class MappedSampleSink(dir: File, initialCapacity: Int) : DecodeSink {
        private val file = File(dir, "decode-" + UUID.randomUUID() + ".f32")
        private val randomAccess = RandomAccessFile(file, "rw")
        private val channel = randomAccess.channel
        private var buffer: ByteBuffer
        private var floats: FloatBuffer

        init {
            try {
                sweepAbandoned(dir)
                val bytes = initialCapacity.toLong() * 4
                if (dir.usableSpace < bytes + SCRATCH_FREE_SPACE_MARGIN_BYTES) {
                    throw DecodeException("Not enough free space to decode the recording")
                }
                buffer = map(initialCapacity)
                floats = floatView()
            } catch (e: Exception) {
                releaseQuietly()
                throw if (e is DecodeException) e else DecodeException("Cannot create the decode scratch file")
            }
        }

        override fun sampleCount() = floats.position()

        override fun result() = DecodedAudio(buffer, floats.position()) { releaseQuietly() }

        override fun discard() {
            releaseQuietly()
        }

        override fun add(sample: Float) {
            if (!floats.hasRemaining()) {
                grow(1)
            }
            floats.put(sample)
        }

        override fun add(samples: FloatArray, count: Int) {
            if (floats.remaining() < count) {
                grow(count)
            }
            floats.put(samples, 0, count)
        }

        private fun grow(need: Int) {
            val current = floats.position()
            if (current >= MAX_TOTAL_SAMPLES) {
                throw RuntimeException("Audio is too long")
            }
            val next = (current.toLong() + need)
                .coerceAtLeast(current * 2L)
                .coerceAtLeast(DEFAULT_CAPACITY_SAMPLES.toLong())
                .coerceAtMost(MAX_TOTAL_SAMPLES.toLong())
                .toInt()
            buffer = map(next)
            val nextFloats = floatView()
            val readable = floats.duplicate()
            readable.flip()
            nextFloats.put(readable)
            floats = nextFloats
        }

        // duplicate() hands back a buffer whose byte order is reset to big-endian, so the order is
        // restated before every view is taken; the native engine reads the file's floats in the
        // machine order, and a byte-swapped recording would transcribe into noise.
        private fun floatView(): FloatBuffer =
            buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()

        // Grows (sparse — the blocks are only claimed when written) and maps the file whole; the
        // previous mapping simply becomes unreachable and the kernel takes it back at its leisure.
        private fun map(samples: Int): ByteBuffer {
            val bytes = samples.toLong() * 4
            randomAccess.setLength(bytes)
            return channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes).order(ByteOrder.nativeOrder())
        }

        private fun sweepAbandoned(dir: File) {
            val staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles { f -> f.name.startsWith("decode-") && f.name.endsWith(".f32") && f.lastModified() < staleBefore }
                ?.forEach { it.delete() }
        }

        private fun releaseQuietly() {
            try {
                channel.close()
            } catch (ignore: Exception) {
            }
            file.delete()
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

        private var shorts = ShortArray(0)
        private var floatSamples = FloatArray(0)
        private var out = FloatArray(0)

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
                pushFrames(carry, 0, 1)
                carried = 0
            }
            val frameCount = (length - pos) / bytesPerFrame
            if (frameCount > 0) {
                pushFrames(bytes, pos, frameCount)
                pos += frameCount * bytesPerFrame
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

        // Downmixes whole frames and emits every 16 kHz sample whose linear-interpolation window
        // is now complete. Only the previous mono sample is needed as history: an output between
        // source samples idx and idx+1 is emitted the moment idx+1 arrives.
        private fun pushFrames(bytes: ByteArray, offset: Int, frameCount: Int) {
            val sampleCount = frameCount * channels
            if (floatInput) {
                if (floatSamples.size < sampleCount) {
                    floatSamples = FloatArray(sampleCount)
                }
                ByteBuffer.wrap(bytes, offset, sampleCount * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                    .get(floatSamples, 0, sampleCount)
            } else {
                if (shorts.size < sampleCount) {
                    shorts = ShortArray(sampleCount)
                }
                ByteBuffer.wrap(bytes, offset, sampleCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(shorts, 0, sampleCount)
            }
            val emittedMax = Math.ceil(frameCount / step).toInt() + 8
            if (out.size < emittedMax) {
                out = FloatArray(emittedMax)
            }
            var written = 0
            var sample = 0
            var frame = 0
            while (frame < frameCount) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += if (floatInput) floatSamples[sample + c] else shorts[sample + c] / 32768f
                }
                val cur = acc / channels
                val total = frames + 1
                while (true) {
                    val srcPos = emitted * step
                    val idx = srcPos.toLong()
                    if (idx + 1 > total) {
                        break
                    }
                    val frac = (srcPos - idx).toFloat()
                    out[written++] = hist + (cur - hist) * frac
                    emitted++
                }
                hist = cur
                frames++
                sample += channels
                frame++
            }
            if (written > 0) {
                sink.add(out, written)
            }
        }
    }
}
