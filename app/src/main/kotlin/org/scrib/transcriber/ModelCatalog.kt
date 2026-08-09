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

    // Silero VAD, which finds the speech in a recording. It transcribes nothing on its own and is
    // never the active model — it is a small companion to whichever whisper model is chosen.
    const val VAD_FILE = "ggml-silero-v5.1.2.bin"
    const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE"

    private fun standard(
        id: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        multilingual: Boolean,
        tier: Int,
        recommended: Boolean = false
    ) = WhisperModel(id, displayName, fileName, BASE + fileName, approxBytes, multilingual, tier, recommended)

    val MODELS: List<WhisperModel> = listOf(
        standard("tiny-q5_1", "Tiny", "ggml-tiny-q5_1.bin", 32_200_000, true, 1),
        standard("base-q5_1", "Base", "ggml-base-q5_1.bin", 57_800_000, true, 2, recommended = true),
        standard("small-q5_1", "Small", "ggml-small-q5_1.bin", 190_000_000, true, 3),
        standard("medium-q5_0", "Medium", "ggml-medium-q5_0.bin", 539_000_000, true, 4),
        standard("large-v3-turbo-q5_0", "Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin", 574_000_000, true, 5),
        standard("tiny.en-q5_1", "Tiny (English)", "ggml-tiny.en-q5_1.bin", 32_200_000, false, 1),
        standard("base.en-q5_1", "Base (English)", "ggml-base.en-q5_1.bin", 57_800_000, false, 2),
        standard("small.en-q5_1", "Small (English)", "ggml-small.en-q5_1.bin", 190_000_000, false, 3)
    )

    fun byFileName(fileName: String): WhisperModel? = MODELS.firstOrNull { it.fileName == fileName }

    fun isEnglishOnly(fileName: String): Boolean {
        val name = fileName.lowercase()
        return name.contains(".en-") || name.contains(".en.") || name.endsWith(".en.bin")
    }

    fun customModel(fileName: String, url: String): WhisperModel = WhisperModel(
        id = "custom:$fileName",
        displayName = fileName.removePrefix("ggml-").removeSuffix(".bin"),
        fileName = fileName,
        url = url,
        approxBytes = 0,
        multilingual = !isEnglishOnly(fileName),
        tier = 3,
        custom = true
    )
}
