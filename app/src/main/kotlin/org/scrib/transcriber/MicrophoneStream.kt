package org.scrib.transcriber

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.abs

// The microphone as a live feed rather than a file. The app's recorder encodes a take and hands it
// over once it is finished; dictation needs the samples while the user is still talking, so they go
// into an AudioStream in the small chunks the contract asks for, and the segmenting there decides
// where an utterance ends.
class MicrophoneStream(
    private val sink: AudioStream,
    private val onLevel: (Float) -> Unit,
    private val onFailure: () -> Unit
) {

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var stopped = false

    // False when the microphone could not be opened at all — denied, or held by another app.
    fun start(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING,
                maxOf(minimum, CHUNK_BYTES * 4)
            )
        } catch (e: Throwable) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Throwable) {
            record.release()
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }
        recorder = record
        Thread({ run(record) }, "scrib-microphone").start()
        return true
    }

    // Only closes the microphone: the audio already captured still has to be transcribed, so the
    // stream is left for the caller to end. Releasing is the reader's job — the two must not race.
    fun stop() {
        if (stopped) {
            return
        }
        stopped = true
        try {
            recorder?.stop()
        } catch (ignore: Exception) {
        }
    }

    private fun run(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            while (!stopped) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == 0) {
                        continue
                    }
                    break
                }
                onLevel(peak(buffer, read))
                // Blocks while the model is behind, which is what keeps the backlog bounded.
                sink.write(buffer, read)
            }
        } catch (e: Throwable) {
            if (!stopped) {
                onFailure()
            }
        } finally {
            recorder = null
            try {
                record.stop()
            } catch (ignore: Exception) {
            }
            record.release()
        }
    }

    // The loudest sample of the chunk, 0..1 — the same measure the in-app recorder's meter draws.
    private fun peak(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val magnitude = abs(sample.toShort().toInt())
            if (magnitude > peak) {
                peak = magnitude
            }
            i += 2
        }
        return (peak / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16000

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // A tenth of a second, well under the transaction size the contract warns about.
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
        private const val MAX_AMPLITUDE = 32767f
    }
}
