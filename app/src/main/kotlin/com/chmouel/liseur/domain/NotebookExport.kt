package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation

/**
 * Renders a book's highlights, notes and bookmarks as Markdown.
 *
 * The shape is chosen so the result drops cleanly into a note-taking app:
 * one heading for the book, chapters as sub-headings, passages as block
 * quotes and the reader's own words as plain paragraphs underneath, since
 * that is the distinction worth keeping when the text is read later.
 */
fun exportNotebookMarkdown(
    title: String,
    author: String?,
    annotations: List<BookAnnotation>,
): String {
    val out = StringBuilder()
    out.append("# ").append(title.ifBlank { "Untitled" }).append('\n')
    if (!author.isNullOrBlank()) out.append('\n').append('*').append(author).append('*').append('\n')

    val ordered = annotations.sortedWith(
        compareBy({ it.totalProgression ?: Double.MAX_VALUE }, { it.createdAt }),
    )
    var chapter: String? = NO_CHAPTER_YET
    for (annotation in ordered) {
        if (annotation.chapter != chapter) {
            chapter = annotation.chapter
            out.append("\n## ").append(chapter?.takeIf { it.isNotBlank() } ?: "Elsewhere")
                .append('\n')
        }
        out.append('\n')
        when (annotation.kind) {
            AnnotationKind.BOOKMARK.name ->
                out.append("- Bookmark").append(annotation.pageSuffix()).append('\n')

            else -> {
                annotation.text?.takeIf { it.isNotBlank() }?.let { text ->
                    text.trim().lines().forEach { out.append("> ").append(it.trim()).append('\n') }
                }
                annotation.note?.takeIf { it.isNotBlank() }?.let {
                    out.append('\n').append(it.trim()).append('\n')
                }
            }
        }
    }
    return out.toString()
}

private const val NO_CHAPTER_YET = "\u0000"

private fun BookAnnotation.pageSuffix(): String =
    position?.let { " — page $it" }.orEmpty()
