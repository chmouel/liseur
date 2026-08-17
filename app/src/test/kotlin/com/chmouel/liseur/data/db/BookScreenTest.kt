package com.chmouel.liseur.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the two answers a book listens to.
 *
 * Getting this wrong is not visible on screen: the screen either sleeps
 * in the middle of a page or stays lit all night, and neither shows up
 * until the reader is halfway through a book or halfway through a
 * battery.
 */
class BookScreenTest {

    private fun own(keepScreenOn: Boolean) =
        BookScreen(bookUrl = "book", keepScreenOn = keepScreenOn)

    @Test
    fun `a book with no answer of its own follows the app-wide setting`() {
        assertEquals(true, null.keepsScreenOnWith(global = true))
        assertEquals(false, null.keepsScreenOnWith(global = false))
    }

    @Test
    fun `a book that was asked about keeps its own answer`() {
        assertEquals(true, own(true).keepsScreenOnWith(global = false))
        assertEquals(false, own(false).keepsScreenOnWith(global = true))
    }

    @Test
    fun `an answer that agrees with the setting today survives it changing`() {
        // The point of writing the row even when it matches: the reader
        // said this book, and Settings moving later is not them changing
        // their mind about it.
        val agreesToday = own(true)
        assertEquals(true, agreesToday.keepsScreenOnWith(global = true))
        assertEquals(true, agreesToday.keepsScreenOnWith(global = false))
    }
}
