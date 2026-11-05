package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.opentranscribe.api.TranscriptionRequest

class TranscriptionRequestTest {

    @Test
    fun defaultRequestMeansTheWholeFile() {
        val request = TranscriptionRequest()
        assertNull(request.rangeOrNull())
    }

    @Test
    fun nullRequestMeansTheWholeFile() {
        val request: TranscriptionRequest? = null
        assertNull(request.rangeOrNull())
    }

    @Test
    fun zeroEndBeatsANonzeroStart() {
        val request = TranscriptionRequest()
        request.startMs = 1000
        assertNull(request.rangeOrNull())
    }

    @Test
    fun rangeKeepsBothBounds() {
        val request = TranscriptionRequest()
        request.startMs = 1000
        request.endMs = 6000
        assertEquals(AudioRange(1000, 6000), request.rangeOrNull())
    }

    @Test
    fun negativeStartClampsToTheBeginning() {
        val request = TranscriptionRequest()
        request.startMs = -500
        request.endMs = 6000
        assertEquals(AudioRange(0, 6000), request.rangeOrNull())
    }

    @Test
    fun emptyRangeIsRejected() {
        val request = TranscriptionRequest()
        request.startMs = 6000
        request.endMs = 6000
        assertThrows(IllegalArgumentException::class.java) { request.rangeOrNull() }
    }

    @Test
    fun filterKeepsStraddlingSegments() {
        val segments = listOf(
            TranscriptSegment(0, 3000, "before"),
            TranscriptSegment(4500, 7500, "straddler"),
            TranscriptSegment(9000, 12000, "after")
        )
        val kept = segments.filterOverlapping(AudioRange(5000, 6000))
        assertEquals(listOf("straddler"), kept.map { it.text })
    }

    @Test
    fun filterWithoutRangeKeepsEverything() {
        val segments = listOf(TranscriptSegment(0, 1000, "a"), TranscriptSegment(2000, 3000, "b"))
        assertEquals(segments, segments.filterOverlapping(null))
    }
}
