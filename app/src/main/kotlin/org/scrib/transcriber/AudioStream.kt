package org.scrib.transcriber

import android.media.AudioFormat
import android.os.IBinder
import android.util.Log
import com.whispercpp.whisper.WhisperAbortFlag
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.StreamRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class TranscribedSegment(val text: String, val language: String?)

fun interface SegmentTranscriber {
    fun transcribe(
        samples: ByteBuffer,
        sampleCount: Int,
        language: String?,
        prompt: String?,
        abortFlag: WhisperAbortFlag
    ): TranscribedSegment
}

// Audio arriving live from another app. Whisper consumes a whole window at once, so the stream is
// cut into utterances at pauses and each one is transcribed complete: the text only ever grows,
// and no word is split the way a fixed-interval cut would split it.
class AudioStream(
    request: StreamRequest?,
    private val callback: ITranscriptionCallback,
    private val transcriber: SegmentTranscriber
) {

    private val lock = Object()
    private val pending = PendingAudio()
    private val converter: AudioDecoder.ChunkConverter
    private val abortFlag = WhisperAbortFlag()
    private val settled = AtomicBoolean(false)
    private val worker = Thread({ run() }, "scrib-stream")

    private val segment = ByteBuffer
        .allocateDirect(MAX_SEGMENT_SAMPLES * 4)
        .order(ByteOrder.nativeOrder())
    private val segmentFloats: FloatBuffer = segment.asFloatBuffer()

    private val death = IBinder.DeathRecipient { cancel() }

    // Guarded by lock.
    private var ended = false
    private var cancelled = false
    private var flushed = false
    private var scannedFrames = 0
    private var silenceFrames = 0
    private var speechFrames = 0
    private var presentFrames = 0
    private var noiseFloor = Float.MAX_VALUE
    private var blockLow = Float.MAX_VALUE
    private var previousLow = Float.MAX_VALUE
    private var blockFrames = 0

    // Worker thread only.
    private val committed = StringBuilder()
    private var language: String?
    private var closed: () -> Unit = {}

    init {
        val rate = request?.sampleRate.let { if (it == null || it <= 0) SAMPLE_RATE else it }
        val channels = request?.channels.let { if (it == null || it <= 0) 1 else it }
        require(rate in MIN_INPUT_RATE..MAX_INPUT_RATE) { "Unsupported sample rate $rate" }
        require(channels in 1..MAX_INPUT_CHANNELS) { "Unsupported channel count $channels" }
        converter = AudioDecoder.ChunkConverter(rate, channels, AudioFormat.ENCODING_PCM_16BIT, pending)
        language = request?.languageHint?.takeIf { it.isNotEmpty() }
    }

    fun start(onClosed: () -> Unit = {}) {
        closed = onClosed
        try {
            callback.asBinder().linkToDeath(death, 0)
        } catch (e: Exception) {
            cancel()
        }
        worker.start()
    }

    // Blocks while the backlog is full, so a client pushing faster than the model can keep up is
    // slowed down instead of filling memory. Call it off the main thread.
    fun write(pcm: ByteArray, length: Int) {
        val count = length.coerceAtMost(pcm.size)
        if (count <= 0) {
            return
        }
        synchronized(lock) {
            var waited = 0L
            while (pending.count >= HIGH_WATER_SAMPLES && !cancelled && !ended) {
                if (waited >= WRITE_TIMEOUT_MS) {
                    throw IllegalStateException("Transcription is too far behind the stream")
                }
                try {
                    lock.wait(WRITE_WAIT_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while waiting for the backlog to drain")
                }
                waited += WRITE_WAIT_MS
            }
            if (cancelled || ended) {
                return
            }
            converter.feed(pcm, count)
            lock.notifyAll()
        }
    }

    fun endOfStream() {
        synchronized(lock) {
            ended = true
            lock.notifyAll()
        }
    }

    fun cancel() {
        synchronized(lock) {
            if (cancelled) {
                return
            }
            cancelled = true
            lock.notifyAll()
        }
        abortFlag.cancel()
    }

    private fun run() {
        try {
            while (true) {
                var length = 0
                var last = false
                synchronized(lock) {
                    while (true) {
                        if (cancelled) {
                            throw CancelledException()
                        }
                        if (ended && !flushed) {
                            converter.flush()
                            flushed = true
                        }
                        val cut = scanForCut()
                        if (cut > 0) {
                            length = take(cut)
                            break
                        }
                        if (ended) {
                            last = pending.count == 0
                            if (!last) {
                                length = take(pending.count.coerceAtMost(MAX_SEGMENT_SAMPLES))
                                last = pending.count == 0
                            }
                            break
                        }
                        trimLeadingSilence()
                        lock.wait()
                    }
                }
                if (length > 0) {
                    transcribeSegment(length)
                }
                if (last) {
                    settle { callback.onTranscriptionResult(committed.toString()) }
                    return
                }
            }
        } catch (e: CancelledException) {
            settle { callback.onTranscriptionError(transcriptionError(ErrorType.CANCELLED)) }
        } catch (e: ModelNotAvailableException) {
            settle { callback.onTranscriptionError(transcriptionError(ErrorType.MODEL_NOT_AVAILABLE)) }
        } catch (e: Throwable) {
            Log.w(TAG, "Stream failed", e)
            settle { callback.onTranscriptionError(transcriptionError(ErrorType.UNEXPECTED, e.message)) }
        } finally {
            abortFlag.close()
            try {
                callback.asBinder().unlinkToDeath(death, 0)
            } catch (ignore: Exception) {
            }
            closed()
        }
    }

    private fun transcribeSegment(length: Int) {
        val prompt = committed.takeLast(PROMPT_CHARS).toString()
        val result = transcriber.transcribe(segment, length, language, prompt, abortFlag)
        synchronized(lock) {
            if (cancelled) {
                throw CancelledException()
            }
        }
        val text = result.text.trim()
        if (text.isEmpty()) {
            return
        }
        // Detecting per utterance lets a stream drift into another language mid-sentence.
        if (language == null) {
            language = result.language
        }
        if (committed.isNotEmpty()) {
            committed.append(' ')
        }
        committed.append(text)
        try {
            callback.onTranscriptionProgress(committed.toString())
        } catch (e: Exception) {
            cancel()
            throw CancelledException()
        }
    }

    private fun scanForCut(): Int {
        while ((scannedFrames + 1) * FRAME_SAMPLES <= pending.count) {
            val level = frameLevel(scannedFrames * FRAME_SAMPLES)
            updateNoiseFloor(level)
            // Two thresholds on purpose: whether anything audible is there, which decides what may
            // be dropped, and whether it is confidently speech, which only decides where to cut.
            // Judging both by the strict one discards real speech in a room as loud as the talker.
            val present = level >= maxOf(SILENCE_FLOOR, noiseFloor * PRESENCE_MARGIN)
            val silent = level < maxOf(SILENCE_FLOOR, noiseFloor * SILENCE_MARGIN)
            scannedFrames++
            if (present) {
                presentFrames++
            }
            if (silent) {
                silenceFrames++
            } else {
                silenceFrames = 0
                speechFrames++
            }
            val position = scannedFrames * FRAME_SAMPLES
            val paused = silenceFrames >= SILENCE_FRAMES &&
                speechFrames >= MIN_SPEECH_FRAMES &&
                position >= MIN_SEGMENT_SAMPLES
            if (paused || position >= MAX_SEGMENT_SAMPLES) {
                return position
            }
        }
        return -1
    }

    private fun trimLeadingSilence() {
        if (presentFrames > 0) {
            return
        }
        val scanned = scannedFrames * FRAME_SAMPLES
        val keep = PRE_ROLL_SAMPLES + FRAME_SAMPLES * SILENCE_FRAMES
        if (scanned <= keep) {
            return
        }
        val drop = (scanned - keep) / FRAME_SAMPLES * FRAME_SAMPLES
        pending.drop(drop)
        scannedFrames -= drop / FRAME_SAMPLES
    }

    // Returns 0 when the window holds no speech, so silence is dropped rather than handed to
    // whisper, which reliably invents text for it.
    private fun take(length: Int): Int {
        val speech = presentFrames >= MIN_SPEECH_FRAMES
        if (speech) {
            pending.copyInto(segmentFloats, length)
        }
        pending.drop(length)
        scannedFrames = 0
        silenceFrames = 0
        speechFrames = 0
        presentFrames = 0
        lock.notifyAll()
        return if (speech) length else 0
    }

    private fun frameLevel(offset: Int): Float {
        var sum = 0.0
        for (i in offset until offset + FRAME_SAMPLES) {
            val sample = pending[i]
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / FRAME_SAMPLES).toFloat()
    }

    // The quietest frame of the last few seconds, as the running minimum of two blocks so no
    // history is stored. A floor derived from the silence verdict instead could never rise: in a
    // room above it every frame already counts as speech, and no pause is ever found.
    private fun updateNoiseFloor(level: Float) {
        blockLow = minOf(blockLow, level)
        blockFrames++
        if (blockFrames >= NOISE_BLOCK_FRAMES) {
            previousLow = blockLow
            blockLow = Float.MAX_VALUE
            blockFrames = 0
        }
        noiseFloor = minOf(previousLow, blockLow)
    }

    private fun settle(report: () -> Unit) {
        if (!settled.compareAndSet(false, true)) {
            return
        }
        try {
            report()
        } catch (ignore: Exception) {
        }
    }

    private class PendingAudio : PcmSink {

        private var buffer = allocate(SAMPLE_RATE * 4)
        private var floats = buffer.asFloatBuffer()

        var count = 0
            private set

        override fun add(sample: Float) {
            if (count == floats.capacity()) {
                grow()
            }
            floats.put(count, sample)
            count++
        }

        operator fun get(index: Int): Float = floats.get(index)

        fun copyInto(target: FloatBuffer, length: Int) {
            val source = floats.duplicate()
            source.position(0)
            source.limit(length)
            target.position(0)
            target.put(source)
        }

        // Shifts the rest down to the start: native code reads a direct buffer from its base
        // address, never from its position.
        fun drop(length: Int) {
            for (i in 0 until count - length) {
                floats.put(i, floats.get(i + length))
            }
            count -= length
        }

        private fun grow() {
            if (count >= MAX_PENDING_SAMPLES) {
                throw IllegalStateException("Stream backlog is too long")
            }
            val next = allocate((count * 2L).coerceAtMost(MAX_PENDING_SAMPLES.toLong()).toInt())
            val nextFloats = next.asFloatBuffer()
            val readable = floats.duplicate()
            readable.position(0)
            readable.limit(count)
            nextFloats.put(readable)
            buffer = next
            floats = nextFloats
        }

        private fun allocate(samples: Int): ByteBuffer =
            ByteBuffer.allocateDirect(samples * 4).order(ByteOrder.nativeOrder())
    }

    private companion object {
        const val TAG = "AudioStream"

        const val SAMPLE_RATE = 16000
        const val MIN_INPUT_RATE = 4000
        const val MAX_INPUT_RATE = 192000
        const val MAX_INPUT_CHANNELS = 8

        const val FRAME_SAMPLES = 320
        const val SILENCE_FRAMES = 20
        const val MIN_SPEECH_FRAMES = 8
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE / 5

        const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE * 2
        const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 20
        const val HIGH_WATER_SAMPLES = SAMPLE_RATE * 30
        const val MAX_PENDING_SAMPLES = SAMPLE_RATE * 150

        const val WRITE_WAIT_MS = 200L
        const val WRITE_TIMEOUT_MS = 30_000L
        const val PROMPT_CHARS = 200

        const val SILENCE_FLOOR = 0.004f
        const val SILENCE_MARGIN = 2.5f
        const val PRESENCE_MARGIN = 1.6f
        const val NOISE_BLOCK_FRAMES = 100
    }
}
