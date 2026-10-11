package org.scrib.transcriber

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TranscriptHistory {

    data class Entry(
        val id: String,
        val savedAtMs: Long,
        val fileName: String,
        val modelName: String?,
        val text: String,
        val segments: List<TranscriptSegment>
    )

    fun save(context: Context, transcript: TranscribeUi): Entry {
        val entry = Entry(
            id = System.currentTimeMillis().toString(36) + "-" + (0..9999).random(),
            savedAtMs = System.currentTimeMillis(),
            fileName = transcript.fileName,
            modelName = transcript.modelName,
            text = transcript.text,
            segments = transcript.segments
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
            Log.w(LOG_TAG, "Couldn't drop the kept transcript", e)
        }
    }

    private fun write(context: Context, entry: Entry) {
        try {
            val segments = JSONArray()
            entry.segments.forEach {
                segments.put(
                    JSONObject()
                        .put(START, it.startMs)
                        .put(END, it.endMs)
                        .put(TEXT, it.text)
                        .put(PARAGRAPH, it.startsParagraph)
                )
            }
            val json = JSONObject()
                .put(ID, entry.id)
                .put(SAVED_AT, entry.savedAtMs)
                .put(NAME, entry.fileName)
                .put(MODEL, entry.modelName.orEmpty())
                .put(TEXT, entry.text)
                .put(SEGMENTS, segments)
            dir(context).mkdirs()
            file(context, entry.id).writeText(json.toString())
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't keep the transcript", e)
        }
    }

    private fun read(file: File): Entry? {
        val json = JSONObject(file.readText())
        val text = json.optString(TEXT)
        if (text.isBlank()) {
            return null
        }
        val array = json.optJSONArray(SEGMENTS) ?: JSONArray()
        val segments = ArrayList<TranscriptSegment>(array.length())
        for (i in 0 until array.length()) {
            val segment = array.getJSONObject(i)
            segments.add(
                TranscriptSegment(
                    segment.optLong(START),
                    segment.optLong(END),
                    segment.optString(TEXT),
                    segment.optBoolean(PARAGRAPH)
                )
            )
        }
        return Entry(
            id = json.optString(ID).ifBlank { file.nameWithoutExtension },
            savedAtMs = json.optLong(SAVED_AT),
            fileName = json.optString(NAME),
            modelName = json.optString(MODEL).takeIf { it.isNotBlank() },
            text = text,
            segments = segments
        )
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun file(context: Context, id: String) = File(dir(context), "$id.json")

    private const val LOG_TAG = "TranscriptHistory"
    private const val DIR = "history"
    private const val ID = "id"
    private const val SAVED_AT = "savedAt"
    private const val NAME = "name"
    private const val MODEL = "model"
    private const val TEXT = "text"
    private const val SEGMENTS = "segments"
    private const val START = "start"
    private const val END = "end"
    private const val PARAGRAPH = "paragraph"
}
