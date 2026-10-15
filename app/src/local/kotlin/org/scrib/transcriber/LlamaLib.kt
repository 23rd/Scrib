package org.scrib.transcriber

// a llama.cpp model, begin() then next() untill null
class LlamaModel private constructor(private var ptr: Long) {

    val description: String? get() = if (ptr == 0L) null else LlamaLib.nativeDescription(ptr)

    val arch: String? get() = if (ptr == 0L) null else LlamaLib.nativeArch(ptr)

    // prompt token count, -1 if it wont fit, -2 if aborted
    fun begin(message: String, maxNewTokens: Int, abort: TranscribeAbort?): Int {
        check(ptr != 0L)
        return LlamaLib.nativeBegin(ptr, message, maxNewTokens, abort?.ptr ?: 0L)
    }

    fun next(): ByteArray? {
        check(ptr != 0L)
        return LlamaLib.nativeNext(ptr)
    }

    val generated: Int get() = if (ptr == 0L) 0 else LlamaLib.nativeGenerated(ptr)

    fun release() {
        if (ptr != 0L) {
            LlamaLib.nativeFree(ptr)
            ptr = 0L
        }
    }

    companion object {
        fun load(path: String, threads: Int, contextTokens: Int = DEFAULT_CONTEXT): LlamaModel {
            val ptr = LlamaLib.nativeLoad(path, threads, contextTokens)
            if (ptr == 0L) {
                throw RuntimeException("Could not load $path")
            }
            return LlamaModel(ptr)
        }

        const val DEFAULT_CONTEXT = 4096
    }
}

class LlamaLib private constructor() {

    companion object {
        init {
            System.loadLibrary("scrib_transcribe")
        }

        external fun nativeLoad(path: String, threads: Int, contextTokens: Int): Long
        external fun nativeFree(ptr: Long)
        external fun nativeDescription(ptr: Long): String?
        external fun nativeArch(ptr: Long): String?
        external fun nativeBegin(ptr: Long, message: String, maxNewTokens: Int, abortPtr: Long): Int
        external fun nativeNext(ptr: Long): ByteArray?
        external fun nativeGenerated(ptr: Long): Int
    }
}
