package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookExportTest {

    private fun annotation(
        kind: AnnotationKind = AnnotationKind.HIGHLIGHT,
        text: String? = null,
        note: String? = null,
        chapter: String? = null,
        position: Int? = null,
        progression: Double? = null,
        createdAt: Long = 0,
    ) = BookAnnotation(
        id = "$kind-$text-$note-$position",
        bookId = "book",
        kind = kind.name,
        locatorJson = "{}",
        text = text,
        note = note,
        chapter = chapter,
        position = position,
        totalProgression = progression,
        createdAt = createdAt,
    )

    @Test
    fun `title and author head the document`() {
        val md = exportNotebookMarkdown("Moby-Dick", "Herman Melville", emptyList())
        assertTrue(md.startsWith("# Moby-Dick\n"))
        assertTrue(md.contains("*Herman Melville*"))
    }

    @Test
    fun `a missing author leaves no empty emphasis behind`() {
        val md = exportNotebookMarkdown("Untitled book", null, emptyList())
        assertFalse(md.contains("**"))
        assertEquals("# Untitled book\n", md)
    }

    @Test
    fun `passages are block quotes and notes are plain paragraphs`() {
        val md = exportNotebookMarkdown(
            title = "A book",
            author = null,
            annotations = listOf(
                annotation(
                    text = "Call me Ishmael.",
                    note = "The most famous opening in English.",
                    chapter = "Loomings",
                    progression = 0.01,
                ),
            ),
        )
        assertTrue(md.contains("## Loomings"))
        assertTrue(md.contains("> Call me Ishmael."))
        assertTrue(md.contains("\nThe most famous opening in English.\n"))
    }

    @Test
    fun `a passage spanning lines is quoted line by line`() {
        val md = exportNotebookMarkdown(
            title = "A book",
            author = null,
            annotations = listOf(annotation(text = "first line\nsecond line")),
        )
        assertTrue(md.contains("> first line\n> second line"))
    }

    @Test
    fun `bookmarks are listed with their page`() {
        val md = exportNotebookMarkdown(
            title = "A book",
            author = null,
            annotations = listOf(
                annotation(kind = AnnotationKind.BOOKMARK, position = 42, chapter = "Two"),
            ),
        )
        assertTrue(md.contains("- Bookmark — page 42"))
    }

    @Test
    fun `each chapter gets one heading, in reading order`() {
        val md = exportNotebookMarkdown(
            title = "A book",
            author = null,
            annotations = listOf(
                annotation(text = "later", chapter = "Two", progression = 0.8),
                annotation(text = "earlier", chapter = "One", progression = 0.1),
                annotation(text = "also earlier", chapter = "One", progression = 0.2),
            ),
        )
        assertEquals(1, Regex("## One").findAll(md).count())
        assertTrue(md.indexOf("## One") < md.indexOf("## Two"))
        assertTrue(md.indexOf("> earlier") < md.indexOf("> also earlier"))
    }

    @Test
    fun `annotations outside any known chapter still get a home`() {
        val md = exportNotebookMarkdown(
            title = "A book",
            author = null,
            annotations = listOf(annotation(text = "somewhere", chapter = null)),
        )
        assertTrue(md.contains("## Elsewhere"))
        assertTrue(md.contains("> somewhere"))
    }

    @Test
    fun `an untitled book still reads as a document`() {
        val md = exportNotebookMarkdown("  ", null, emptyList())
        assertTrue(md.startsWith("# Untitled"))
    }
}
