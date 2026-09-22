package org.scrib.transcriber

import android.content.Context
import android.util.Log

object Dictionary {

    private const val TAG = "Dictionary"
    private const val PREFS = "dictionary"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RULES = "rules"
    private const val KEY_RULES_VERSION = "rulesVersion"
    private const val CURRENT_RULES_VERSION = 6


    private val NEW_SEEDED_RULES_V4: List<String> = listOf(
        "джимэйл=Gmail",
        "джимейл=Gmail",
        "гмайл=Gmail",
        "артем=Артём",
        "алена=Алёна",
        "семен=Семён",
        "федор=Фёдор",
        "петр=Пётр",
        "еще=ещё",
        "самолет=самолёт",
        "вертолет=вертолёт"
    )

    private val NEW_SEEDED_RULES_V5: List<String> = listOf(
        "# «гид» бывает экскурсоводом — удалите правило, если мешает",
        "гид=git",
        "комит=коммит",
        "закомить=закоммитить",
        "комитнуть=коммитнуть",
        "а-а",
        "э-э",
        "аа",
        "ээ",
        "мм",
        "м-м",
        "хм",
        "типа"
    )

    private val NEW_SEEDED_RULES_V6: List<String> = listOf(
        "гидхаб=GitHub",
        "gidhabe=GitHub"
    )

    private val SEEDED_UPDATES: Map<Int, List<String>> = mapOf(
        4 to NEW_SEEDED_RULES_V4,
        5 to NEW_SEEDED_RULES_V5,
        6 to NEW_SEEDED_RULES_V6
    )

    private val RULE_MIGRATIONS: List<Pair<String, String>> = listOf(
        "макоес=МакОС" to "макоес=macOS",
        "макуэс=МакОС" to "макуэс=macOS",
        "макось=МакОС" to "макось=macOS",
        "маккуэс=МакОС" to "маккуэс=macOS",
        "макос=МакОС" to "макос=macOS",
        "мак ос=МакОС" to "мак ос=macOS",
        "mac os=МакОС" to "mac os=macOS"
    )

    private const val WORD_CHARS = "а-яёА-ЯЁa-zA-Z0-9"

    private const val V1_DEFAULTS: String =
        "# Словарь замен: строка «что слышит модель=что вставить».\n" +
            "# Строка без «=» удаляет слово, строки на «#» и пустые игнорируются.\n" +
            "# Регистр не важен; порядок важен — сначала длинные и точные правила.\n" +
            "дископ=десктоп\n" +
            "дескоп=десктоп\n" +
            "дескoп=десктоп\n" +
            "desk top=десктоп\n" +
            "discop=десктоп\n"

    val DEFAULT_RULES: String =
        "# Словарь замен: строка «что слышит модель=что вставить».\n" +
            "# Строка без «=» удаляет слово, строки на «#» и пустые игнорируются.\n" +
            "# Регистр не важен; порядок важен — сначала длинные и точные правила.\n" +
            "#\n" +
            "# --- macOS, десктоп, устройства ---\n" +
            "макоес=macOS\n" +
            "макуэс=macOS\n" +
            "макось=macOS\n" +
            "маккуэс=macOS\n" +
            "макос=macOS\n" +
            "мак ос=macOS\n" +
            "mac os=macOS\n" +
            "макбук=MacBook\n" +
            "айфон=iPhone\n" +
            "айос=iOS\n" +
            "андроид=Android\n" +
            "виндовс=Windows\n" +
            "винда=Windows\n" +
            "линукс=Linux\n" +
            "убунту=Ubuntu\n" +
            "#\n" +
            "# --- мессенджеры и соцсети ---\n" +
            "телеграм=Telegram\n" +
            "дискорд=Discord\n" +
            "ватсап=WhatsApp\n" +
            "вайбер=Viber\n" +
            "скайп=Skype\n" +
            "зум=Zoom\n" +
            "слак=Slack\n" +
            "слэк=Slack\n" +
            "гугл мит=Google Meet\n" +
            "ютуб=YouTube\n" +
            "тикток=TikTok\n" +
            "инстаграм=Instagram\n" +
            "фейсбук=Facebook\n" +
            "твиттер=X\n" +
            "реддит=Reddit\n" +
            "#\n" +
            "# --- языковые модели и агенты ---\n" +
            "чат гпт=ChatGPT\n" +
            "чатгпт=ChatGPT\n" +
            "чат джипити=ChatGPT\n" +
            "гпт-4о=GPT-4o\n" +
            "гпт-4=GPT-4\n" +
            "гпт-5=GPT-5\n" +
            "гпт4=GPT-4\n" +
            "гпт3=GPT-3\n" +
            "гпт=GPT\n" +
            "опен эй ай=OpenAI\n" +
            "опенэйай=OpenAI\n" +
            "клод код=Claude Code\n" +
            "клодкод=Claude Code\n" +
            "клод=Claude\n" +
            "антропик=Anthropic\n" +
            "джемини=Gemini\n" +
            "джеминай=Gemini\n" +
            "копайлот=Copilot\n" +
            "копилот=Copilot\n" +
            "манус=Manus\n" +
            "перплексити=Perplexity\n" +
            "дипсик=DeepSeek\n" +
            "грок=Grok\n" +
            "квен=Qwen\n" +
            "мистраль=Mistral\n" +
            "гигачат=GigaChat\n" +
            "яндекс гпт=YandexGPT\n" +
            "яндексгпт=YandexGPT\n" +
            "кандинский=Kandinsky\n" +
            "#\n" +
            "# --- имена и буква ё (модель ё не пишет) ---\n" +
            "артем=Артём\n" +
            "алена=Алёна\n" +
            "семен=Семён\n" +
            "федор=Фёдор\n" +
            "петр=Пётр\n" +
            "еще=ещё\n" +
            "самолет=самолёт\n" +
            "вертолет=вертолёт\n" +
            "#\n" +
            "# --- почта ---\n" +
            "джимэйл=Gmail\n" +
            "джимейл=Gmail\n" +
            "гмайл=Gmail\n" +
            "элевен лабс=ElevenLabs\n" +
            "суно=Suno\n" +
            "вео=Veo\n" +
            "вио=Veo\n" +
            "клинг=Kling\n" +
            "ранвей=Runway\n" +
            "#\n" +
            "# --- железо и софт ---\n" +
            "нвидиа=NVIDIA\n" +
            "энвидиа=NVIDIA\n" +
            "гпу=GPU\n" +
            "гугл плей=Google Play\n" +
            "плеймаркет=Google Play\n" +
            "апстор=App Store\n" +
            "эпстор=App Store\n" +
            "рустор=RuStore\n" +
            "эфдроид=F-Droid\n" +
            "апк=APK\n" +
            "хром=Chrome\n" +
            "фаерфокс=Firefox\n" +
            "эдж=Edge\n" +
            "самсунг=Samsung\n" +
            "эпл=Apple\n" +
            "майкрософт=Microsoft\n" +
            "гугл=Google\n" +
            "сири=Siri\n" +
            "мета=Meta\n" +
            "#\n" +
            "# --- разработка ---\n" +
            "гитхаб=GitHub\n" +
            "гидхаб=GitHub\n" +
            "gidhabe=GitHub\n" +
            "гит=Git\n" +
            "# «гид» бывает экскурсоводом — удалите правило, если мешает\n" +
            "гид=git\n" +
            "комит=коммит\n" +
            "закомить=закоммитить\n" +
            "комитнуть=коммитнуть\n" +
            "питон=Python\n" +
            "голанг=Go\n" +
            "джава=Java\n" +
            "котлин=Kotlin\n" +
            "свифт=Swift\n" +
            "раст=Rust\n" +
            "тайпскрипт=TypeScript\n" +
            "джаваскрипт=JavaScript\n" +
            "пхп=PHP\n" +
            "эскуэль=SQL\n" +
            "постгрес=PostgreSQL\n" +
            "постгресс=PostgreSQL\n" +
            "монго=MongoDB\n" +
            "монгодб=MongoDB\n" +
            "редис=Redis\n" +
            "докер=Docker\n" +
            "кубернетис=Kubernetes\n" +
            "кубер=Kubernetes\n" +
            "энжинкс=nginx\n" +
            "энджинэкс=nginx\n" +
            "фаербейс=Firebase\n" +
            "супабейс=Supabase\n" +
            "версель=Vercel\n" +
            "хероку=Heroku\n" +
            "клаудфлер=Cloudflare\n" +
            "вебсокет=WebSocket\n" +
            "графкьюэл=GraphQL\n" +
            "графкюэль=GraphQL\n" +
            "джэрписи=gRPC\n" +
            "джиарписи=gRPC\n" +
            "иксэмэль=XML\n" +
            "ямл=YAML\n" +
            "латех=LaTeX\n" +
            "маркдаун=Markdown\n" +
            "ноушен=Notion\n" +
            "обсидиан=Obsidian\n" +
            "фигма=Figma\n" +
            "джетбрейнс=JetBrains\n" +
            "пайчарм=PyCharm\n" +
            "интеллиджей=IntelliJ\n" +
            "андроид студио=Android Studio\n" +
            "икскод=Xcode\n" +
            "ви эс код=VS Code\n" +
            "вэ эс код=VS Code\n" +
            "вс код=VS Code\n" +
            "виэскод=VS Code\n" +
            "стековерфлоу=Stack Overflow\n" +
            "кагл=Kaggle\n" +
            "колаб=Colab\n" +
            "нампай=NumPy\n" +
            "пандас=pandas\n" +
            "пайторч=PyTorch\n" +
            "тензорфлоу=TensorFlow\n" +
            "керас=Keras\n" +
            "ффмпег=FFmpeg\n" +
            "оннкс=ONNX\n" +
            "виспер=Whisper\n" +
            "хаггинг фейс=Hugging Face\n" +
            "оллама=Ollama\n" +
            "олама=Ollama\n" +
            "элэм студио=LM Studio\n" +
            "о бэ эс=OBS\n" +
            "пайпи=PyPI\n" +
            "энпээм=npm\n" +
            "#\n" +
            "# --- аббревиатуры ---\n" +
            "апи=API\n" +
            "сдк=SDK\n" +
            "урл=URL\n" +
            "айпи=IP\n" +
            "днс=DNS\n" +
            "впн=VPN\n" +
            "эсэсэйч=SSH\n" +
            "вайфай=Wi-Fi\n" +
            "блютуз=Bluetooth\n" +
            "юэсби=USB\n" +
            "пдф=PDF\n" +
            "кьюар=QR\n" +
            "куар=QR\n" +
            "энэфси=NFC\n" +
            "джипиэс=GPS\n" +
            "сим=SIM\n" +
            "тэтеэс=TTS\n" +
            "ттс=TTS\n" +
            "стт=STT\n" +
            "аср=ASR\n" +
            "аээсэр=ASR\n" +
            "элэлэм=LLM\n" +
            "ллм=LLM\n" +
            "раг=RAG\n" +
            "эмсипи=MCP\n" +
            "рест=REST\n" +
            "#\n" +
            "# --- мусорные звуки и слова-паразиты (удаляются) ---\n" +
            "а-а\n" +
            "э-э\n" +
            "аа\n" +
            "ээ\n" +
            "мм\n" +
            "м-м\n" +
            "хм\n" +
            "типа\n" +
            "#\n" +
            "# --- десктоп ---\n" +
            "дископ=десктоп\n" +
            "дескоп=десктоп\n" +
            "дескoп=десктоп\n" +
            "desk top=десктоп\n" +
            "discop=десктоп\n"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getRules(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_RULES_VERSION, 1)
        if (version >= CURRENT_RULES_VERSION) {
            return prefs.getString(KEY_RULES, null) ?: DEFAULT_RULES
        }
        val saved = prefs.getString(KEY_RULES, null)
        val upgraded = if (saved == null || saved == V1_DEFAULTS || saved.trim() == V1_DEFAULTS.trim()) {
            DEFAULT_RULES
        } else {
            appendMissingSeedRules(migrateLines(saved), version)
        }
        prefs.edit().putString(KEY_RULES, upgraded)
            .putInt(KEY_RULES_VERSION, CURRENT_RULES_VERSION).apply()
        return upgraded
    }

    private fun appendMissingSeedRules(rules: String, fromVersion: Int): String {
        val existing = rules.lines().map { it.trim() }.toHashSet()
        val missing = ((fromVersion + 1)..CURRENT_RULES_VERSION)
            .flatMap { SEEDED_UPDATES[it] ?: emptyList() }
            .filter { it !in existing }
        if (missing.isEmpty()) {
            return rules
        }
        return rules.trimEnd() + "\n\n# --- добавлено в обновлении ---\n" +
            missing.joinToString("\n") + "\n"
    }

    private fun migrateLines(rules: String): String {
        val lines = rules.lines().toMutableList()
        for (i in lines.indices) {
            for ((old, new) in RULE_MIGRATIONS) {
                if (lines[i] == old) {
                    lines[i] = new
                    break
                }
            }
        }
        return lines.joinToString("\n")
    }

    fun setRules(context: Context, rules: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RULES, rules).putInt(KEY_RULES_VERSION, CURRENT_RULES_VERSION).apply()
    }

    fun applyReplacements(context: Context, text: String): String {
        if (text.isBlank() || !isEnabled(context)) {
            return text
        }
        return applyRules(text, getRules(context))
    }

    fun applyRules(text: String, raw: String): String {
        if (text.isBlank() || raw.isBlank()) {
            return text
        }
        return try {
            val result = raw.lines().fold(text) { acc, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return@fold acc
                }
                val (from, to) = if ('=' in trimmed) {
                    val parts = trimmed.split("=", limit = 2)
                    parts[0].trim() to parts[1].trim()
                } else {
                    trimmed to ""
                }
                if (from.isEmpty()) {
                    return@fold acc
                }
                val pattern = Regex(
                    "(?<![${WORD_CHARS}])${Regex.escape(from)}(?![${WORD_CHARS}])",
                    RegexOption.IGNORE_CASE
                )
                pattern.replace(acc, to)
            }
            result.replace(Regex(" {2,}"), " ").trim()
        } catch (e: Exception) {
            Log.w(TAG, "applyReplacements failed: ${e.message}")
            text
        }
    }
}
