package org.scrib.transcriber

// TranslateGemma only answers its own prompt wording
enum class PromptStyle { Generic, TranslateGemma }

// one gguf file per model
data class TranslationModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val tier: Int,
    val recommended: Boolean = false,
    val custom: Boolean = false,
    val promptStyle: PromptStyle = PromptStyle.Generic
)

object TranslationCatalog {

    private fun hf(
        repo: String,
        displayName: String,
        fileName: String,
        approxBytes: Long,
        tier: Int,
        recommended: Boolean = false,
        promptStyle: PromptStyle = PromptStyle.Generic
    ) = TranslationModel(
        id = repo.substringAfter('/'),
        displayName = displayName,
        fileName = fileName,
        url = "https://huggingface.co/$repo/resolve/main/$fileName",
        approxBytes = approxBytes,
        tier = tier,
        recommended = recommended,
        promptStyle = promptStyle
    )

    // cheapest first, tier from a quick de->en check not a proper benchmark
    val MODELS: List<TranslationModel> = listOf(
        hf("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5 0.8B", "Qwen3.5-0.8B-Q4_K_M.gguf", 533_000_000, 1),
        hf("ggml-org/gemma-3-1b-it-GGUF", "Gemma 3 1B", "gemma-3-1b-it-Q4_K_M.gguf", 806_058_240, 2),
        hf("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5 2B", "Qwen3.5-2B-Q4_K_M.gguf", 1_281_000_000, 3),
        hf("IndexTeam/Index-Translate-2B-GGUF", "Index-Translate 2B", "Index-Translate-2B.Q4_K_M.gguf", 1_312_164_352, 4, recommended = true),
        hf("bullerwins/translategemma-4b-it-GGUF", "TranslateGemma 4B", "translategemma-4b-it-Q4_K_M.gguf", 2_489_909_312, 5, promptStyle = PromptStyle.TranslateGemma)
    )

    fun byFileName(fileName: String): TranslationModel? = MODELS.firstOrNull { it.fileName == fileName }

    // custom models are recognised by file name
    fun promptStyleFor(fileName: String): PromptStyle =
        byFileName(fileName)?.promptStyle
            ?: if (fileName.lowercase().contains("translategemma")) PromptStyle.TranslateGemma else PromptStyle.Generic

    fun customModel(fileName: String, url: String): TranslationModel = TranslationModel(
        id = "custom:$fileName",
        displayName = fileName.removeSuffix(".gguf"),
        fileName = fileName,
        url = url,
        approxBytes = 0,
        tier = 3,
        custom = true,
        promptStyle = promptStyleFor(fileName)
    )
}

object TranslationLanguages {

    // picker languages, model is always asked in english
    val CODES: List<String> = listOf(
        "en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "uk", "tr", "ar", "zh", "ja", "ko",
        "hi", "cs", "sv", "da", "fi", "no", "el", "hu", "ro", "id", "vi"
    )

    fun displayName(code: String): String {
        val locale = java.util.Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(java.util.Locale.getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
    }

    fun englishName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)

    // english name -> code for every langauge the phone knows
    private val byEnglishName: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (locale in java.util.Locale.getAvailableLocales()) {
            val code = locale.language
            if (code.isEmpty() || code.length > 3) continue
            val name = locale.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase()
            if (name.isNotEmpty() && name != code) map.putIfAbsent(name, code)
        }
        map
    }

    fun codeForEnglishName(name: String): String? {
        val cleaned = name.lowercase().replace(Regex("[^a-z ]"), " ").trim()
        if (cleaned.isEmpty()) return null
        byEnglishName[cleaned]?.let { return it }
        // the name might be burried in a short sentance
        for (word in cleaned.split(Regex("\\s+"))) {
            byEnglishName[word]?.let { return it }
        }
        return null
    }
}
