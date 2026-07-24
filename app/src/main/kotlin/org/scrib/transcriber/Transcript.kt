package org.scrib.transcriber

import java.util.Locale

// One decoded utterance and where it sits in the recording.
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

// The text formats whisper.cpp itself writes, so a transcript can go straight into a subtitle
// track or a lyrics-style note instead of arriving as one undifferentiated block.
enum class TranscriptFormat(val extension: String) {
    TXT("txt"),
    SRT("srt"),
    VTT("vtt"),
    LRC("lrc")
}

fun List<TranscriptSegment>.format(format: TranscriptFormat): String = when (format) {
    // Whisper leaves a space in front of every segment, so plain text is a straight join.
    TranscriptFormat.TXT -> joinToString("") { it.text }.trim()
    TranscriptFormat.SRT -> mapIndexed { i, s ->
        "${i + 1}\n${clock(s.startMs, ',')} --> ${clock(s.endMs, ',')}\n${s.text.trim()}"
    }.joinToString(separator = "\n\n", postfix = "\n")
    TranscriptFormat.VTT -> map { s ->
        "${clock(s.startMs, '.')} --> ${clock(s.endMs, '.')}\n${s.text.trim()}"
    }.joinToString(separator = "\n\n", prefix = "WEBVTT\n\n", postfix = "\n")
    TranscriptFormat.LRC -> joinToString(separator = "\n", postfix = "\n") {
        "[${lrcClock(it.startMs)}] ${it.text.trim()}"
    }
}

// hh:mm:ss,mmm — SRT separates the milliseconds with a comma, WebVTT with a dot. Formatted in the
// root locale: a phone set to Arabic or Persian digits would otherwise write timestamps no player
// can parse.
private fun clock(ms: Long, decimal: Char): String {
    val t = ms.coerceAtLeast(0)
    return String.format(
        Locale.ROOT, "%02d:%02d:%02d%c%03d",
        t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, decimal, t % 1000
    )
}

// mm:ss.cc, with the minutes counting on past an hour the way LRC players expect.
private fun lrcClock(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%02d", t / 60_000, t / 1000 % 60, t % 1000 / 10)
}
