package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log

private const val LOG_TAG = "LibWhisper"

interface WhisperSegmentCallback {
    fun onSegment(text: String)
}

class WhisperContext private constructor(private var ptr: Long) {

    // Whisper C++ requires that a context is not accessed from more than one thread
    // at a time; the callers here are already serialized, and this enforces it too.
    @Synchronized
    fun transcribeData(data: FloatArray, language: String?, onSegment: ((String) -> Unit)? = null): String {
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
        WhisperLib.fullTranscribe(ptr, numThreads, data, language ?: "", callback)
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
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            System.loadLibrary("whisper")
        }

        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String, segmentCallback: WhisperSegmentCallback?)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}
