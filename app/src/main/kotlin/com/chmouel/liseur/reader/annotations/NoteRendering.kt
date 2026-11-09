package com.chmouel.liseur.reader.annotations

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** A note drawn with its formatting, markers gone, for reading. */
fun noteAnnotated(note: String, quiet: Color): AnnotatedString {
    val rendered = NoteMarkdown.render(note)
    return AnnotatedString.Builder(rendered.text).apply {
        rendered.styles.forEach { addStyle(it.style.span(quiet), it.start, it.end) }
    }.toAnnotatedString()
}

/**
 * The note as it is typed, characters unchanged so the cursor never
 * jumps, with the words already in bold or italic and the markers dimmed.
 */
class NoteMarkdownTransformation(private val quiet: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = AnnotatedString.Builder(text).apply {
            NoteMarkdown.editorStyles(text.text).forEach { addStyle(it.style.span(quiet), it.start, it.end) }
        }.toAnnotatedString()
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is NoteMarkdownTransformation && other.quiet == quiet

    override fun hashCode(): Int = quiet.hashCode()
}

private fun NoteMarkdown.Style.span(quiet: Color): SpanStyle = when (this) {
    NoteMarkdown.Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    NoteMarkdown.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    NoteMarkdown.Style.QUIET, NoteMarkdown.Style.MARKER -> SpanStyle(color = quiet)
}
