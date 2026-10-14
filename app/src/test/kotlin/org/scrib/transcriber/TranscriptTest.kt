package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun testNoSplitForShortSegment() {
        val words = listOf(
            TranscriptWord(0, 500, "Hello"),
            TranscriptWord(600, 1000, "world")
        )
        val segment = TranscriptSegment(0, 1000, "Hello world", words = words)
        val result = splitLongSegments(listOf(segment))

        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(1000L, result[0].endMs)
        assertEquals(2, result[0].words.size)
    }

    @Test
    fun testSplitLongSegmentByWordGap() {
        val words = listOf(
            TranscriptWord(0, 800, "First"),
            TranscriptWord(900, 1500, "part"),
            // Pause 1500ms to 2300ms: gap = 800ms >= SEGMENT_GAP_MS (700ms)
            TranscriptWord(2300, 3000, "second"),
            TranscriptWord(3100, 3800, "part")
        )
        val segment = TranscriptSegment(0, 3800, "First part second part", words = words)
        val result = splitLongSegments(listOf(segment))

        assertEquals(2, result.size)
        assertEquals("First part", result[0].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(1500L, result[0].endMs)
        assertEquals(listOf("First", "part"), result[0].words.map { it.text })

        assertEquals(" second part", result[1].text)
        assertEquals(2300L, result[1].startMs)
        assertEquals(3800L, result[1].endMs)
        assertEquals(listOf("second", "part"), result[1].words.map { it.text })
    }

    @Test
    fun testSplitLongSegmentByMaxDuration() {
        // Continuous speech over 8 seconds with tiny 50ms gaps
        val words = (0 until 16).map { i ->
            val start = i * 500L
            TranscriptWord(start, start + 450L, "word$i")
        }
        val segment = TranscriptSegment(0, 8000, words.joinToString(" ") { it.text }, words = words)
        val result = splitLongSegments(listOf(segment))

        assertTrue(result.size >= 2)
        for (sub in result) {
            assertTrue("Subsegment duration ${sub.endMs - sub.startMs} should be <= $MAX_SEGMENT_MS",
                sub.endMs - sub.startMs <= MAX_SEGMENT_MS)
        }
        // Total words across subsegments must equal original words
        assertEquals(words.size, result.sumOf { it.words.size })
    }

    @Test
    fun testSplitLongSegmentByMaxLineChars() {
        // Words that together exceed 84 characters without long pauses
        val words = listOf(
            TranscriptWord(0, 400, "This"),
            TranscriptWord(450, 800, "is"),
            TranscriptWord(850, 1200, "a"),
            TranscriptWord(1250, 1600, "substantially"),
            TranscriptWord(1650, 2000, "long"),
            TranscriptWord(2050, 2400, "sentence"),
            TranscriptWord(2450, 2800, "specifically"),
            TranscriptWord(2850, 3200, "engineered"),
            TranscriptWord(3250, 3600, "to"),
            TranscriptWord(3650, 4000, "exceed"),
            TranscriptWord(4050, 4400, "the"),
            TranscriptWord(4450, 4800, "character"),
            TranscriptWord(4850, 5200, "budget")
        )
        val segment = TranscriptSegment(0, 5200, words.joinToString(" ") { it.text }, words = words)
        val result = splitLongSegments(listOf(segment))

        assertTrue(result.size >= 2)
        for (sub in result) {
            assertTrue("Line length ${sub.text.trim().length} should be <= $MAX_LINE_CHARS",
                sub.text.trim().length <= MAX_LINE_CHARS)
        }
    }

    @Test
    fun testSplitLongSegmentBySoftSentenceBoundary() {
        // Sentence ending at 4200ms (> SOFT_MAX_SEGMENT_MS 4000ms), followed by more words up to 5500ms
        val words = listOf(
            TranscriptWord(0, 1000, "Welcome"),
            TranscriptWord(1100, 2000, "everyone"),
            TranscriptWord(2100, 3000, "to"),
            TranscriptWord(3100, 4200, "Scrib."),
            TranscriptWord(4300, 4800, "Let"),
            TranscriptWord(4900, 5200, "us"),
            TranscriptWord(5300, 5500, "begin.")
        )
        val segment = TranscriptSegment(0, 5500, words.joinToString(" ") { it.text }, words = words)
        val result = splitLongSegments(listOf(segment))

        assertEquals(2, result.size)
        assertEquals("Welcome everyone to Scrib.", result[0].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(4200L, result[0].endMs)

        assertEquals(" Let us begin.", result[1].text)
        assertEquals(4300L, result[1].startMs)
        assertEquals(5500L, result[1].endMs)
    }

    @Test
    fun testFinishSegmentsParagraphMarkingOnWordGaps() {
        // Parakeet 30s window with a 2-second pause inside
        val words = listOf(
            TranscriptWord(1000, 2000, "Paragraph"),
            TranscriptWord(2100, 3000, "one."),
            // 2000ms pause from 3000ms to 5000ms (>= PARAGRAPH_GAP_MS 1500ms)
            TranscriptWord(5000, 6000, "Paragraph"),
            TranscriptWord(6100, 7000, "two.")
        )
        val monolithicSegment = TranscriptSegment(0, 30000, "Paragraph one. Paragraph two.", words = words)
        val finished = finishSegments(listOf(monolithicSegment))

        assertEquals(2, finished.size)
        assertFalse(finished[0].startsParagraph)
        assertTrue(finished[1].startsParagraph)

        val plainText = finished.format(TranscriptFormat.TXT)
        assertEquals("Paragraph one.\n\nParagraph two.", plainText)
    }

    @Test
    fun testSplitTextFallbackWithoutWords() {
        // A long text without word timestamps (e.g. Sherpa without VAD)
        val longText = "First sentence of the text. Second sentence that provides additional context. Third concluding sentence."
        val segment = TranscriptSegment(0, 20000, longText, words = emptyList())
        val result = splitLongSegments(listOf(segment))

        assertTrue(result.size >= 2)
        for (sub in result) {
            assertTrue(sub.text.trim().isNotEmpty())
            assertTrue(sub.startMs >= 0)
            assertTrue(sub.endMs <= 20000)
            assertTrue(sub.startMs <= sub.endMs)
        }
        val formatted = result.format(TranscriptFormat.TXT)
        assertEquals(longText, formatted)
    }

    @Test
    fun testFormattingOutputs() {
        val words = listOf(
            TranscriptWord(1000, 2000, "Hello"),
            TranscriptWord(2100, 3000, "world"),
            TranscriptWord(5000, 6000, "New"),
            TranscriptWord(6100, 7000, "cue")
        )
        val segment = TranscriptSegment(0, 10000, "Hello world New cue", words = words)
        val finished = finishSegments(listOf(segment))

        val srt = finished.format(TranscriptFormat.SRT)
        assertTrue(srt.contains("1\n00:00:01,000 --> 00:00:03,000\nHello world"))
        assertTrue(srt.contains("2\n00:00:05,000 --> 00:00:07,000\nNew cue"))

        val vtt = finished.format(TranscriptFormat.VTT)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.000\nHello world"))

        val wordsTxt = finished.format(TranscriptFormat.WORDS)
        assertTrue(wordsTxt.contains("00:01,000 --> 00:02,000 Hello"))
        assertTrue(wordsTxt.contains("00:06,100 --> 00:07,000 cue"))
    }

    @Test
    fun testTransformTextAppliedToSubsegments() {
        val words = listOf(
            TranscriptWord(0, 1000, "I"),
            TranscriptWord(1100, 2000, "use"),
            TranscriptWord(2100, 3000, "git"),
            TranscriptWord(5000, 6000, "and"),
            TranscriptWord(6100, 7000, "GitHub")
        )
        val segment = TranscriptSegment(0, 7000, "I use git and GitHub", words = words)
        val finished = finishSegments(listOf(segment)) { text ->
            text.replace("git", "Git")
        }

        assertEquals(2, finished.size)
        assertEquals("I use Git", finished[0].text)
        assertEquals(" and GitHub", finished[1].text)
    }
}
