package org.scrib.transcriber

data class LanguageOption(
    val nameRes: Int,
    val recommendedFileName: String,
    val noteRes: Int
)

object LanguageCatalog {

    private const val BASE_EN = "ggml-base.en-q5_1.bin"
    private const val BASE = "ggml-base-q5_1.bin"
    private const val SMALL = "ggml-small-q5_1.bin"

    val LANGUAGES: List<LanguageOption> = listOf(
        LanguageOption(R.string.lang_english, BASE_EN, R.string.note_english_base),
        LanguageOption(R.string.lang_spanish, BASE, R.string.note_multi_base),
        LanguageOption(R.string.lang_german, BASE, R.string.note_multi_base),
        LanguageOption(R.string.lang_french, BASE, R.string.note_multi_base),
        LanguageOption(R.string.lang_italian, BASE, R.string.note_multi_base),
        LanguageOption(R.string.lang_portuguese, BASE, R.string.note_multi_base),
        LanguageOption(R.string.lang_dutch, BASE, R.string.note_multi_base),
        LanguageOption(R.string.lang_russian, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_ukrainian, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_polish, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_turkish, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_arabic, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_chinese, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_japanese, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_korean, SMALL, R.string.note_multi_small),
        LanguageOption(R.string.lang_hindi, SMALL, R.string.note_multi_small)
    )
}
