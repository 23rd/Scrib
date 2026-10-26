package org.scrib.transcriber

// cheap langauge guess from script and common words, null if unsure
object LanguageDetector {

    private val WORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "to", "of", "a", "in", "is", "it", "that", "i", "you", "for", "was", "on", "with", "we", "this", "have", "are", "be", "not", "but", "at", "they", "so", "just"),
        "de" to setOf("und", "die", "der", "das", "ich", "nicht", "ist", "zu", "sie", "wir", "ein", "eine", "mit", "auf", "für", "dass", "es", "den", "dem", "sich", "auch", "noch", "wenn", "aber", "nur", "heute", "haben", "wird", "bei", "um"),
        "es" to setOf("el", "la", "de", "que", "y", "en", "los", "las", "un", "una", "por", "con", "para", "es", "se", "no", "lo", "pero", "como", "más", "su", "al", "del", "muy", "también", "este", "esta", "hay"),
        "fr" to setOf("le", "la", "les", "de", "des", "et", "est", "un", "une", "que", "qui", "dans", "pour", "pas", "je", "nous", "vous", "il", "elle", "sur", "avec", "ce", "cette", "mais", "au", "aux", "plus", "très", "ne"),
        "it" to setOf("il", "la", "di", "che", "e", "è", "un", "una", "per", "non", "con", "sono", "del", "della", "anche", "come", "ma", "più", "gli", "le", "si", "ho", "ha", "questo", "questa", "nel", "alla", "nella"),
        "pt" to setOf("o", "a", "de", "que", "e", "do", "da", "em", "um", "uma", "para", "com", "não", "os", "as", "se", "por", "mais", "mas", "também", "ele", "ela", "você", "isso", "está", "são", "nos", "muito"),
        "nl" to setOf("de", "het", "een", "en", "van", "ik", "je", "dat", "is", "niet", "op", "te", "zijn", "voor", "met", "maar", "ook", "we", "er", "nog", "dan", "wel", "naar", "bij", "dit", "hebben", "wat", "zij"),
        "pl" to setOf("i", "w", "nie", "na", "się", "to", "jest", "że", "z", "do", "jak", "ale", "co", "tak", "po", "za", "ja", "już", "tylko", "jeszcze", "bardzo", "był", "być", "może", "przez", "dla", "od"),
        "tr" to setOf("ve", "bir", "bu", "da", "de", "için", "ile", "ben", "sen", "ne", "çok", "ama", "gibi", "daha", "var", "yok", "mi", "mı", "olarak", "kadar", "sonra", "şey", "bize", "biz", "o", "en"),
        "cs" to setOf("a", "je", "se", "na", "v", "to", "že", "s", "jsem", "jsme", "ale", "jak", "do", "pro", "tak", "co", "nebo", "už", "ještě", "také", "být", "byl", "jsou", "si", "od", "pak", "když"),
        "sv" to setOf("och", "att", "det", "är", "jag", "en", "ett", "som", "på", "inte", "med", "för", "av", "har", "vi", "till", "om", "den", "de", "så", "kan", "men", "var", "här", "ska", "också"),
        "da" to setOf("og", "at", "det", "er", "jeg", "en", "et", "som", "på", "ikke", "med", "for", "af", "har", "vi", "til", "om", "den", "de", "så", "kan", "men", "var", "her", "skal", "også"),
        "no" to setOf("og", "at", "det", "er", "jeg", "en", "et", "som", "på", "ikke", "med", "for", "av", "har", "vi", "til", "om", "den", "de", "så", "kan", "men", "var", "her", "skal", "også", "ikkje"),
        "fi" to setOf("ja", "on", "ei", "että", "se", "en", "minä", "me", "mutta", "kun", "niin", "myös", "tai", "jos", "vain", "hän", "ole", "ovat", "oli", "nyt", "tämä", "sitten", "kanssa", "mitä", "joka"),
        "hu" to setOf("és", "a", "az", "hogy", "nem", "is", "egy", "ez", "de", "van", "meg", "csak", "már", "még", "mi", "én", "volt", "ha", "vagy", "mint", "majd", "azt", "amit", "lesz", "kell"),
        "ro" to setOf("și", "de", "la", "în", "cu", "este", "un", "o", "pe", "nu", "că", "se", "mai", "pentru", "dar", "sau", "din", "eu", "noi", "care", "sunt", "am", "fost", "ce", "foarte", "aceasta"),
        "id" to setOf("dan", "yang", "di", "ini", "itu", "dengan", "untuk", "tidak", "saya", "kami", "kita", "ada", "akan", "dari", "ke", "juga", "sudah", "bisa", "karena", "pada", "adalah", "mereka", "kalau", "atau"),
        "vi" to setOf("và", "là", "của", "có", "không", "tôi", "chúng", "được", "cho", "này", "một", "các", "với", "để", "trong", "đã", "sẽ", "cũng", "như", "nhưng", "thì", "rất", "bạn", "anh", "chị")
    )

    private val UKRAINIAN = setOf('і', 'ї', 'є', 'ґ')
    private val RUSSIAN = setOf('ы', 'э', 'ё', 'ъ')

    fun detect(text: String): String? {
        val sample = text.take(4000)
        if (sample.isBlank()) return null
        scriptOf(sample)?.let { return it }
        val tokens = sample.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.isNotEmpty() }
        if (tokens.size < 3) return null
        var best: String? = null
        var bestHits = 0
        for ((code, words) in WORDS) {
            val hits = tokens.count { it in words }
            if (hits > bestHits) {
                bestHits = hits
                best = code
            }
        }
        // two hits in a hundred words is just noise
        return if (best != null && bestHits >= 2 && bestHits * 10 >= tokens.size) best else null
    }

    private fun scriptOf(sample: String): String? {
        var cyrillic = 0; var ukrainian = 0; var russian = 0
        var arabic = 0; var greek = 0; var devanagari = 0; var hangul = 0; var kana = 0; var han = 0; var letters = 0
        for (c in sample) {
            if (!Character.isLetter(c)) continue
            letters++
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.CYRILLIC, Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> {
                    cyrillic++
                    val l = c.lowercaseChar()
                    if (l in UKRAINIAN) ukrainian++ else if (l in RUSSIAN) russian++
                }
                Character.UnicodeBlock.ARABIC -> arabic++
                Character.UnicodeBlock.GREEK -> greek++
                Character.UnicodeBlock.DEVANAGARI -> devanagari++
                Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO -> hangul++
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                else -> {}
            }
        }
        if (letters == 0) return null
        fun dominant(n: Int) = n * 2 > letters
        return when {
            dominant(cyrillic) -> if (ukrainian > russian) "uk" else "ru"
            dominant(arabic) -> "ar"
            dominant(greek) -> "el"
            dominant(devanagari) -> "hi"
            dominant(hangul) -> "ko"
            dominant(kana + han) -> if (kana > 0) "ja" else "zh"
            else -> null
        }
    }
}
