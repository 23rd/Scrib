package org.scrib.transcriber

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

// The microphone written into a compact AAC file, which then goes through the same decoder as any
// audio the app is handed. Recording at 16 kHz mono is what whisper wants anyway, so the sound is
// never resampled twice.
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null

    fun start(output: File) {
        val fresh = newRecorder()
        try {
            fresh.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            fresh.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            fresh.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            fresh.setAudioChannels(1)
            fresh.setAudioSamplingRate(SAMPLE_RATE)
            fresh.setAudioEncodingBitRate(BIT_RATE)
            fresh.setOutputFile(output.absolutePath)
            fresh.prepare()
            fresh.start()
        } catch (e: Throwable) {
            fresh.release()
            throw e
        }
        recorder = fresh
    }

    // Loudest sample since the previous call, 0..1 — the level meter's only input.
    fun level(): Float {
        val current = recorder ?: return 0f
        return try {
            (current.maxAmplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
        } catch (e: Exception) {
            0f
        }
    }

    // True when the output holds a finished recording. A take stopped in its first moments leaves
    // the encoder with nothing to write, and the file with no track the decoder could read.
    fun stop(): Boolean {
        val current = recorder ?: return false
        recorder = null
        return try {
            current.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            current.release()
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 64000
        const val MAX_AMPLITUDE = 32767f
    }
}
