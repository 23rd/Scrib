package org.scrib.transcriber

import org.junit.Assert.assertEquals
import org.junit.Test

class AidlThreadsTest {
    @Test fun supportedCountsRoundTrip() {
        for (choice in AidlThreads.entries) {
            assertEquals(choice, AidlThreads.fromStoredCount(choice.count ?: 0))
        }
    }

    @Test fun unsupportedValuesFallBackToAuto() {
        for (value in listOf(-1, 3, 5, Int.MAX_VALUE)) {
            assertEquals(AidlThreads.AUTO, AidlThreads.fromStoredCount(value))
        }
    }
}
