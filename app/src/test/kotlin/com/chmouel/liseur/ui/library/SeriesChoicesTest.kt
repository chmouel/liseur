package com.chmouel.liseur.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which series are offered when a book is being filed by hand.
 *
 * The list is the whole point of the dialog: without it, joining a
 * series already on the shelf means retyping its name exactly, and a
 * near miss quietly starts a second shelf beside the first.
 */
class SeriesChoicesTest {

    private val library = listOf(
        "The Expanse",
        "Sherlock Holmes",
        "The Wheel of Time",
        "Éloge de la fuite",
    )

    @Test
    fun `an empty field offers the shelf as it stands`() {
        assertEquals(library, seriesChoices("", library))
    }

    @Test
    fun `typing narrows the list the way the library search does`() {
        assertEquals(listOf("The Expanse"), seriesChoices("expa", library))
    }

    @Test
    fun `a name typed without its accents still finds its shelf`() {
        assertEquals(listOf("Éloge de la fuite"), seriesChoices("eloge", library))
    }

    @Test
    fun `words are matched separately rather than as a phrase`() {
        assertEquals(listOf("The Wheel of Time"), seriesChoices("time wheel", library))
    }

    @Test
    fun `the series already chosen stays in the list`() {
        // It is what shows the reader that their tap took.
        assertTrue("The Expanse" in seriesChoices("The Expanse", library))
    }

    @Test
    fun `a genuinely new name offers nothing to join`() {
        assertTrue(seriesChoices("Discworld", library).isEmpty())
    }

    @Test
    fun `a long shelf is offered whole rather than cut short`() {
        // The list is lazy and scrolls; capping it would hide exactly
        // the series a reader with two hundred of them is looking for.
        val many = (1..200).map { "Series $it" }
        assertEquals(200, seriesChoices("", many).size)
    }

    @Test
    fun `what starts with what was typed comes first`() {
        val shelf = listOf("The Expanse", "Expanse Companion", "Tales of the Expanse")
        assertEquals(
            listOf("Expanse Companion", "The Expanse", "Tales of the Expanse"),
            seriesChoices("Expanse", shelf),
        )
    }
}
