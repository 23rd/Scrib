package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointTimeoutTest {

    @Test
    fun shortRecordingsKeepTheTenMinuteFloor() {
        assertEquals(600_000, EndpointHttp.readTimeoutMs(0))
        assertEquals(600_000, EndpointHttp.readTimeoutMs(200_000))
    }

    @Test
    fun longRecordingsGetThreeTimesTheirLength() {
        assertEquals(900_000, EndpointHttp.readTimeoutMs(300_000))
        assertEquals(2_460_000, EndpointHttp.readTimeoutMs(820_000))
    }

    @Test
    fun absurdDurationsDoNotWrapNegative() {
        assertEquals(Int.MAX_VALUE, EndpointHttp.readTimeoutMs(Long.MAX_VALUE / 3))
    }

    @Test
    fun durationReadsTheWavHeader() {
        assertEquals(5_000, WavWriter.durationMs(WavWriter.silence(16_000, 5_000)))
        assertEquals(0, WavWriter.durationMs(ByteArray(10)))
    }
}
