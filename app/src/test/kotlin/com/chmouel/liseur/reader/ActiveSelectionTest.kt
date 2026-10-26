package com.chmouel.liseur.reader

import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.reader.annotations.locator
import com.chmouel.liseur.reader.annotations.toDecorations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalReadiumApi::class)
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ActiveSelectionTest {
    @Test
    fun `a fresh selection near a paragraph note has no existing annotation`() {
        val original = paragraphNote()
        val decorations = listOf(original).toDecorations()

        for (text in listOf("he", "sea", "The great whale crossed the sea.")) {
            val selection = ActiveSelection(locator(text, 0.301), null)

            assertNull(selection.existing)
            assertEquals(text, selection.text)
        }
        assertEquals(listOf(original.id), decorations.map { it.id })
        assertEquals("My paragraph note", original.note)
    }

    @Test
    fun `a decoration tap retains the exact annotation to edit`() {
        val original = paragraphNote()
        val decoration = listOf(original).toDecorations().single()
        val tapped = ActiveSelection(decoration.locator, null, original)

        assertSame(original, tapped.existing)
        assertEquals(decoration.id, tapped.existing?.id)
        assertEquals("My paragraph note", tapped.existing?.note)
        assertEquals("YELLOW", tapped.existing?.tint)
    }

    @Test
    fun `refreshing a dragged selection uses the new text without an annotation ID`() {
        val original = paragraphNote()
        val tapped = ActiveSelection(requireNotNull(original.locator()), null, original)
        val refreshed = ActiveSelection(locator("sea", 0.302), null)

        assertEquals(original.id, tapped.existing?.id)
        assertNull(refreshed.existing)
        assertEquals("sea", refreshed.text)
    }

    private fun paragraphNote(): BookAnnotation {
        val place = locator("The great whale crossed the sea.", 0.30)
        return BookAnnotation(
            id = "paragraph-note",
            bookId = "content://books/test.epub",
            kind = AnnotationKind.NOTE.name,
            locatorJson = place.toJSON().toString(),
            text = place.text.highlight,
            note = "My paragraph note",
            tint = "YELLOW",
            createdAt = 0,
            updatedAt = 0,
        )
    }

    private fun locator(text: String, progression: Double): Locator = requireNotNull(
        Locator.fromJSON(
            JSONObject()
                .put("href", "chapter.xhtml")
                .put("type", "application/xhtml+xml")
                .put("locations", JSONObject().put("progression", progression))
                .put("text", JSONObject().put("highlight", text)),
        ),
    )
}
