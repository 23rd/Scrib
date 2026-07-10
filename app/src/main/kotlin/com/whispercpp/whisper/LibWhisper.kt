package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "LibWhisper"

interface WhisperSegmentCallback {
    fun onSegment(text: String)
}

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
        val callback = if (onSegment != null) object : WhisperSegmentCallback {
            override fun onSegment(text: String) {
                try {
                    onSegment(text)
                } catch (ignore: Throwable) {
                }
            }
        } else null
        WhisperLib.fullTranscribe(ptr, numThreads, data, language ?: "", callback, abortFlag?.nativePtr() ?: 0L)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return buildString {
            for (i in 0 until textCount) {
                append(WhisperLib.getTextSegment(ptr, i))
            }
        }
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
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String, segmentCallback: WhisperSegmentCallback?, abortFlagPtr: Long)
        external fun newAbortFlag(): Long
        external fun setAbortFlag(flagPtr: Long)
        external fun freeAbortFlag(flagPtr: Long)
        external fun languageCount(): Int
        external fun languageId(index: Int): String?
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
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
