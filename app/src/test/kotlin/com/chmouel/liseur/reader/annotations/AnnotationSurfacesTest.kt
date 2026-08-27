package com.chmouel.liseur.reader.annotations

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A standalone book note has no passage, so it must not reach the two
 * surfaces that assume one.
 *
 * Both guards are string comparisons against a kind name, which the
 * compiler cannot check for us: nothing about adding a kind to the enum
 * makes a `!=` fail to build. So they are pinned here instead.
 *
 * Robolectric because reading a locator back goes through `Uri`, which is
 * a stub in android.jar.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class AnnotationSurfacesTest {

    @Test
    fun `a book note is never drawn over the page`() {
        val drawn = listOf(highlight(), passageNote(), bookNote(), bookmark()).toDecorations()

        assertEquals(listOf("highlight", "passage-note"), drawn.map { it.id })
    }

    @Test
    fun `a book note has no locator to look a passage up by`() {
        // What keeps it out of `annotationAt` even if the kind filter
        // there were ever dropped: there is no passage to match, and
        // reading the sentinel back does not throw on the way to finding
        // that out.
        assertNull(bookNote().locator())
        assertNull(bookNote().copy(locatorJson = "not json").locator())
        assertNotNull(highlight().locator())
    }

    private fun annotation(
        id: String,
        kind: AnnotationKind,
        locatorJson: String,
        note: String? = null,
    ) = BookAnnotation(
        id = id,
        bookId = BOOK,
        kind = kind.name,
        locatorJson = locatorJson,
        text = "a passage",
        note = note,
        tint = "YELLOW",
        createdAt = 0,
        updatedAt = 0,
    )

    private fun highlight() = annotation("highlight", AnnotationKind.HIGHLIGHT, LOCATOR)

    private fun passageNote() =
        annotation("passage-note", AnnotationKind.NOTE, LOCATOR, note = "in the margin")

    private fun bookmark() = annotation("bookmark", AnnotationKind.BOOKMARK, LOCATOR)

    private fun bookNote() =
        annotation("book-note", AnnotationKind.BOOK_NOTE, "", note = "about the whole book")

    private companion object {
        const val BOOK = "content://sd/a-book.epub"
        const val LOCATOR =
            """{"href":"/ch1.xhtml","type":"application/xhtml+xml",""" +
                """"locations":{"progression":0.25},"text":{"highlight":"a passage"}}"""
    }
}
