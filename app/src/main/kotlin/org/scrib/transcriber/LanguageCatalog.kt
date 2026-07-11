package org.scrib.transcriber

data class LanguageOption(
    val name: String,
    val recommendedFileName: String,
    val note: String
)

object LanguageCatalog {

    private const val BASE_EN = "ggml-base.en-q5_1.bin"
    private const val BASE = "ggml-base-q5_1.bin"
    private const val SMALL = "ggml-small-q5_1.bin"

    val LANGUAGES: List<LanguageOption> = listOf(
        LanguageOption("English", BASE_EN, "English-only · Base"),
        LanguageOption("Spanish", BASE, "Multilingual · Base"),
        LanguageOption("German", BASE, "Multilingual · Base"),
        LanguageOption("French", BASE, "Multilingual · Base"),
        LanguageOption("Italian", BASE, "Multilingual · Base"),
        LanguageOption("Portuguese", BASE, "Multilingual · Base"),
        LanguageOption("Dutch", BASE, "Multilingual · Base"),
        LanguageOption("Russian", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Ukrainian", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Polish", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Turkish", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Arabic", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Chinese", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Japanese", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Korean", SMALL, "Multilingual · Small — better accuracy"),
        LanguageOption("Hindi", SMALL, "Multilingual · Small — better accuracy")
    )
}
