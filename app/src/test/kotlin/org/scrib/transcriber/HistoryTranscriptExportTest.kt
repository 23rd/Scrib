package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTranscriptExportTest {
    private val timed = entry(
        TranscriptSegment(1250, 3000, " Alpha phrase "),
        TranscriptSegment(4120, 5000, " Second phrase ")
    )

    @Test fun timedFormatsKeepOriginalCueBounds() {
        assertEquals(
            listOf(TranscriptFormat.TXT, TranscriptFormat.SRT, TranscriptFormat.VTT, TranscriptFormat.LRC),
            timed.exportFormats()
        )
        assertEquals(
            "1\n00:00:01,250 --> 00:00:03,000\nAlpha phrase\n\n" +
                "2\n00:00:04,120 --> 00:00:05,000\nSecond phrase\n",
            timed.formatForExport(TranscriptFormat.SRT)
        )
        assertEquals(
            "WEBVTT\n\n00:00:01.250 --> 00:00:03.000\nAlpha phrase\n\n" +
                "00:00:04.120 --> 00:00:05.000\nSecond phrase\n",
            timed.formatForExport(TranscriptFormat.VTT)
        )
        assertEquals(
            "[00:01.25] Alpha phrase\n[00:04.12] Second phrase\n",
            timed.formatForExport(TranscriptFormat.LRC)
        )
        assertNull(timed.formatForExport(TranscriptFormat.WORDS))
    }

    @Test fun txtPreservesSavedTextForBothKindsOfEntry() {
        assertEquals("Stored paragraph one.\n\nStored paragraph two.\n", timed.formatForExport(TranscriptFormat.TXT))
        assertEquals(timed.text, entry().formatForExport(TranscriptFormat.TXT))
    }

    @Test fun legacyAndIncompleteTimingOnlyExposeTxt() {
        val noTimes = listOf(
            entry(),
            entry(TranscriptSegment(0, 0, "No time")),
            entry(TranscriptSegment(-1, 900, "Missing start")),
            entry(TranscriptSegment(0, -1, "Missing end")),
            entry(TranscriptSegment(1200, 900, "Inverted duration")),
            entry(TranscriptSegment(0, 800, "Valid cue"), TranscriptSegment(-1, 900, "Untimed text"))
        )
        for (record in noTimes) {
            assertEquals(listOf(TranscriptFormat.TXT), record.exportFormats())
            for (format in TranscriptFormat.entries.filterNot { it == TranscriptFormat.TXT }) {
                assertNull(record.formatForExport(format))
            }
        }
    }

    private fun entry(vararg segments: TranscriptSegment) = TranscriptHistory.Entry(
        id = "fixture",
        savedAtMs = 0,
        fileName = "fixture.wav",
        modelName = null,
        text = "Stored paragraph one.\n\nStored paragraph two.\n",
        segments = segments.toList()
    )
}
