package org.scrib.transcriber

// Engines with explicit phase reporting delimit recognition itself. Retain the old live
// estimate for engines that do not report that phase separately.
internal class RecognitionTiming {
    private var reportedMs = 0L
    private var decodedAt: Long? = null
    private var recognitionAt: Long? = null
    private var explicit = false

    fun update(metrics: TranscriptionMetrics, now: Long) {
        if (decodedAt == null && metrics.audioDurationMs > 0) decodedAt = now
        when (metrics.recognitionInProgress) {
            true -> {
                explicit = true
                if (recognitionAt == null) recognitionAt = now
            }
            false -> {
                explicit = true
                recognitionAt = null
            }
            null -> Unit
        }
        if (metrics.inferenceMs > 0) reportedMs = metrics.inferenceMs
    }

    fun durationAt(now: Long): Long {
        val start = if (explicit) recognitionAt else decodedAt
        return if (start == null) reportedMs else maxOf(reportedMs, now - start)
    }
}
