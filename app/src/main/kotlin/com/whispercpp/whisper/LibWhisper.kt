package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val LOG_TAG = "LibWhisper"

interface WhisperSegmentCallback {
    fun onSegment(text: String)
}

// How far through the audio whisper is, 0..100.
interface WhisperProgressCallback {
    fun onProgress(percent: Int)
}

class WhisperChunk(val text: String, val language: String?)

class WhisperSegment(val startMs: Long, val endMs: Long, val text: String)

// A stretch the VAD heard speech in. Only populated when the run used a VAD model.
class WhisperSpeechSpan(val startMs: Long, val endMs: Long)

class WhisperTranscription(val segments: List<WhisperSegment>, val speech: List<WhisperSpeechSpan>)

class WhisperAbortFlag {

    private var ptr: Long = WhisperLib.newAbortFlag()

    @Synchronized
    fun nativePtr(): Long = ptr

    @Synchronized
    fun cancel() {
        if (ptr != 0L) {
            WhisperLib.setAbortFlag(ptr)
        }
    }

    @Synchronized
    fun close() {
        if (ptr != 0L) {
            WhisperLib.freeAbortFlag(ptr)
            ptr = 0
        }
    }
}

class WhisperContext private constructor(private var ptr: Long) {

    val audioWindowSamples: Int = WhisperLib.audioWindowSamples(ptr)

    val isParakeet: Boolean = WhisperLib.isParakeetContext(ptr)

    // Whisper C++ requires that a context is not accessed from more than one thread
    // at a time; the callers here are already serialized, and this enforces it too.
    @Synchronized
    fun transcribeData(
        data: FloatArray,
        language: String?,
        abortFlag: WhisperAbortFlag? = null,
        onSegment: ((String) -> Unit)? = null
    ): String {
        require(ptr != 0L)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        WhisperLib.fullTranscribe(ptr, numThreads, data, language ?: "", segmentCallback(onSegment), abortFlag?.nativePtr() ?: 0L)
        return collectText()
    }

    // Same, but reads the samples from a direct buffer, so long recordings never need a
    // Java-heap array. Returns the segments whisper decoded, each with its position in the
    // recording, so a caller can lay the text out as subtitles rather than one block. With a
    // vadModelPath the quiet stretches are skipped, and the timings still refer to the original
    // recording, not to what is left after the silence is dropped.
    @Synchronized
    fun transcribeBuffer(
        samples: ByteBuffer,
        sampleCount: Int,
        language: String?,
        abortFlag: WhisperAbortFlag? = null,
        vadModelPath: String? = null,
        onSegment: ((String) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null
    ): WhisperTranscription {
        require(ptr != 0L)
        require(samples.isDirect)
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        WhisperLib.fullTranscribeDirect(ptr, numThreads, samples, sampleCount, language ?: "", "", false, vadModelPath ?: "", segmentCallback(onSegment), progressCallback(onProgress), abortFlag?.nativePtr() ?: 0L)
        return WhisperTranscription(collectSegments(), collectSpeechSpans())
    }

    // One chunk of a live stream. The detected language is read under the same lock, so a
    // parallel file request cannot overwrite it in between. No VAD here: the stream is already cut
    // at its own pauses and hands over only the windows that hold speech.
    @Synchronized
    fun transcribeChunk(
        samples: ByteBuffer,
        sampleCount: Int,
        language: String?,
        prompt: String?,
        abortFlag: WhisperAbortFlag?
    ): WhisperChunk {
        require(ptr != 0L)
        require(samples.isDirect)
        WhisperLib.fullTranscribeDirect(ptr, WhisperCpuConfig.preferredThreadCount, samples, sampleCount, language ?: "", prompt ?: "", true, "", null, null, abortFlag?.nativePtr() ?: 0L)
        return WhisperChunk(collectText(), WhisperLib.fullLangId(ptr))
    }

    private fun segmentCallback(onSegment: ((String) -> Unit)?): WhisperSegmentCallback? =
        if (onSegment != null) object : WhisperSegmentCallback {
            override fun onSegment(text: String) {
                try {
                    onSegment(text)
                } catch (ignore: Throwable) {
                }
            }
        } else null

    private fun progressCallback(onProgress: ((Int) -> Unit)?): WhisperProgressCallback? =
        if (onProgress != null) object : WhisperProgressCallback {
            override fun onProgress(percent: Int) {
                try {
                    onProgress(percent)
                } catch (ignore: Throwable) {
                }
            }
        } else null

    private fun collectText(): String {
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return buildString {
            for (i in 0 until textCount) {
                append(WhisperLib.getTextSegment(ptr, i))
            }
        }
    }

    // Whisper reports segment bounds in centiseconds.
    private fun collectSegments(): List<WhisperSegment> {
        val count = WhisperLib.getTextSegmentCount(ptr)
        val segments = ArrayList<WhisperSegment>(count)
        for (i in 0 until count) {
            segments.add(
                WhisperSegment(
                    WhisperLib.getTextSegmentT0(ptr, i) * 10,
                    WhisperLib.getTextSegmentT1(ptr, i) * 10,
                    WhisperLib.getTextSegment(ptr, i)
                )
            )
        }
        return segments
    }

    // Whisper reports these in centiseconds too.
    private fun collectSpeechSpans(): List<WhisperSpeechSpan> {
        val count = WhisperLib.getSpeechSpanCount(ptr)
        val spans = ArrayList<WhisperSpeechSpan>(count)
        for (i in 0 until count) {
            spans.add(
                WhisperSpeechSpan(
                    WhisperLib.getSpeechSpanT0(ptr, i) * 10,
                    WhisperLib.getSpeechSpanT1(ptr, i) * 10
                )
            )
        }
        return spans
    }

    @Synchronized
    fun release() {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        release()
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String = WhisperLib.getSystemInfo()

        fun isParakeetModel(modelPath: String): Boolean = WhisperLib.isParakeetModel(modelPath)

        fun supportedLanguages(): List<String> {
            val count = WhisperLib.languageCount()
            val languages = ArrayList<String>(count)
            for (i in 0 until count) {
                WhisperLib.languageId(i)?.let { languages.add(it) }
            }
            return languages
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")) {
                cpuInfo()?.let { if (it.contains("vfpv4")) loadVfpv4 = true }
            } else if (Build.SUPPORTED_ABIS[0].equals("arm64-v8a")) {
                cpuInfo()?.let { if (it.contains("fphp")) loadV8fp16 = true }
            }
            when {
                loadVfpv4 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                }
                loadV8fp16 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun audioWindowSamples(contextPtr: Long): Int
        external fun isParakeetContext(contextPtr: Long): Boolean
        external fun isParakeetModel(modelPath: String): Boolean
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String, segmentCallback: WhisperSegmentCallback?, abortFlagPtr: Long)
        external fun fullTranscribeDirect(contextPtr: Long, numThreads: Int, audioBuffer: ByteBuffer, sampleCount: Int, language: String, prompt: String, suppressNonSpeech: Boolean, vadModelPath: String, segmentCallback: WhisperSegmentCallback?, progressCallback: WhisperProgressCallback?, abortFlagPtr: Long)
        external fun fullLangId(contextPtr: Long): String?
        external fun newAbortFlag(): Long
        external fun setAbortFlag(flagPtr: Long)
        external fun freeAbortFlag(flagPtr: Long)
        external fun languageCount(): Int
        external fun languageId(index: Int): String?
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSpeechSpanCount(contextPtr: Long): Int
        external fun getSpeechSpanT0(contextPtr: Long, index: Int): Long
        external fun getSpeechSpanT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}
