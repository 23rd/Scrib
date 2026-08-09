package org.scrib.transcriber

import java.util.Locale

// One decoded utterance and where it sits in the recording. startsParagraph marks the ones that
// open a new turn or thought — see markParagraphs, which is the only thing that decides it.
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val startsParagraph: Boolean = false
)

// A stretch of the recording that held speech, as heard by the VAD.
data class SpeechSpan(val startMs: Long, val endMs: Long)

// The text formats whisper.cpp itself writes, so a transcript can go straight into a subtitle
// track or a lyrics-style note instead of arriving as one undifferentiated block.
enum class TranscriptFormat(val extension: String) {
    TXT("txt"),
    SRT("srt"),
    VTT("vtt"),
    LRC("lrc")
}

fun List<TranscriptSegment>.format(format: TranscriptFormat): String = when (format) {
    TranscriptFormat.TXT -> paragraphs()
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

// A pause long enough to read as a change of speaker or of thought. Whisper's segment bounds are
// coarse and someone pausing mid-thought looks the same as a handover, so this errs long: a missed
// break reads better than one dropped into the middle of a sentence.
private const val PARAGRAPH_GAP_MS = 1500L

// Decides which segments open a paragraph. Two sources, because they never both apply: with no
// VAD the decoded segments carry the pauses between them, and the gap is the whole story. With a
// VAD the segments run edge to edge — a removed silence lands inside one of them, never between
// two — so the gap is always zero and whisper's own speech spans are what is left to read. Each
// long enough silence breaks at the first segment that starts once the speech resumes.
fun markParagraphs(segments: List<TranscriptSegment>, speech: List<SpeechSpan>): List<TranscriptSegment> {
    if (speech.isEmpty()) {
        return segments.mapIndexed { i, segment ->
            val gap = i > 0 && segment.startMs - segments[i - 1].endMs >= PARAGRAPH_GAP_MS
            if (gap) segment.copy(startsParagraph = true) else segment
        }
    }
    val opens = HashSet<Int>()
    speech.zipWithNext { before, after ->
        if (after.startMs - before.endMs >= PARAGRAPH_GAP_MS) {
            val next = segments.indexOfFirst { it.startMs >= after.startMs }
            if (next > 0) {
                opens.add(next)
            }
        }
    }
    return segments.mapIndexed { i, segment ->
        if (opens.contains(i)) segment.copy(startsParagraph = true) else segment
    }
}

// Whisper leaves a space in front of every segment, so the text joins straight up; one that opens a
// paragraph starts a new block instead. Plain text carries no timestamps, so the pauses are the
// only structure it has — without them several people talking arrive as one unbroken block.
private fun List<TranscriptSegment>.paragraphs(): String = buildString {
    this@paragraphs.forEachIndexed { i, segment ->
        if (i > 0 && segment.startsParagraph) {
            append("\n\n")
            append(segment.text.trimStart())
        } else {
            append(segment.text)
        }
    }
}.trim()

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
