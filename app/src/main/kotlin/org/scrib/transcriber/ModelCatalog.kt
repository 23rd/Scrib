package org.scrib.transcriber

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val multilingual: Boolean,
    val tier: Int,
    val recommended: Boolean = false,
    val custom: Boolean = false
)

object ModelCatalog {

    private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    // The ggml weights every family but whisper is published in, one file per model and quant.
    private const val GGUF = "https://huggingface.co/handy-computer/"

    // Silero VAD, which finds the speech in a recording. It transcribes nothing on its own and is
    // never the active model — it is a small companion to whichever whisper model is chosen.
    const val VAD_FILE = "ggml-silero-v5.1.2.bin"
    const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE"
    const val SHERPA_VAD_FILE = "silero_vad.onnx"
    const val SHERPA_VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$SHERPA_VAD_FILE"

    private fun standard(
        id: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        multilingual: Boolean,
        tier: Int,
        recommended: Boolean = false
    ) = WhisperModel(id, displayName, fileName, BASE + fileName, approxBytes, multilingual, tier, recommended)

    private fun gguf(
        id: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        multilingual: Boolean,
        tier: Int,
        recommended: Boolean = false
    ) = WhisperModel(id, displayName, fileName, GGUF + id + "/resolve/main/" + fileName, approxBytes, multilingual, tier, recommended)

    val MODELS: List<WhisperModel> = listOf(
        standard("tiny-q5_1", "Tiny", "ggml-tiny-q5_1.bin", 32_200_000, true, 1),
        standard("base-q5_1", "Base", "ggml-base-q5_1.bin", 57_800_000, true, 2, recommended = true),
        standard("small-q5_1", "Small", "ggml-small-q5_1.bin", 190_000_000, true, 3),
        standard("medium-q5_0", "Medium", "ggml-medium-q5_0.bin", 539_000_000, true, 4),
        standard("large-v3-turbo-q5_0", "Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin", 574_000_000, true, 5),
        standard("tiny.en-q5_1", "Tiny (English)", "ggml-tiny.en-q5_1.bin", 32_200_000, false, 1),
        standard("base.en-q5_1", "Base (English)", "ggml-base.en-q5_1.bin", 57_800_000, false, 2),
        standard("small.en-q5_1", "Small (English)", "ggml-small.en-q5_1.bin", 190_000_000, false, 3),
        // The families below are not whisper at all: one ggml library reads them all, one file is
        // the whole model, and the quant is part of the file name. Ordered by what a phone can
        // still run at a sensible pace, cheapest first.
        gguf("moonshine-tiny-gguf", "Moonshine Tiny", "moonshine-tiny-Q8_0.gguf", 35_500_000, false, 1),
        gguf("moonshine-streaming-tiny-gguf", "Moonshine Tiny Live", "moonshine-streaming-tiny-Q8_0.gguf", 50_500_000, false, 2),
        gguf("parakeet-tdt_ctc-110m-gguf", "Parakeet English", "parakeet-tdt_ctc-110m-Q4_K_M.gguf", 90_000_000, false, 2, recommended = true),
        gguf("canary-180m-flash-gguf", "Canary Flash", "canary-180m-flash-Q4_K_M.gguf", 139_200_000, true, 3),
        gguf("SenseVoiceSmall-gguf", "SenseVoice Small", "SenseVoiceSmall-Q4_K_M.gguf", 145_700_000, true, 3),
        gguf("gigaam-v3-ctc-gguf", "GigaAM v3", "gigaam-v3-ctc-Q4_K_M.gguf", 182_200_000, false, 4),
        gguf("parakeet-tdt-0.6b-v3-gguf", "Parakeet Multilingual", "parakeet-tdt-0.6b-v3-Q4_K_M.gguf", 485_400_000, true, 4),
        gguf("nemotron-3.5-asr-streaming-0.6b-gguf", "Nemotron Streaming", "nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf", 495_800_000, true, 5)
    )

    val PARAKEET_LANGUAGES: List<String> = listOf(
        "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it",
        "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk"
    )

    fun byFileName(fileName: String): WhisperModel? = MODELS.firstOrNull { it.fileName == fileName }

    fun isEnglishOnly(fileName: String): Boolean {
        val name = fileName.lowercase()
        if (name.contains(".en-") || name.contains(".en.") || name.endsWith(".en.bin")) {
            return true
        }
        // A gguf file carries the quant in its name rather than a language marker, so the families
        // that only ever transcribe English are recognised by what they are called.
        return name.contains("moonshine") || name.contains("gigaam") || name.contains("110m")
    }

    fun customModel(fileName: String, url: String): WhisperModel = WhisperModel(
        id = "custom:$fileName",
        displayName = fileName.removePrefix("ggml-").removeSuffix(".bin").removeSuffix(".gguf"),
        fileName = fileName,
        url = url,
        approxBytes = 0,
        multilingual = !isEnglishOnly(fileName),
        tier = 3,
        custom = true
    )
}
