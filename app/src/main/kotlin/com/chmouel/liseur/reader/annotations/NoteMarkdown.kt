package com.chmouel.liseur.reader.annotations

/**
 * The small piece of Markdown a note may carry: `**bold**`, `*italic*`
 * (or `_italic_`), `-` bullet lists, `1.` numbered lists and `>` quotes.
 *
 * Notes are stored, synced and exported as the raw text, so a note keeps
 * reading sensibly in an app that shows it as plain text. Only Liseur
 * draws the formatting. Line breaks are kept as typed: a note is a few
 * thoughts jotted down, not a document whose lines should reflow.
 *
 * Anything that does not close, or does not sit against a word, is left
 * as the characters it is, so `2 * 3 * 4` stays arithmetic.
 */
object NoteMarkdown {

    enum class LineKind { TEXT, BULLET, NUMBERED, QUOTE }

    /** A `**`/`*`/`_` pair, in positions of the raw text. */
    data class Emphasis(
        val openStart: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val closeEnd: Int,
        val bold: Boolean,
    )

    /**
     * One line of a note. [markerEnd] is where its list or quote marker
     * (and the space after it) ends; for plain text it is [contentStart].
     */
    data class Line(
        val start: Int,
        val end: Int,
        val kind: LineKind,
        val indent: Int,
        val contentStart: Int,
        val number: Int? = null,
        val delimiter: Char? = null,
        val bullet: Char? = null,
    )

    data class Parsed(
        val lines: List<Line>,
        val emphasis: List<Emphasis>,
        val escapes: List<Int>,
    )

    enum class Style { BOLD, ITALIC, QUIET, MARKER }

    /** A style laid over `[start, end)`. */
    data class Styled(val start: Int, val end: Int, val style: Style)

    /** A note made ready to show: the markers gone, the styles to apply. */
    data class Rendered(val text: String, val styles: List<Styled>)

    private val BULLET = Regex("""^([ \t]*)([-*+•])[ \t]+""")
    private val NUMBERED = Regex("""^([ \t]*)(\d{1,9})([.)])[ \t]+""")
    private val QUOTE = Regex("""^([ \t]*)>[ \t]?""")
    private const val ESCAPABLE = "*_\\"

    fun parse(text: String): Parsed {
        val lines = mutableListOf<Line>()
        val emphasis = mutableListOf<Emphasis>()
        val escapes = mutableListOf<Int>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = line(text, start, newline)
            lines += line
            inline(text, line.contentStart, newline, emphasis, escapes)
            start = newline + 1
        }
        return Parsed(lines, emphasis.sortedBy { it.openStart }, escapes)
    }

    private fun line(text: String, start: Int, end: Int): Line {
        val raw = text.substring(start, end)
        NUMBERED.find(raw)?.let { match ->
            return Line(
                start, end, LineKind.NUMBERED,
                indent = match.groupValues[1].length,
                contentStart = start + match.range.last + 1,
                number = match.groupValues[2].toIntOrNull(),
                delimiter = match.groupValues[3].single(),
            )
        }
        BULLET.find(raw)?.let { match ->
            return Line(
                start, end, LineKind.BULLET,
                indent = match.groupValues[1].length,
                contentStart = start + match.range.last + 1,
                bullet = match.groupValues[2].single(),
            )
        }
        QUOTE.find(raw)?.let { match ->
            return Line(
                start, end, LineKind.QUOTE,
                indent = match.groupValues[1].length,
                contentStart = start + match.range.last + 1,
            )
        }
        return Line(start, end, LineKind.TEXT, indent = 0, contentStart = start)
    }

    /**
     * Finds the emphasis between [from] and [to], the way the common cases
     * of CommonMark do: an opener sits against the word after it, a closer
     * against the word before it, `**` is tried before `*` so `***x***`
     * is both, and a closing `**` is the last two of a run of stars.
     */
    private fun inline(
        text: String,
        from: Int,
        to: Int,
        out: MutableList<Emphasis>,
        escapes: MutableList<Int>,
    ) {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\\' && i + 1 < to && text[i + 1] in ESCAPABLE) {
                escapes += i
                i += 2
                continue
            }
            if (c == '*' && text.startsWith("**", i) && opens(text, i + 2, to)) {
                val close = closingDouble(text, i + 2, to)
                if (close != null) {
                    out += Emphasis(i, i + 2, close, close + 2, bold = true)
                    inline(text, i + 2, close, out, escapes)
                    i = close + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && opens(text, i + 1, to) && (c == '*' || wordBoundary(text, i - 1))) {
                val close = closingSingle(text, c, i + 1, to)
                if (close != null) {
                    out += Emphasis(i, i + 1, close, close + 1, bold = false)
                    inline(text, i + 1, close, out, escapes)
                    i = close + 1
                    continue
                }
            }
            i++
        }
    }

    private fun opens(text: String, at: Int, to: Int): Boolean =
        at < to && !text[at].isWhitespace()

    private fun closes(text: String, at: Int, contentStart: Int): Boolean =
        at > contentStart && !text[at - 1].isWhitespace()

    private fun closingDouble(text: String, from: Int, to: Int): Int? {
        var j = from + 1
        while (j + 1 < to) {
            if (text[j] == '\\') {
                j += 2
                continue
            }
            if (text[j] == '*' && text[j + 1] == '*' && (j + 2 >= to || text[j + 2] != '*') &&
                closes(text, j, from)
            ) {
                return j
            }
            j++
        }
        return null
    }

    private fun closingSingle(text: String, marker: Char, from: Int, to: Int): Int? {
        var j = from + 1
        while (j < to) {
            val c = text[j]
            if (c == '\\') {
                j += 2
                continue
            }
            if (marker == '*' && c == '*' && j + 1 < to && text[j + 1] == '*') {
                // A `**` inside an italic run belongs to a bold of its own.
                j += 2
                continue
            }
            if (c == marker && closes(text, j, from) && (marker == '*' || wordBoundary(text, j + 1))) {
                return j
            }
            j++
        }
        return null
    }

    /** `_` only counts outside a word, so snake_case_names stay as they are. */
    private fun wordBoundary(text: String, at: Int): Boolean =
        at < 0 || at >= text.length || !text[at].isLetterOrDigit()

    /**
     * The styles for the raw text as it is typed: the words in bold or
     * italic, and every marker quiet so it reads as punctuation.
     */
    fun editorStyles(text: String): List<Styled> {
        val parsed = parse(text)
        val out = mutableListOf<Styled>()
        parsed.lines.forEach { line ->
            if (line.kind != LineKind.TEXT && line.contentStart > line.start + line.indent) {
                out += Styled(line.start + line.indent, line.contentStart, Style.MARKER)
            }
            if (line.kind == LineKind.QUOTE && line.end > line.contentStart) {
                out += Styled(line.contentStart, line.end, Style.QUIET)
            }
        }
        parsed.emphasis.forEach { e ->
            out += Styled(e.openStart, e.contentStart, Style.MARKER)
            if (e.contentEnd > e.contentStart) {
                out += Styled(e.contentStart, e.contentEnd, if (e.bold) Style.BOLD else Style.ITALIC)
            }
            out += Styled(e.contentEnd, e.closeEnd, Style.MARKER)
        }
        parsed.escapes.forEach { out += Styled(it, it + 1, Style.MARKER) }
        return out
    }

    /**
     * The note as it is shown: emphasis markers and escapes gone, bullets
     * drawn as `•`, numbers kept, quotes set in and quiet.
     */
    fun render(text: String): Rendered {
        val parsed = parse(text)
        val hidden = BooleanArray(text.length)
        parsed.emphasis.forEach { e ->
            for (k in e.openStart until e.contentStart) hidden[k] = true
            for (k in e.contentEnd until e.closeEnd) hidden[k] = true
        }
        parsed.escapes.forEach { hidden[it] = true }

        // Where each raw position lands in the shown text, once markers go.
        val shownAt = IntArray(text.length + 1)
        val out = StringBuilder()
        val styles = mutableListOf<Styled>()
        parsed.lines.forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            when (line.kind) {
                LineKind.TEXT -> Unit
                LineKind.BULLET -> {
                    out.append(text, line.start, line.start + line.indent)
                    val markStart = out.length
                    out.append("•\u2002")
                    styles += Styled(markStart, out.length, Style.QUIET)
                }
                LineKind.NUMBERED -> {
                    out.append(text, line.start, line.start + line.indent)
                    val markStart = out.length
                    out.append(line.number ?: 1).append(line.delimiter ?: '.').append('\u2002')
                    styles += Styled(markStart, out.length, Style.QUIET)
                }
                LineKind.QUOTE -> {
                    out.append(text, line.start, line.start + line.indent)
                    out.append('\u2003')
                }
            }
            val contentShownStart = out.length
            for (k in line.contentStart until line.end) {
                shownAt[k] = out.length
                if (!hidden[k]) out.append(text[k])
            }
            shownAt[line.end] = out.length
            if (line.kind == LineKind.QUOTE && out.length > contentShownStart) {
                styles += Styled(contentShownStart, out.length, Style.QUIET)
                styles += Styled(contentShownStart, out.length, Style.ITALIC)
            }
        }
        parsed.emphasis.forEach { e ->
            val start = shownAt[e.contentStart]
            val end = shownAt[e.contentEnd]
            if (end > start) styles += Styled(start, end, if (e.bold) Style.BOLD else Style.ITALIC)
        }
        return Rendered(out.toString(), styles)
    }
}
