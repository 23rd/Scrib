package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRangeTest {

    private fun pcm(samples: Int) = pcmBuffer(FloatArray(samples) { it.toFloat() })

    @Test
    fun wholeAudioWithRangeShiftsTimestampsToOriginalFileTimeOnce() {
        val audio = GgmlTranscriptionEngine.RunAudio.whole(pcm(4 * 16_000), 4 * 16_000, 30_000L)
        val result = GgmlTranscriptionEngine.RunResult(
            "hello world",
            listOf(
                TranscribeSegmentData(
                    1_000, 2_500, "hello world",
                    listOf(TranscribeWordData(1_000, 1_500, "hello"), TranscribeWordData(2_000, 2_500, "world"))
                )
            )
        )
        val segments = audio.segmentsOf(result, 0, 4 * 16_000) { it }

        assertEquals(31_000L, segments[0].startMs)
        assertEquals(32_500L, segments[0].endMs)
        assertEquals(31_000L, segments[0].words[0].startMs)
        assertEquals(31_500L, segments[0].words[0].endMs)
        assertEquals(32_000L, segments[0].words[1].startMs)
        assertEquals(32_500L, segments[0].words[1].endMs)
    }

    @Test
    fun wholeAudioWithRangeShiftsWindowedSegmentsByRangeStartPlusWindowOffset() {
        val audio = GgmlTranscriptionEngine.RunAudio.whole(pcm(4 * 16_000), 4 * 16_000, 30_000L)
        val result = GgmlTranscriptionEngine.RunResult(
            "tail",
            listOf(TranscribeSegmentData(500, 1_500, "tail"))
        )
        // A second window starting one second into the buffer: window offsets stay buffer-relative
        // and baseMs is added exactly once, so the segment lands at 30s + 1s + 0.5s.
        val segments = audio.segmentsOf(result, 16_000, 16_000) { it }

        assertEquals(31_500L, segments[0].startMs)
        assertEquals(32_500L, segments[0].endMs)
    }

    @Test
    fun wholeAudioWithoutRangeKeepsBufferRelativeTimestamps() {
        val audio = GgmlTranscriptionEngine.RunAudio.whole(pcm(4 * 16_000), 4 * 16_000)
        val result = GgmlTranscriptionEngine.RunResult(
            "hello",
            listOf(TranscribeSegmentData(1_000, 2_500, "hello"))
        )
        val segments = audio.segmentsOf(result, 0, 4 * 16_000) { it }

        assertEquals(1_000L, segments[0].startMs)
        assertEquals(2_500L, segments[0].endMs)
    }

    @Test
    fun fallbackSegmentWithoutNativeSegmentsStillRebasesThroughBase() {
        val audio = GgmlTranscriptionEngine.RunAudio.whole(pcm(4 * 16_000), 4 * 16_000, 30_000L)
        val result = GgmlTranscriptionEngine.RunResult("one undivided utterance", emptyList())
        val segments = audio.segmentsOf(result, 16_000, 16_000) { it }

        assertEquals(31_000L, segments[0].startMs)
        assertEquals(32_000L, segments[0].endMs)
    }

    @Test
    fun vadCompactedAudioWithRangeMapsSpansFirstAndAddsRangeStartOnce() {
        // Ten seconds with two speech stretches, packed into six seconds of buffer; the range
        // starts two minutes into the original file.
        val spans = listOf(VadSpan(1_000, 4_000), VadSpan(6_000, 9_000))
        val audio = GgmlTranscriptionEngine.RunAudio.compact(
            pcm(10 * 16_000), 10 * 16_000, spans, 120_000L
        )!!
        val result = GgmlTranscriptionEngine.RunResult(
            "speech",
            listOf(
                TranscribeSegmentData(
                    0, 6_000, "speech",
                    listOf(TranscribeWordData(1_000, 2_000, "speech"))
                )
            )
        )
        val segments = audio.segmentsOf(result, 0, audio.sampleCount) { it }

        // Packed 0ms sits in the first span (buffer 1.0s) -> 121s; the packed buffer ends at 6.0s,
        // the end of the second span (buffer 9.0s) -> 129s.
        assertEquals(121_000L, segments[0].startMs)
        assertEquals(129_000L, segments[0].endMs)
        assertEquals(122_000L, segments[0].words[0].startMs)
        assertEquals(123_000L, segments[0].words[0].endMs)
    }

    @Test
    fun vadCompactedAudioWithoutRangeKeepsDecodedFileTime() {
        val spans = listOf(VadSpan(1_000, 4_000), VadSpan(6_000, 9_000))
        val audio = GgmlTranscriptionEngine.RunAudio.compact(
            pcm(10 * 16_000), 10 * 16_000, spans
        )!!
        val result = GgmlTranscriptionEngine.RunResult(
            "speech",
            listOf(TranscribeSegmentData(0, 6_000, "speech"))
        )
        val segments = audio.segmentsOf(result, 0, audio.sampleCount) { it }

        assertEquals(1_000L, segments[0].startMs)
        assertEquals(9_000L, segments[0].endMs)
    }

    @Test
    fun overlapPredicateKeepsOnlySegmentsTouchingTheHalfOpenRange() {
        val range = AudioRange(10_000, 20_000)
        assertTrue(TranscriptSegment(9_000, 10_500, "x").overlapsRange(range))
        assertTrue(TranscriptSegment(19_500, 21_000, "x").overlapsRange(range))
        assertTrue(TranscriptSegment(5_000, 25_000, "x").overlapsRange(range))
        assertTrue(TranscriptSegment(12_000, 15_000, "x").overlapsRange(range))
        // Touching exactly the half-open bounds is not overlapping.
        assertFalse(TranscriptSegment(0, 10_000, "x").overlapsRange(range))
        assertFalse(TranscriptSegment(20_000, 25_000, "x").overlapsRange(range))
        assertFalse(TranscriptSegment(0, 5_000, "x").overlapsRange(range))
        assertFalse(TranscriptSegment(25_000, 30_000, "x").overlapsRange(range))
    }

    @Test
    fun rebasingShiftsSegmentsAndWordsByTheRangeStartOnce() {
        val segments = listOf(
            TranscriptSegment(
                1_000, 2_000, "hi",
                words = listOf(TranscriptWord(1_000, 1_500, "hi"))
            )
        )
        val rebased = segments.rebasedToOriginal(AudioRange(30_000, 40_000))

        assertEquals(31_000L, rebased[0].startMs)
        assertEquals(32_000L, rebased[0].endMs)
        assertEquals(31_000L, rebased[0].words[0].startMs)
        assertEquals(31_500L, rebased[0].words[0].endMs)
    }

    @Test
    fun rebasingWithoutARangeOrWithAZeroStartChangesNothing() {
        val segments = listOf(TranscriptSegment(1_000, 2_000, "hi"))
        assertTrue(segments.rebasedToOriginal(null) === segments)
        assertTrue(segments.rebasedToOriginal(AudioRange(0, 40_000)) === segments)
    }
}
