package com.chmouel.liseur.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A book that has been answered for keeps its answer, whatever Settings
 * goes on to say. That is the whole difference between this and simply
 * reading the app-wide setting, so it is what is checked.
 */
class BookReadingModeTest {

    @Test
    fun `a book with no answer follows the app-wide setting`() {
        assertTrue(null.scrollsWith(global = true))
        assertFalse(null.scrollsWith(global = false))
    }

    @Test
    fun `a book that was answered for keeps its answer`() {
        val scrolled = BookReadingMode(bookUrl = "book", scroll = true)
        val paginated = BookReadingMode(bookUrl = "book", scroll = false)
        assertTrue(scrolled.scrollsWith(global = false))
        assertFalse(paginated.scrollsWith(global = true))
    }
}
