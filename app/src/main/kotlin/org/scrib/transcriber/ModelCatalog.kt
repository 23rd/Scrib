package org.scrib.transcriber

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val multilingual: Boolean,
    val recommended: Boolean = false,
    val custom: Boolean = false
)

object ModelCatalog {

    private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    private fun standard(
        id: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        multilingual: Boolean,
        recommended: Boolean = false
    ) = WhisperModel(id, displayName, fileName, BASE + fileName, approxBytes, multilingual, recommended)

    val MODELS: List<WhisperModel> = listOf(
        standard("tiny-q5_1", "Tiny", "ggml-tiny-q5_1.bin", 32_200_000, true),
        standard("base-q5_1", "Base", "ggml-base-q5_1.bin", 57_800_000, true, recommended = true),
        standard("small-q5_1", "Small", "ggml-small-q5_1.bin", 190_000_000, true),
        standard("medium-q5_0", "Medium", "ggml-medium-q5_0.bin", 539_000_000, true),
        standard("large-v3-turbo-q5_0", "Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin", 574_000_000, true),
        standard("tiny.en-q5_1", "Tiny (English)", "ggml-tiny.en-q5_1.bin", 32_200_000, false),
        standard("base.en-q5_1", "Base (English)", "ggml-base.en-q5_1.bin", 57_800_000, false),
        standard("small.en-q5_1", "Small (English)", "ggml-small.en-q5_1.bin", 190_000_000, false)
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
        custom = true
    )
}
