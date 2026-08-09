package org.scrib.transcriber

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TranscriptStore {

    private const val LOG_TAG = "TranscriptStore"
    private const val FILE_NAME = "last-transcript.json"

    fun save(context: Context, transcript: TranscribeUi) {
        try {
            val segments = JSONArray()
            transcript.segments.forEach {
                segments.put(
                    JSONObject()
                        .put(START, it.startMs)
                        .put(END, it.endMs)
                        .put(TEXT, it.text)
                        .put(PARAGRAPH, it.startsParagraph)
                )
            }
            val json = JSONObject()
                .put(NAME, transcript.fileName)
                .put(TEXT, transcript.text)
                .put(SEGMENTS, segments)
            file(context).writeText(json.toString())
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't keep the transcript", e)
        }
    }

    fun load(context: Context): TranscribeUi? = try {
        val file = file(context)
        if (file.exists()) read(file) else null
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Couldn't read the kept transcript", e)
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't drop the kept transcript", e)
        }
    }

    private fun read(file: File): TranscribeUi? {
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
        return TranscribeUi(
            fileName = json.optString(NAME),
            text = text,
            running = false,
            error = null,
            segments = segments,
            percent = 100
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private const val NAME = "name"
    private const val TEXT = "text"
    private const val SEGMENTS = "segments"
    private const val START = "start"
    private const val END = "end"
    private const val PARAGRAPH = "paragraph"
}
