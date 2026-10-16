package org.scrib.transcriber

data class LanguageOption(
    val nameRes: Int,
    val recommendedFileName: String,
    val noteRes: Int,
    val code: String
)

object LanguageCatalog {

    private const val BASE_EN = "ggml-base.en-q8_0.bin"
    private const val BASE = "ggml-base-q8_0.bin"
    private const val SMALL = "ggml-small-q8_0.bin"

    val LANGUAGES: List<LanguageOption> = listOf(
        LanguageOption(R.string.lang_english, BASE_EN, R.string.note_english_base, "en"),
        LanguageOption(R.string.lang_spanish, BASE, R.string.note_multi_base, "es"),
        LanguageOption(R.string.lang_german, BASE, R.string.note_multi_base, "de"),
        LanguageOption(R.string.lang_french, BASE, R.string.note_multi_base, "fr"),
        LanguageOption(R.string.lang_italian, BASE, R.string.note_multi_base, "it"),
        LanguageOption(R.string.lang_portuguese, BASE, R.string.note_multi_base, "pt"),
        LanguageOption(R.string.lang_dutch, BASE, R.string.note_multi_base, "nl"),
        LanguageOption(R.string.lang_russian, SMALL, R.string.note_multi_small, "ru"),
        LanguageOption(R.string.lang_ukrainian, SMALL, R.string.note_multi_small, "uk"),
        LanguageOption(R.string.lang_polish, SMALL, R.string.note_multi_small, "pl"),
        LanguageOption(R.string.lang_turkish, SMALL, R.string.note_multi_small, "tr"),
        LanguageOption(R.string.lang_arabic, SMALL, R.string.note_multi_small, "ar"),
        LanguageOption(R.string.lang_chinese, SMALL, R.string.note_multi_small, "zh"),
        LanguageOption(R.string.lang_japanese, SMALL, R.string.note_multi_small, "ja"),
        LanguageOption(R.string.lang_korean, SMALL, R.string.note_multi_small, "ko"),
        LanguageOption(R.string.lang_hindi, SMALL, R.string.note_multi_small, "hi")
    )
}
