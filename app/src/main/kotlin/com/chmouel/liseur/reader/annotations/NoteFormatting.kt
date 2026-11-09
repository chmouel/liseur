package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.reader.annotations.NoteMarkdown.LineKind

/**
 * What the note editor's toolbar and Enter key do to the text, worked out
 * on plain strings so it can be checked without a screen.
 *
 * Every operation takes the text with its selection and returns the new
 * text with the selection where the reader would expect it: around the
 * same words after a toggle, after the new marker on a fresh list line.
 */
object NoteFormatting {

    data class Edit(val text: String, val start: Int, val end: Int = start) {
        val collapsed: Boolean get() = start == end
    }

    enum class Format { BOLD, ITALIC, BULLET, NUMBERED, QUOTE }

    /** The formats in force where the cursor or selection is, to light up the toolbar. */
    fun active(edit: Edit): Set<Format> {
        val parsed = NoteMarkdown.parse(edit.text)
        val out = mutableSetOf<Format>()
        if (parsed.emphasis.any { it.bold && it.covers(edit.start, edit.end) }) out += Format.BOLD
        if (parsed.emphasis.any { !it.bold && it.covers(edit.start, edit.end) }) out += Format.ITALIC
        val lines = touched(parsed.lines, edit).filter { it.end > it.start }
        if (lines.isNotEmpty()) {
            when {
                lines.all { it.kind == LineKind.BULLET } -> out += Format.BULLET
                lines.all { it.kind == LineKind.NUMBERED } -> out += Format.NUMBERED
                lines.all { it.kind == LineKind.QUOTE } -> out += Format.QUOTE
            }
        }
        return out
    }

    fun toggle(edit: Edit, format: Format): Edit = when (format) {
        Format.BOLD -> emphasis(edit, bold = true)
        Format.ITALIC -> emphasis(edit, bold = false)
        Format.BULLET -> lines(edit, LineKind.BULLET)
        Format.NUMBERED -> lines(edit, LineKind.NUMBERED)
        Format.QUOTE -> lines(edit, LineKind.QUOTE)
    }

    private fun NoteMarkdown.Emphasis.covers(start: Int, end: Int): Boolean =
        contentStart <= start && end <= contentEnd

    /**
     * Bold or italic on, or off again.
     *
     * Off when every line's part of the selection already sits in a run
     * of that kind (markers included); otherwise each part not yet in one
     * is wrapped, without the spaces at its edges, since `** word**` is
     * not bold. With nothing selected, a cursor at the end of a run
     * steps past its closing marker so typing carries on plain; elsewhere
     * in a run the run comes off; outside one an empty pair goes in with
     * the cursor between, and pressing again before typing takes it out.
     */
    private fun emphasis(edit: Edit, bold: Boolean): Edit {
        val marker = if (bold) "**" else "*"
        val parsed = NoteMarkdown.parse(edit.text)
        val runs = parsed.emphasis.filter { it.bold == bold }

        if (edit.collapsed) {
            runs.filter { it.covers(edit.start, edit.end) }.maxByOrNull { it.openStart }?.let {
                if (edit.start == it.contentEnd && it.contentEnd > it.contentStart) return Edit(edit.text, it.closeEnd)
                return unwrap(edit, listOf(it))
            }
            val before = edit.text.substring(0, edit.start)
            val after = edit.text.substring(edit.start)
            if (before.endsWith(marker) && after.startsWith(marker) &&
                !before.removeSuffix(marker).endsWith("*") && !after.removePrefix(marker).startsWith("*")
            ) {
                return Edit(before.removeSuffix(marker) + after.removePrefix(marker), edit.start - marker.length)
            }
            return Edit(before + marker + marker + after, edit.start + marker.length)
        }

        val segments = segments(edit, parsed)
        if (segments.isEmpty()) return edit
        val covering = segments.map { (from, to) ->
            runs.filter { it.openStart <= from && to <= it.closeEnd }.maxByOrNull { it.openStart }
        }
        if (covering.all { it != null }) return unwrap(edit, covering.filterNotNull().distinct())

        // Words already in this style inside the selection lose their own
        // markers first, so the new pair does not nest inside them.
        val inside = runs.filter { run -> segments.any { (from, to) -> run.openStart < to && from < run.closeEnd } }
        if (inside.isNotEmpty()) {
            val widened = Edit(
                edit.text,
                minOf(edit.start, inside.minOf { it.openStart }),
                maxOf(edit.end, inside.maxOf { it.closeEnd }),
            )
            return wrap(unwrap(widened, inside), marker)
        }
        return wrap(edit, marker, segments)
    }

    private fun wrap(edit: Edit, marker: String, segments: List<Pair<Int, Int>> = segments(edit)): Edit {
        var text = edit.text
        var start = edit.start
        var end = edit.end
        // Last part first, so the earlier positions still hold.
        for ((from, to) in segments.reversed()) {
            text = text.substring(0, from) + marker + text.substring(from, to) + marker + text.substring(to)
            if (start >= from) start += marker.length
            end += if (end > to) 2 * marker.length else marker.length
        }
        return Edit(text, start, end)
    }

    /** The selected part of each line it touches, without spaces at the edges. */
    private fun segments(edit: Edit, parsed: NoteMarkdown.Parsed = NoteMarkdown.parse(edit.text)): List<Pair<Int, Int>> =
        touched(parsed.lines, edit).mapNotNull { line ->
            var from = maxOf(edit.start, line.contentStart)
            var to = minOf(edit.end, line.end)
            while (from < to && edit.text[from].isWhitespace()) from++
            while (to > from && edit.text[to - 1].isWhitespace()) to--
            if (from < to) from to to else null
        }

    private fun unwrap(edit: Edit, runs: List<NoteMarkdown.Emphasis>): Edit {
        var text = edit.text
        var start = edit.start
        var end = edit.end
        for (run in runs.sortedByDescending { it.openStart }) {
            val open = run.contentStart - run.openStart
            val close = run.closeEnd - run.contentEnd
            text = text.removeRange(run.contentEnd, run.closeEnd).removeRange(run.openStart, run.contentStart)
            fun moved(at: Int): Int = when {
                at <= run.openStart -> at
                at <= run.contentStart -> run.openStart
                at <= run.contentEnd -> at - open
                at <= run.closeEnd -> run.contentEnd - open
                else -> at - open - close
            }
            start = moved(start)
            end = moved(end)
        }
        return Edit(text, start, end)
    }

    /**
     * A list or quote on the lines the selection touches, or off again
     * when they all already are one. Switching from one kind to another
     * replaces the marker; numbers run from 1 across the lines.
     */
    private fun lines(edit: Edit, kind: LineKind): Edit {
        val parsed = NoteMarkdown.parse(edit.text)
        val touched = touched(parsed.lines, edit)
        val written = touched.filter { it.end > it.contentStart || it.kind != LineKind.TEXT }
        val targets = written.ifEmpty { touched }
        val off = written.isNotEmpty() && written.all { it.kind == kind }

        var number = 0
        val prefixes = targets.associateWith { line ->
            val indent = edit.text.substring(line.start, line.start + line.indent)
            when {
                off -> indent
                kind == LineKind.BULLET -> "$indent- "
                kind == LineKind.NUMBERED -> "$indent${++number}. "
                else -> "$indent> "
            }
        }
        return rewritePrefixes(edit, prefixes)
    }

    /**
     * Replaces each line's marker (everything before its content) with a
     * new prefix, carrying the selection across: a position in the old
     * marker lands at the end of the new one, a position in the content
     * moves with it.
     */
    private fun rewritePrefixes(edit: Edit, prefixes: Map<NoteMarkdown.Line, String>): Edit {
        val text = StringBuilder(edit.text)
        var start = edit.start
        var end = edit.end
        for ((line, prefix) in prefixes.entries.sortedByDescending { it.key.start }) {
            val oldEnd = line.contentStart
            text.replace(line.start, oldEnd, prefix)
            val delta = prefix.length - (oldEnd - line.start)
            fun moved(at: Int): Int = when {
                at < line.start -> at
                at < oldEnd -> line.start + prefix.length
                else -> at + delta
            }
            start = moved(start)
            end = moved(end)
        }
        return Edit(text.toString(), start, end)
    }

    /**
     * Lines the selection reaches into. A selection that starts at the
     * very end of one line or ends at the very start of the next, as
     * dragging across a line break does, leaves that line out.
     */
    private fun touched(lines: List<NoteMarkdown.Line>, edit: Edit): List<NoteMarkdown.Line> =
        lines.filter { line ->
            if (edit.collapsed) {
                edit.start in line.start..line.end
            } else {
                line.start < edit.end && (line.end > edit.start || line.start == edit.start)
            }
        }

    /**
     * Carries a list on when Enter is pressed at the end of an item, and
     * ends it when Enter is pressed on an item with nothing written in it.
     *
     * [before] is the text before the key, [after] what the field
     * reports after it. Returns null when the change was anything other
     * than a single new line typed at the cursor.
     */
    fun continueList(before: Edit, after: Edit): Edit? {
        if (!before.collapsed || !after.collapsed) return null
        val at = before.start
        if (after.text.length != before.text.length + 1 || after.start != at + 1) return null
        if (after.text[at] != '\n') return null
        if (!after.text.startsWith(before.text.substring(0, at)) || !after.text.endsWith(before.text.substring(at))) {
            return null
        }
        val line = NoteMarkdown.parse(before.text).lines.firstOrNull { at in it.start..it.end } ?: return null
        if (line.kind == LineKind.TEXT || line.kind == LineKind.HEADING || at < line.contentStart) return null
        val indent = before.text.substring(line.start, line.start + line.indent)

        if (line.end == line.contentStart) {
            val text = before.text.removeRange(line.start + line.indent, line.contentStart)
            return Edit(text, line.start + line.indent)
        }
        val marker = when (line.kind) {
            LineKind.BULLET -> "$indent${line.bullet ?: '-'} "
            LineKind.NUMBERED -> "$indent${(line.number ?: 0) + 1}${line.delimiter ?: '.'} "
            else -> "$indent> "
        }
        val text = after.text.substring(0, at + 1) + marker + after.text.substring(at + 1)
        return Edit(text, at + 1 + marker.length)
    }
}
