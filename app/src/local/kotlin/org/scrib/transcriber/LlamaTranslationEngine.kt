package org.scrib.transcriber

import android.util.Log
import java.io.ByteArrayOutputStream

class TranslationPluginImpl : TranslationPlugin {

    @Volatile
    private var engine: TranslationEngine? = null

    @Synchronized
    override fun engine(context: android.content.Context): TranslationEngine =
        engine ?: LlamaTranslationEngine().also { engine = it }
}

class LlamaTranslationEngine : TranslationEngine {

    @Volatile
    private var model: LlamaModel? = null

    @Volatile
    private var loadedPath: String? = null

    @Synchronized
    override fun translate(
        modelPath: String,
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        cancellation: CancellationToken,
        onPartial: (String) -> Unit
    ): TranslationResult {
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        val llm = model(modelPath)
        val abort = TranscribeAbort.create()
        val out = StringBuilder()
        var tokens = 0
        try {
            cancellation.onCancel { abort.cancel() }
            for (chunk in TextChunker.split(text, MAX_CHUNK_CHARS)) {
                if (cancellation.isCancelled) {
                    throw CancelledException()
                }
                val prompt = prompt(modelPath, chunk.text, sourceLanguage, targetLanguage)
                // generous budget, the model stops itself well before
                val maxNew = (chunk.text.length + 128).coerceIn(256, MAX_ANSWER_TOKENS)
                val begun = llm.begin(prompt, maxNew, abort)
                if (begun == -2) {
                    throw CancelledException()
                }
                if (begun < 0) {
                    throw RuntimeException("Text is too long for the model's context")
                }
                val bytes = ByteArrayOutputStream()
                val prefix = out.toString() + chunk.joiner
                while (true) {
                    val piece = llm.next() ?: break
                    bytes.write(piece)
                    tokens++
                    onPartial(prefix + clean(String(bytes.toByteArray(), Charsets.UTF_8)))
                }
                if (cancellation.isCancelled) {
                    throw CancelledException()
                }
                out.append(chunk.joiner)
                out.append(clean(String(bytes.toByteArray(), Charsets.UTF_8)))
            }
        } finally {
            abort.close()
        }
        return TranslationResult(out.toString().trim(), tokens)
    }

    @Synchronized
    override fun detectLanguage(modelPath: String, text: String, cancellation: CancellationToken): String? {
        if (cancellation.isCancelled) {
            throw CancelledException()
        }
        val llm = model(modelPath)
        val abort = TranscribeAbort.create()
        try {
            cancellation.onCancel { abort.cancel() }
            val sample = text.trim().take(DETECT_SAMPLE_CHARS)
            val prompt = "Which language is the following text written in? Answer with the English name of the language only.\n\n$sample"
            val begun = llm.begin(prompt, 16, abort)
            if (begun == -2) {
                throw CancelledException()
            }
            if (begun < 0) {
                return null
            }
            val bytes = ByteArrayOutputStream()
            while (true) {
                val piece = llm.next() ?: break
                bytes.write(piece)
            }
            if (cancellation.isCancelled) {
                throw CancelledException()
            }
            val answer = clean(String(bytes.toByteArray(), Charsets.UTF_8))
            Log.i(TAG, "Language asked of the model: \"$answer\"")
            return TranslationLanguages.codeForEnglishName(answer)
        } finally {
            abort.close()
        }
    }

    @Synchronized
    override fun releaseModel() {
        model?.release()
        model = null
        loadedPath = null
    }

    private fun model(path: String): LlamaModel {
        model?.let {
            if (loadedPath == path) {
                return it
            }
            releaseModel()
        }
        val loaded = LlamaModel.load(path, CpuConfig.preferredThreadCount)
        Log.i(TAG, "Loaded ${loaded.description} (${loaded.arch})")
        model = loaded
        loadedPath = path
        return loaded
    }

    private fun prompt(modelPath: String, text: String, sourceLanguage: String?, targetLanguage: String): String {
        val target = TranslationLanguages.englishName(targetLanguage)
        val source = sourceLanguage?.let { TranslationLanguages.englishName(it) }
        return when (TranslationCatalog.promptStyleFor(java.io.File(modelPath).name)) {
            PromptStyle.TranslateGemma -> {
                // the exact wording TranslateGemma was trained on, two blank lines matter
                val pair = if (source != null) "$source ($sourceLanguage) to $target ($targetLanguage)" else "translator into $target ($targetLanguage)"
                val original = if (source != null) "original $source text" else "original text"
                val following = if (source != null) "following $source text" else "following text"
                "You are a professional ${if (source != null) "$pair translator" else pair}. " +
                    "Your goal is to accurately convey the meaning and nuances of the $original while adhering to " +
                    "$target grammar, vocabulary, and cultural sensitivities. Produce only the $target translation, " +
                    "without any additional explanations or commentary. Please translate the $following into $target:\n\n\n$text"
            }
            PromptStyle.Generic -> {
                val from = source?.let { "from $it " }.orEmpty()
                "Translate the following text ${from}into $target. Reply with only the translation: " +
                    "no explanations, no notes, and keep the line breaks of the original.\n\n$text"
            }
        }
    }

    // strip any thinking block that slipped through
    private fun clean(answer: String): String =
        THINK.replace(answer, "").trimStart()

    companion object {
        private const val TAG = "ScribTranslate"
        private const val MAX_CHUNK_CHARS = 1500
        private const val MAX_ANSWER_TOKENS = 2048
        private const val DETECT_SAMPLE_CHARS = 400
        private val THINK = Regex("(?s)<think>.*?</think>\\s*")
    }
}

// splits long text into chunks at paragraph then sentance ends
object TextChunker {

    class Chunk(val text: String, val joiner: String)

    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    fun split(text: String, maxChars: Int): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n"))
        var current = StringBuilder()
        var currentJoiner = ""
        fun flush(nextJoiner: String) {
            if (current.isNotEmpty()) {
                chunks.add(Chunk(current.toString(), currentJoiner))
                current = StringBuilder()
            }
            currentJoiner = nextJoiner
        }
        for ((index, paragraph) in paragraphs.withIndex()) {
            val p = paragraph.trim()
            if (p.isEmpty()) continue
            val joiner = if (index == 0) "" else "\n\n"
            if (current.isNotEmpty() && current.length + joiner.length + p.length > maxChars) {
                flush(joiner)
            }
            if (p.length <= maxChars) {
                if (current.isNotEmpty()) current.append(joiner)
                current.append(p)
                continue
            }
            // paragraph too long, go sentance by sentance
            flush(joiner)
            for (sentence in p.split(SENTENCE_END)) {
                val s = sentence.trim()
                if (s.isEmpty()) continue
                if (current.isNotEmpty() && current.length + 1 + s.length > maxChars) {
                    flush(" ")
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(s)
            }
        }
        flush("")
        return chunks
    }
}
