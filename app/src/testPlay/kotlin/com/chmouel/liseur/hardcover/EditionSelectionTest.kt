package com.chmouel.liseur.hardcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionSelectionTest {
    private val shown = listOf(1L, 2L, 3L)

    @Test
    fun `it starts empty and cannot choose`() {
        val selection = EditionSelection()

        assertNull(selection.selectedIn(shown))
        assertFalse(selection.canChoose(shown))
        assertNull(selection.submit(shown).second)
    }

    @Test
    fun `a tap selects, another tap moves the selection`() {
        val selection = EditionSelection().select(2, shown).select(3, shown)

        assertEquals(3L, selection.selectedIn(shown))
        assertTrue(selection.canChoose(shown))
    }

    @Test
    fun `an id not on screen is not selected`() {
        val selection = EditionSelection().select(2, shown).select(99, shown)

        assertEquals(2L, selection.selectedIn(shown))
        assertNull(EditionSelection().select(99, shown).selectedIn(shown))
    }

    @Test
    fun `new results clear the selection at once`() {
        val selection = EditionSelection().select(2, shown).resultsChanged()

        assertNull(selection.selectedIn(shown))
        assertFalse(selection.canChoose(shown))
        assertEquals(2L, selection.select(2, shown).selectedIn(shown))
    }

    @Test
    fun `a selection is disabled once its edition leaves the shown list`() {
        val selection = EditionSelection().select(2, shown)

        assertFalse(selection.canChoose(listOf(1L, 3L)))
    }

    @Test
    fun `submitting answers once and then ignores everything`() {
        val (submitting, first) = EditionSelection().select(2, shown).submit(shown)

        assertEquals(2L, first)
        assertTrue(submitting.submitting)
        assertNull(submitting.submit(shown).second)
        assertNull(submitting.select(3, shown).selectedIn(shown))
        assertTrue(submitting.resultsChanged().submitting)
        assertFalse(submitting.canChoose(shown))
    }
}
