package org.scrib.transcriber

// A half-open [startMs, endMs) stretch of a recording to transcribe. A null range anywhere in the
// pipeline means the whole file.
data class AudioRange(val startMs: Long, val endMs: Long)

// A segment survives a ranged run when it sounds anywhere inside [startMs, endMs). Engines that
// honor the range never produce anything outside it; the run layer keeps this net anyway so an
// engine that ignores the range still returns correct timestamps, at the cost of wasted compute.
internal fun TranscriptSegment.overlapsRange(range: AudioRange): Boolean =
    endMs > range.startMs && startMs < range.endMs

// Engines whose segment timestamps are relative to the decoded buffer call this once on the way
// out: a ranged decode starts at startMs of the original file, so adding it once — after any
// VAD-span mapping the engine has already applied — turns buffer time into original file time.
internal fun List<TranscriptSegment>.rebasedToOriginal(range: AudioRange?): List<TranscriptSegment> {
    if (range == null || range.startMs <= 0L) {
        return this
    }
    val offset = range.startMs
    return map { segment ->
        segment.copy(
            startMs = segment.startMs + offset,
            endMs = segment.endMs + offset,
            words = segment.words.map { word ->
                word.copy(startMs = word.startMs + offset, endMs = word.endMs + offset)
            }
        )
    }
}
