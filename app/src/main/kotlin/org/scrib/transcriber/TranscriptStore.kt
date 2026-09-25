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
                .put(MODEL, transcript.modelName)
                .put(TEXT, transcript.text)
                .put(SEGMENTS, segments)
                .put(METRICS, JSONObject()
                    .put(AUDIO_DURATION_MS, transcript.metrics.audioDurationMs)
                    .put(DECODE_MS, transcript.metrics.decodeMs)
                    .put(INFERENCE_MS, transcript.metrics.inferenceMs)
                    .put(MODEL_LOAD_MS, transcript.metrics.modelLoadMs)
                    .put(MODEL_PSS_MB, transcript.metrics.modelPssMb)
                    .put(MODEL_MEMORY_DELTA_MB, transcript.metrics.modelMemoryDeltaMb)
                    .put(TOTAL_MS, transcript.metrics.totalMs)
                    .put(PSS_MB, transcript.metrics.pssMb)
                    .put(PEAK_PSS_MB, transcript.metrics.peakPssMb)
                    .put(FREE_RAM_MB, transcript.metrics.freeRamMb)
                )
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
        val metrics = json.optJSONObject(METRICS) ?: JSONObject()
        return TranscribeUi(
            fileName = json.optString(NAME),
            text = text,
            running = false,
            error = null,
            modelName = json.optString(MODEL).takeIf { it.isNotBlank() },
            metrics = TranscriptionMetrics(
                audioDurationMs = metrics.optLong(AUDIO_DURATION_MS),
                decodeMs = metrics.optLong(DECODE_MS),
                inferenceMs = metrics.optLong(INFERENCE_MS),
                modelLoadMs = metrics.optLong(MODEL_LOAD_MS),
                modelPssMb = metrics.optInt(MODEL_PSS_MB),
                modelMemoryDeltaMb = metrics.optInt(MODEL_MEMORY_DELTA_MB),
                totalMs = metrics.optLong(TOTAL_MS),
                pssMb = metrics.optInt(PSS_MB),
                peakPssMb = metrics.optInt(PEAK_PSS_MB),
                freeRamMb = metrics.optInt(FREE_RAM_MB)
            ),
            segments = segments,
            percent = 100
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private const val NAME = "name"
    private const val MODEL = "model"
    private const val TEXT = "text"
    private const val SEGMENTS = "segments"
    private const val METRICS = "metrics"
    private const val AUDIO_DURATION_MS = "audioDurationMs"
    private const val DECODE_MS = "decodeMs"
    private const val INFERENCE_MS = "inferenceMs"
    private const val MODEL_LOAD_MS = "modelLoadMs"
    private const val MODEL_PSS_MB = "modelPssMb"
    private const val MODEL_MEMORY_DELTA_MB = "modelMemoryDeltaMb"
    private const val TOTAL_MS = "totalMs"
    private const val PSS_MB = "pssMb"
    private const val PEAK_PSS_MB = "peakPssMb"
    private const val FREE_RAM_MB = "freeRamMb"
    private const val START = "start"
    private const val END = "end"
    private const val PARAGRAPH = "paragraph"
}
