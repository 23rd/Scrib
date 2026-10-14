package org.scrib.transcriber

import java.util.Locale

// One decoded utterance and where it sits in the recording. startsParagraph marks the ones that
// open a new turn or thought — see markParagraphs, which is the only thing that decides it.
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val startsParagraph: Boolean = false,
    val words: List<TranscriptWord> = emptyList()
)

data class TranscriptWord(val startMs: Long, val endMs: Long, val text: String)

// A stretch of the recording that held speech, as heard by the VAD.
data class SpeechSpan(val startMs: Long, val endMs: Long)

// The text formats whisper.cpp itself writes, so a transcript can go straight into a subtitle
// track or a lyrics-style note instead of arriving as one undifferentiated block.
enum class TranscriptFormat(val extension: String) {
    TXT("txt"),
    SRT("srt"),
    VTT("vtt"),
    LRC("lrc"),
    WORDS("txt")
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
    TranscriptFormat.WORDS -> {
        val hours = any { it.endMs >= 3_600_000 }
        flatMap { it.words }.joinToString(separator = "\n", postfix = "\n") {
            "${wordClock(it.startMs, hours)} --> ${wordClock(it.endMs, hours)} ${it.text.trim()}"
        }
    }
}

private fun wordClock(ms: Long, hours: Boolean): String {
    val t = ms.coerceAtLeast(0)
    return if (hours) clock(t, ',') else String.format(Locale.ROOT, "%02d:%02d,%03d", t / 60_000, t / 1000 % 60, t % 1000)
}

// A pause long enough to read as a change of speaker or of thought. Whisper's segment bounds are
// coarse and someone pausing mid-thought looks the same as a handover, so this errs long: a missed
// break reads better than one dropped into the middle of a sentence.
private const val PARAGRAPH_GAP_MS = 1500L

internal const val SEGMENT_GAP_MS = 700L
internal const val SOFT_MAX_SEGMENT_MS = 4000L
internal const val MAX_SEGMENT_MS = 6000L
internal const val MAX_LINE_CHARS = 84

fun finishSegments(
    segments: List<TranscriptSegment>,
    speech: List<SpeechSpan> = emptyList(),
    transformText: (String) -> String = { it }
): List<TranscriptSegment> = markParagraphs(splitLongSegments(segments, transformText), speech)

// Splits overlong segments (such as monolithic Parakeet windows) into subtitle-sized cues using
// word timestamps when available, or punctuation/space boundaries when words are absent.
fun splitLongSegments(
    segments: List<TranscriptSegment>,
    transformText: (String) -> String = { it }
): List<TranscriptSegment> {
    if (segments.isEmpty()) {
        return emptyList()
    }
    val result = ArrayList<TranscriptSegment>(segments.size)
    for (segment in segments) {
        val split = splitSegment(segment, isFirstInTranscript = result.isEmpty(), transformText = transformText)
        result.addAll(split)
    }
    return result
}

private fun splitSegment(
    segment: TranscriptSegment,
    isFirstInTranscript: Boolean,
    transformText: (String) -> String
): List<TranscriptSegment> {
    return if (segment.words.isNotEmpty()) {
        splitWords(segment, isFirstInTranscript, transformText)
    } else {
        splitTextFallback(segment, isFirstInTranscript, transformText)
    }
}

private fun splitWords(
    segment: TranscriptSegment,
    isFirstInTranscript: Boolean,
    transformText: (String) -> String
): List<TranscriptSegment> {
    val words = segment.words
    if (words.isEmpty()) {
        return splitTextFallback(segment, isFirstInTranscript, transformText)
    }

    val result = ArrayList<TranscriptSegment>()
    val currentWords = ArrayList<TranscriptWord>()
    var currentChars = 0

    fun flush() {
        if (currentWords.isEmpty()) {
            return
        }
        val subStart = currentWords.first().startMs
        val subEnd = currentWords.last().endMs
        val rawText = transformText(currentWords.joinToString(" ") { it.text.trim() }.trim())
        val isFirst = isFirstInTranscript && result.isEmpty()
        val formattedText = if (isFirst) rawText else " $rawText"
        val startsPara = if (result.isEmpty()) segment.startsParagraph else false
        result.add(
            TranscriptSegment(
                startMs = subStart,
                endMs = maxOf(subStart, subEnd),
                text = formattedText,
                startsParagraph = startsPara,
                words = ArrayList(currentWords)
            )
        )
        currentWords.clear()
        currentChars = 0
    }

    for (word in words) {
        val wordText = word.text.trim()
        if (wordText.isEmpty()) {
            continue
        }

        if (currentWords.isNotEmpty()) {
            val previousEnd = currentWords.last().endMs
            val gap = word.startMs - previousEnd
            val currentDuration = previousEnd - currentWords.first().startMs
            val totalDurationWithWord = word.endMs - currentWords.first().startMs
            val wouldChars = currentChars + 1 + wordText.length

            val previousText = currentWords.last().text.trim()
            val endsSentence = previousText.endsWith('.') || previousText.endsWith('!') ||
                previousText.endsWith('?') || previousText.endsWith(';') || previousText.endsWith(':')

            val isGapBreak = gap >= SEGMENT_GAP_MS
            val isDurationBreak = totalDurationWithWord > MAX_SEGMENT_MS
            val isCharBreak = wouldChars > MAX_LINE_CHARS
            val isSentenceBreak = currentDuration >= SOFT_MAX_SEGMENT_MS && endsSentence

            if (isGapBreak || isDurationBreak || isCharBreak || isSentenceBreak) {
                flush()
            }
        }

        currentWords.add(word)
        currentChars += if (currentWords.size == 1) wordText.length else 1 + wordText.length
    }
    flush()

    return if (result.isEmpty()) {
        splitTextFallback(segment, isFirstInTranscript, transformText)
    } else {
        result
    }
}

private fun splitTextFallback(
    segment: TranscriptSegment,
    isFirstInTranscript: Boolean,
    transformText: (String) -> String
): List<TranscriptSegment> {
    val trimmed = segment.text.trim()
    if (trimmed.isEmpty()) {
        return emptyList()
    }
    val duration = (segment.endMs - segment.startMs).coerceAtLeast(1L)
    if (duration <= MAX_SEGMENT_MS && trimmed.length <= MAX_LINE_CHARS) {
        val transformed = transformText(trimmed)
        val text = if (isFirstInTranscript) transformed else " $transformed"
        return listOf(segment.copy(text = text))
    }

    val result = ArrayList<TranscriptSegment>()
    var start = 0
    val totalLength = trimmed.length

    while (start < totalLength) {
        while (start < totalLength && trimmed[start].isWhitespace()) {
            start++
        }
        if (start >= totalLength) break

        val maxEnd = minOf(totalLength, start + MAX_LINE_CHARS)
        var cut = -1

        val remainingLen = totalLength - start
        val estimatedRemainingMs = remainingLen.toLong() * duration / totalLength
        if (remainingLen <= MAX_LINE_CHARS && estimatedRemainingMs <= MAX_SEGMENT_MS) {
            cut = totalLength
        } else {
            val minChars = minOf(20, remainingLen)
            val searchFrom = minOf(start + minChars, maxEnd)

            for (i in maxEnd downTo searchFrom) {
                val c = trimmed[i - 1]
                if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                    if (i == totalLength || trimmed[i].isWhitespace() || c == '。' || c == '！' || c == '？') {
                        cut = i
                        break
                    }
                }
            }

            if (cut < 0) {
                for (i in maxEnd downTo searchFrom) {
                    val c = trimmed[i - 1]
                    if (c == ',' || c == ';' || c == ':' || c == '，' || c == '、' || c == '；' || c == '：') {
                        if (i == totalLength || trimmed[i].isWhitespace() || c == '，' || c == '、' || c == '；' || c == '：') {
                            cut = i
                            break
                        }
                    }
                }
            }

            if (cut < 0) {
                for (i in maxEnd downTo searchFrom) {
                    if (trimmed[i - 1].isWhitespace()) {
                        cut = i - 1
                        break
                    }
                }
            }

            if (cut < 0) {
                cut = maxEnd
            }
        }

        val rawChunk = trimmed.substring(start, cut).trim()
        val chunkText = transformText(rawChunk)
        if (chunkText.isNotEmpty()) {
            val chunkStartMs = segment.startMs + (start.toLong() * duration / totalLength)
            val chunkEndMs = segment.startMs + (cut.toLong() * duration / totalLength)
            val isFirst = isFirstInTranscript && result.isEmpty()
            val text = if (isFirst) chunkText else " $chunkText"
            val startsPara = if (result.isEmpty()) segment.startsParagraph else false
            result.add(
                TranscriptSegment(
                    startMs = chunkStartMs,
                    endMs = maxOf(chunkStartMs, chunkEndMs),
                    text = text,
                    startsParagraph = startsPara
                )
            )
        }
        start = cut
    }

    return if (result.isEmpty()) {
        val transformed = transformText(trimmed)
        listOf(segment.copy(text = if (isFirstInTranscript) transformed else " $transformed"))
    } else {
        result
    }
}

// Decides which segments open a paragraph. Two sources, because they never both apply: with no
// VAD the decoded segments carry the pauses between them, and the gap is the whole story. With a
// VAD the segments run edge to edge — a removed silence lands inside one of them, never between
// two — so the gap is always zero and whisper's own speech spans are what is left to read. Each
// long enough silence breaks at the first segment that starts once the speech resumes.
fun markParagraphs(segments: List<TranscriptSegment>, speech: List<SpeechSpan> = emptyList()): List<TranscriptSegment> {
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
