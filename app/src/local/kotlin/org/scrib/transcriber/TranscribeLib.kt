package org.scrib.transcriber

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val LOG_TAG = "Transcribe"

class TranscribeModelInfo(
    val arch: String,
    val variant: String,
    val languages: List<String>,
    val streaming: Boolean,
    val translate: Boolean,
    val languageDetect: Boolean,
    val contextPrompt: Boolean,
    val longForm: Boolean,
    val maxAudioMs: Long
) {
    val isUnbounded: Boolean = maxAudioMs <= 0L
}

class TranscribeSegmentData(val startMs: Long, val endMs: Long, val text: String)

class TranscribeAbort internal constructor(internal var ptr: Long) {

    companion object {
        fun create(): TranscribeAbort = TranscribeAbort(TranscribeLib.nativeAbortNew())
    }

    fun cancel() {
        if (ptr != 0L) {
            TranscribeLib.nativeAbortSet(ptr)
        }
    }

    fun close() {
        if (ptr != 0L) {
            TranscribeLib.nativeAbortFree(ptr)
            ptr = 0
        }
    }
}

fun pcmBuffer(samples: FloatArray): ByteBuffer =
    ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder()).apply {
        asFloatBuffer().put(samples)
        position(0)
    }

class TranscribeVad private constructor(private var ptr: Long) {

    fun spans(samples: ByteBuffer, sampleCount: Int): List<VadSpan> {
        require(ptr != 0L)
        require(samples.isDirect)
        val flat = TranscribeLib.nativeVadSpans(
            ptr, samples, sampleCount, THRESHOLD, MIN_SPEECH_MS, MIN_SILENCE_MS, SPEECH_PAD_MS
        )
        val spans = ArrayList<VadSpan>(flat.size / 2)
        for (i in 0 until flat.size / 2) {
            spans.add(VadSpan(flat[i * 2], flat[i * 2 + 1]))
        }
        return spans
    }

    fun close() {
        if (ptr != 0L) {
            TranscribeLib.nativeVadClose(ptr)
            ptr = 0
        }
    }

    companion object {
        private const val THRESHOLD = 0.5f
        private const val MIN_SPEECH_MS = 250
        private const val MIN_SILENCE_MS = 100
        private const val SPEECH_PAD_MS = 30

        private var loaded: TranscribeVad? = null
        private var loadedPath: String? = null

        @Synchronized
        fun open(path: String): TranscribeVad? = try {
            loaded?.takeIf { loadedPath == path } ?: TranscribeLib.nativeVadOpen(path, CpuConfig.preferredThreadCount)
                .takeIf { it != 0L }
                ?.let {
                    loaded?.close()
                    TranscribeVad(it).also { model ->
                        loaded = model
                        loadedPath = path
                    }
                }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't open the silence-skipping model $path", e)
            null
        }

        @Synchronized
        fun release() {
            loaded?.close()
            loaded = null
            loadedPath = null
        }
    }
}

class VadSpan(val startMs: Long, val endMs: Long)

class TranscribeModel private constructor(private var ptr: Long) {

    val info: TranscribeModelInfo

    init {
        require(ptr != 0L)
        val flags = TranscribeLib.nativeModelFlags(ptr)
        info = TranscribeModelInfo(
            arch = TranscribeLib.nativeModelArch(ptr).orEmpty(),
            variant = TranscribeLib.nativeModelVariant(ptr).orEmpty(),
            languages = TranscribeLib.nativeModelLanguages(ptr)?.toList().orEmpty(),
            streaming = flags and FLAG_STREAMING != 0,
            translate = flags and FLAG_TRANSLATE != 0,
            languageDetect = flags and FLAG_LANGUAGE_DETECT != 0,
            contextPrompt = flags and FLAG_CONTEXT_PROMPT != 0,
            longForm = flags and FLAG_LONG_FORM != 0,
            maxAudioMs = TranscribeLib.nativeModelMaxAudioMs(ptr)
        )
        Log.d(LOG_TAG, "Loaded ${info.arch} ${info.variant} on ${TranscribeLib.nativeModelBackend(ptr)}")
    }

    val backend: String?
        get() = TranscribeLib.nativeModelBackend(ptr)

    @Synchronized
    fun openSession(threads: Int): TranscribeSession {
        require(ptr != 0L)
        val session = TranscribeLib.nativeSessionOpen(ptr, threads, 0)
        if (session == 0L) {
            throw ModelNotAvailableException()
        }
        return TranscribeSession(session, ptr)
    }

    @Synchronized
    fun release() {
        if (ptr != 0L) {
            TranscribeLib.nativeModelFree(ptr)
            ptr = 0
        }
    }

    companion object {
        private const val FLAG_STREAMING = 1
        private const val FLAG_TRANSLATE = 2
        private const val FLAG_LANGUAGE_DETECT = 4
        private const val FLAG_CONTEXT_PROMPT = 8
        private const val FLAG_LONG_FORM = 16

        val systemInfo: String by lazy {
            TranscribeLib.nativeInit().also { Log.d(LOG_TAG, it) }
        }

        fun usesVad(path: String): Boolean {
            systemInfo
            return TranscribeLib.nativeModelUsesVad(path)
        }

        fun load(path: String): TranscribeModel {
            systemInfo
            val model = TranscribeLib.nativeModelLoad(path)
            if (model == 0L) {
                throw DecodeException("Couldn't load model $path")
            }
            return TranscribeModel(model)
        }
    }
}

class TranscribeSession internal constructor(private var ptr: Long, private val modelPtr: Long) {

    fun run(
        samples: ByteBuffer,
        sampleCount: Int,
        language: String?,
        prompt: String?,
        translate: Boolean,
        abort: TranscribeAbort?
    ): Int {
        require(ptr != 0L)
        require(samples.isDirect)
        return TranscribeLib.nativeRun(
            ptr, modelPtr, samples, sampleCount, language, prompt,
            if (translate) TASK_TRANSLATE else TASK_TRANSCRIBE, abort?.ptr ?: 0L
        )
    }

    val fullText: String?
        get() = TranscribeLib.nativeFullText(ptr)

    val detectedLanguage: String?
        get() = TranscribeLib.nativeDetectedLanguage(ptr)

    val aborted: Boolean
        get() = TranscribeLib.nativeWasAborted(ptr)

    fun segments(): List<TranscribeSegmentData> {
        val count = TranscribeLib.nativeSegmentCount(ptr)
        if (count <= 0) {
            return emptyList()
        }
        val bounds = TranscribeLib.nativeSegmentBounds(ptr, 0)
        val segments = ArrayList<TranscribeSegmentData>(count)
        for (i in 0 until count) {
            val text = TranscribeLib.nativeSegmentText(ptr, i)?.takeIf { it.isNotBlank() } ?: continue
            segments.add(
                TranscribeSegmentData(
                    if (i * 2 + 1 < bounds.size) bounds[i * 2] else 0,
                    if (i * 2 + 1 < bounds.size) bounds[i * 2 + 1] else 0,
                    text
                )
            )
        }
        return segments
    }

    fun streamBegin(language: String?, prompt: String?, translate: Boolean, abort: TranscribeAbort?) {
        require(ptr != 0L)
        val status = TranscribeLib.nativeStreamBegin(
            ptr, modelPtr, language, prompt, if (translate) TASK_TRANSLATE else TASK_TRANSCRIBE,
            abort?.ptr ?: 0L
        )
        TranscribeSession.check(status)
    }

    fun streamFeed(samples: ByteBuffer, sampleCount: Int) {
        require(ptr != 0L)
        TranscribeSession.check(TranscribeLib.nativeStreamFeed(ptr, samples, sampleCount))
    }

    fun streamFinalize() {
        require(ptr != 0L)
        TranscribeSession.check(TranscribeLib.nativeStreamFinalize(ptr))
    }

    fun streamReset() {
        TranscribeLib.nativeStreamReset(ptr)
    }

    val committedText: String?
        get() = TranscribeLib.nativeStreamCommittedText(ptr)

    @Synchronized
    fun release() {
        if (ptr != 0L) {
            TranscribeLib.nativeSessionClose(ptr)
            ptr = 0
        }
    }

    companion object {
        private const val TASK_TRANSCRIBE = 0
        private const val TASK_TRANSLATE = 1

        private const val STATUS_ABORTED = 13

        fun check(status: Int) {
            when (status) {
                0 -> return
                STATUS_ABORTED -> throw CancelledException()
                else -> throw DecodeException(TranscribeLib.nativeStatusString(status))
            }
        }
    }
}

private class TranscribeLib {
    companion object {
        init {
            System.loadLibrary("scrib_transcribe")
        }

        external fun nativeInit(): String
        external fun nativeStatusString(status: Int): String
        external fun nativeModelUsesVad(path: String): Boolean

        external fun nativeVadOpen(path: String, threads: Int): Long
        external fun nativeVadClose(vadPtr: Long)
        external fun nativeVadSpans(
            vadPtr: Long,
            buffer: ByteBuffer,
            sampleCount: Int,
            threshold: Float,
            minSpeechMs: Int,
            minSilenceMs: Int,
            speechPadMs: Int
        ): LongArray

        external fun nativeModelLoad(path: String): Long
        external fun nativeModelFree(modelPtr: Long)
        external fun nativeModelArch(modelPtr: Long): String?
        external fun nativeModelVariant(modelPtr: Long): String?
        external fun nativeModelBackend(modelPtr: Long): String?
        external fun nativeModelLanguages(modelPtr: Long): Array<String>?
        external fun nativeModelFlags(modelPtr: Long): Int
        external fun nativeModelMaxAudioMs(modelPtr: Long): Long

        external fun nativeSessionOpen(modelPtr: Long, threads: Int, nCtx: Int): Long
        external fun nativeSessionClose(sessionPtr: Long)

        external fun nativeRun(
            sessionPtr: Long,
            modelPtr: Long,
            buffer: java.nio.ByteBuffer,
            sampleCount: Int,
            language: String?,
            prompt: String?,
            task: Int,
            abortFlagPtr: Long
        ): Int

        external fun nativeFullText(sessionPtr: Long): String?
        external fun nativeDetectedLanguage(sessionPtr: Long): String?
        external fun nativeSegmentCount(sessionPtr: Long): Int
        external fun nativeSegmentText(sessionPtr: Long, index: Int): String?
        external fun nativeSegmentBounds(sessionPtr: Long, fromIndex: Int): LongArray
        external fun nativeWasAborted(sessionPtr: Long): Boolean

        external fun nativeStreamBegin(
            sessionPtr: Long,
            modelPtr: Long,
            language: String?,
            prompt: String?,
            task: Int,
            abortFlagPtr: Long
        ): Int

        external fun nativeStreamFeed(sessionPtr: Long, buffer: java.nio.ByteBuffer, sampleCount: Int): Int
        external fun nativeStreamFinalize(sessionPtr: Long): Int
        external fun nativeStreamReset(sessionPtr: Long)
        external fun nativeStreamCommittedText(sessionPtr: Long): String?

        external fun nativeAbortNew(): Long
        external fun nativeAbortSet(flagPtr: Long)
        external fun nativeAbortFree(flagPtr: Long)
    }
}
