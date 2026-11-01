package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.opentranscribe.api.StreamRequest

class StreamRequestTest {

    @Test
    fun defaultStreamRequestHasZeroMaxSegmentDuration() {
        val request = StreamRequest()
        assertNull(request.languageHint)
        assertEquals(0, request.sampleRate)
        assertEquals(0, request.channels)
        assertEquals(0, request.maxSegmentDurationMs)
    }

    @Test
    fun streamRequestStoresMaxSegmentDuration() {
        val request = StreamRequest()
        request.languageHint = "en"
        request.sampleRate = 16000
        request.channels = 1
        request.maxSegmentDurationMs = 5000

        assertEquals("en", request.languageHint)
        assertEquals(16000, request.sampleRate)
        assertEquals(1, request.channels)
        assertEquals(5000, request.maxSegmentDurationMs)
    }
}
