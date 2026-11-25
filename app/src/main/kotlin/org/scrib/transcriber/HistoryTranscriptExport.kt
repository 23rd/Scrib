package org.scrib.transcriber

private val plainHistoryFormats = listOf(TranscriptFormat.TXT)
private val timedHistoryFormats = plainHistoryFormats +
    listOf(TranscriptFormat.SRT, TranscriptFormat.VTT, TranscriptFormat.LRC)

// A partial set of timestamps would silently drop words from a timed export. Leave it as TXT
// unless all nonblank cues have complete bounds; never synthesize times from the stored text.
private fun TranscriptHistory.Entry.timedCues(): List<TranscriptSegment>? =
    segments.filter { it.text.isNotBlank() }.takeIf { cues ->
        cues.isNotEmpty() && cues.all { it.startMs >= 0 && it.endMs > it.startMs }
    }

internal fun TranscriptHistory.Entry.exportFormats(): List<TranscriptFormat> =
    if (timedCues() == null) plainHistoryFormats else timedHistoryFormats

internal fun TranscriptHistory.Entry.formatForExport(format: TranscriptFormat): String? = when (format) {
    TranscriptFormat.TXT -> text
    TranscriptFormat.SRT, TranscriptFormat.VTT, TranscriptFormat.LRC -> timedCues()?.format(format)
    TranscriptFormat.WORDS -> null // history does not persist word timings
}
