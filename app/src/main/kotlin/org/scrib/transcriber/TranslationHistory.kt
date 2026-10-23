package org.scrib.transcriber

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

// kept translations, one json file each
object TranslationHistory {

    data class Entry(
        val id: String,
        val savedAtMs: Long,
        val source: String,
        val text: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val modelName: String?
    )

    fun save(
        context: Context,
        source: String,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        modelName: String?
    ): Entry {
        val entry = Entry(
            id = System.currentTimeMillis().toString(36) + "-" + (0..9999).random(),
            savedAtMs = System.currentTimeMillis(),
            source = source,
            text = text,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            modelName = modelName
        )
        write(context, entry)
        return entry
    }

    fun load(context: Context): List<Entry> =
        dir(context).listFiles()
            ?.mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            ?.sortedByDescending { it.savedAtMs }
            ?: emptyList()

    fun delete(context: Context, id: String) {
        try {
            file(context, id).delete()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't drop the kept translation", e)
        }
    }

    fun clearAll(context: Context) {
        try {
            dir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't clear the kept translations", e)
        }
    }

    private fun write(context: Context, entry: Entry) {
        try {
            val json = JSONObject()
                .put(ID, entry.id)
                .put(SAVED_AT, entry.savedAtMs)
                .put(SOURCE, entry.source)
                .put(TEXT, entry.text)
                .put(FROM, entry.sourceLanguage)
                .put(TO, entry.targetLanguage)
                .put(MODEL, entry.modelName.orEmpty())
            dir(context).mkdirs()
            file(context, entry.id).writeText(json.toString())
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't keep the translation", e)
        }
    }

    private fun read(file: File): Entry? {
        val json = JSONObject(file.readText())
        val text = json.optString(TEXT)
        if (text.isBlank()) {
            return null
        }
        return Entry(
            id = json.optString(ID).ifBlank { file.nameWithoutExtension },
            savedAtMs = json.optLong(SAVED_AT),
            source = json.optString(SOURCE),
            text = text,
            sourceLanguage = json.optString(FROM),
            targetLanguage = json.optString(TO),
            modelName = json.optString(MODEL).takeIf { it.isNotBlank() }
        )
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun file(context: Context, id: String) = File(dir(context), "$id.json")

    private const val LOG_TAG = "TranslationHistory"
    private const val DIR = "translations"
    private const val ID = "id"
    private const val SAVED_AT = "savedAt"
    private const val SOURCE = "source"
    private const val TEXT = "text"
    private const val FROM = "from"
    private const val TO = "to"
    private const val MODEL = "model"
}
