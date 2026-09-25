package org.scrib.transcriber

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BenchmarkRun(
    val timestampMs: Long,
    val modelName: String?,
    val fileName: String,
    val metrics: TranscriptionMetrics
)

object BenchmarkStore {
    private const val FILE_NAME = "benchmark-history.json"
    private const val MAX_RUNS = 100
    private const val LOG_TAG = "BenchmarkStore"

    private val _revisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = _revisions

    @Synchronized
    fun record(context: Context, run: BenchmarkRun) {
        val runs = load(context).toMutableList()
        runs.add(0, run)
        if (runs.size > MAX_RUNS) {
            runs.subList(MAX_RUNS, runs.size).clear()
        }
        try {
            file(context).writeText(serialize(runs))
            _revisions.value += 1
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't save benchmark history", e)
        }
    }

    @Synchronized
    fun load(context: Context): List<BenchmarkRun> {
        val file = file(context)
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(file.readText())
            ArrayList<BenchmarkRun>(array.length()).apply {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val metrics = item.optJSONObject(METRICS) ?: JSONObject()
                    add(
                        BenchmarkRun(
                            timestampMs = item.optLong(TIMESTAMP_MS),
                            modelName = item.optString(MODEL).takeIf { it.isNotBlank() },
                            fileName = item.optString(FILE),
                            metrics = TranscriptionMetrics(
                                audioDurationMs = metrics.optLong(AUDIO_DURATION_MS),
                                decodeMs = metrics.optLong(DECODE_MS),
                                inferenceMs = metrics.optLong(INFERENCE_MS),
                                totalMs = metrics.optLong(TOTAL_MS),
                                pssMb = metrics.optInt(PSS_MB),
                                peakPssMb = metrics.optInt(PEAK_PSS_MB),
                                freeRamMb = metrics.optInt(FREE_RAM_MB)
                            )
                        )
                    )
                }
            }.sortedByDescending { it.timestampMs }
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Couldn't read benchmark history", e)
            emptyList()
        }
    }

    private fun serialize(runs: List<BenchmarkRun>): String {
        val array = JSONArray()
        runs.forEach { run ->
            array.put(
                JSONObject()
                    .put(TIMESTAMP_MS, run.timestampMs)
                    .put(MODEL, run.modelName)
                    .put(FILE, run.fileName)
                    .put(METRICS, JSONObject()
                        .put(AUDIO_DURATION_MS, run.metrics.audioDurationMs)
                        .put(DECODE_MS, run.metrics.decodeMs)
                        .put(INFERENCE_MS, run.metrics.inferenceMs)
                        .put(TOTAL_MS, run.metrics.totalMs)
                        .put(PSS_MB, run.metrics.pssMb)
                        .put(PEAK_PSS_MB, run.metrics.peakPssMb)
                        .put(FREE_RAM_MB, run.metrics.freeRamMb)
                    )
            )
        }
        return array.toString()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private const val TIMESTAMP_MS = "timestampMs"
    private const val MODEL = "model"
    private const val FILE = "file"
    private const val METRICS = "metrics"
    private const val AUDIO_DURATION_MS = "audioDurationMs"
    private const val DECODE_MS = "decodeMs"
    private const val INFERENCE_MS = "inferenceMs"
    private const val TOTAL_MS = "totalMs"
    private const val PSS_MB = "pssMb"
    private const val PEAK_PSS_MB = "peakPssMb"
    private const val FREE_RAM_MB = "freeRamMb"
}
