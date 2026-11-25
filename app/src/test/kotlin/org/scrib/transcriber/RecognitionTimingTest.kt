package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionTimingTest {
    @Test fun decodingAndColdModelLoadDoNotCountAsRecognition() {
        val timing = RecognitionTiming()
        timing.update(TranscriptionMetrics(audioDurationMs = 2100, recognitionInProgress = false), now = 1_000)
        timing.update(TranscriptionMetrics(modelLoadMs = 2600), now = 3_600)
        assertEquals(0L, timing.durationAt(now = 3_700))
        timing.update(TranscriptionMetrics(recognitionInProgress = true), now = 3_800)
        assertEquals(900L, timing.durationAt(now = 4_700))
    }

    @Test fun completedRecognitionUsesMeasuredValueAndDoesNotGrowDuringResultWork() {
        val timing = RecognitionTiming()
        timing.update(TranscriptionMetrics(recognitionInProgress = true), now = 10)
        timing.update(TranscriptionMetrics(inferenceMs = 90, recognitionInProgress = true), now = 100)
        assertEquals(120L, timing.durationAt(now = 130))
        timing.update(TranscriptionMetrics(inferenceMs = 125, recognitionInProgress = false), now = 140)
        assertEquals(125L, timing.durationAt(now = 2_000))
    }

    @Test fun enginesWithoutExplicitPhasesKeepExistingLiveEstimate() {
        val timing = RecognitionTiming()
        timing.update(TranscriptionMetrics(audioDurationMs = 1000), now = 100)
        timing.update(TranscriptionMetrics(inferenceMs = 200), now = 170)
        assertEquals(200L, timing.durationAt(now = 180))
        assertEquals(300L, timing.durationAt(now = 400))
    }
}
