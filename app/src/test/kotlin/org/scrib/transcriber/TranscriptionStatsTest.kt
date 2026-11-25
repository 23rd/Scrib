package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TranscriptionStatsTest {
    private val running = TranscribeUi("sample.wav", "", true, null)

    @Test fun phaseDoesNotDependOnLiveSamplingOrPositiveProgress() {
        assertEquals(RunStage.Preparing, running.runStage())
        assertEquals(RunStage.Transcribing, running.copy(audioPrepared = true).runStage())
        assertEquals(RunStage.Transcribing, running.copy(percent = 0).runStage())
        assertEquals(RunStage.Transcribing, running.copy(metrics = TranscriptionMetrics(audioDurationMs = 500)).runStage())
        assertEquals(RunStage.Complete, running.copy(running = false).runStage())
    }

    @Test fun endpointNeverClaimsToKnowTheServerPhase() {
        val endpoint = running.copy(usesEndpoint = true, audioPrepared = true, percent = 100)
        assertEquals(RunStage.Processing, endpoint.runStage())
        assertEquals(RunStage.Complete, endpoint.copy(running = false).runStage())
    }

    @Test fun rowsIncludeOnlyReportedMeasurements() {
        assertTrue(TranscriptionMetrics().runStats(" ", usesEndpoint = false).isEmpty())
        val metrics = TranscriptionMetrics(decodeMs = 8_000, pssMb = 420, modelMemoryDeltaMb = -10)
        val rows = metrics.runStats("Tiny", usesEndpoint = false)
        assertEquals(listOf(R.string.run_stats_model, R.string.run_stats_decode, R.string.run_stats_memory), rows.map { it.label })
        assertEquals(8_000L, (rows[1] as RunStat.Duration).ms)
        assertEquals(420, (rows[2] as RunStat.Memory).mb)
    }

    @Test fun endpointHidesLocalOnlyMeasurementsAndRatio() {
        val metrics = TranscriptionMetrics(audioDurationMs = 1_000, inferenceMs = 1_600, modelPssMb = 8)
        assertEquals(
            listOf(R.string.run_stats_inference, R.string.run_stats_audio, R.string.run_stats_ratio, R.string.run_stats_model_memory),
            metrics.runStats(null, usesEndpoint = false).map { it.label }
        )
        assertEquals(listOf(R.string.run_stats_audio), metrics.runStats(null, usesEndpoint = true).map { it.label })
    }

    @Test fun clockPadsMinutesAndKeepsHours() {
        assertEquals("01:24", formatRunClock(84_999, Locale.US))
        assertEquals("1:02:03", formatRunClock(3_723_000, Locale.US))
        assertEquals("00:00", formatRunClock(-1, Locale.US))
    }
}
