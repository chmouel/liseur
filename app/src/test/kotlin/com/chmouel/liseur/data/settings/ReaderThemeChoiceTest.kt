package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the reader stored, and what the page ends up being painted in.
 *
 * The two are no longer the same thing: "follow the app" has no colours
 * of its own, and the ids in DataStore predate it. Both facts are load
 * bearing, so both are asserted here.
 */
class ReaderThemeChoiceTest {

    @Test
    fun `a stated theme is that theme, whatever the app is doing`() {
        for (dark in listOf(false, true)) {
            assertEquals(ReaderTheme.LIGHT, ReaderThemeChoice.LIGHT.resolve(dark))
            assertEquals(ReaderTheme.SEPIA, ReaderThemeChoice.SEPIA.resolve(dark))
            assertEquals(ReaderTheme.DARK, ReaderThemeChoice.DARK.resolve(dark))
            assertEquals(ReaderTheme.BLACK, ReaderThemeChoice.BLACK.resolve(dark))
        }
    }

    @Test
    fun `following the app means following it both ways`() {
        assertEquals(ReaderTheme.LIGHT, ReaderThemeChoice.FOLLOW_APP.resolve(false))
        assertEquals(ReaderTheme.DARK, ReaderThemeChoice.FOLLOW_APP.resolve(true))
    }

    @Test
    fun `following the app never lands on Sepia`() {
        // Dark is a lighting condition and the app theme can be read as
        // asking for it. Sepia is a taste, and it cannot.
        for (dark in listOf(false, true)) {
            assertNotEquals(ReaderTheme.SEPIA, ReaderThemeChoice.FOLLOW_APP.resolve(dark))
        }
    }

    @Test
    fun `everything already stored still reads back as itself`() {
        // These four ids are what earlier versions wrote. Losing any of
        // them would silently reset a reader to Auto on upgrade.
        assertEquals(ReaderThemeChoice.LIGHT, ReaderThemeChoice.fromId("light"))
        assertEquals(ReaderThemeChoice.SEPIA, ReaderThemeChoice.fromId("sepia"))
        assertEquals(ReaderThemeChoice.DARK, ReaderThemeChoice.fromId("dark"))
        assertEquals(ReaderThemeChoice.BLACK, ReaderThemeChoice.fromId("black"))
    }

    @Test
    fun `a reader who never chose gets the app's answer`() {
        assertEquals(ReaderThemeChoice.FOLLOW_APP, ReaderThemeChoice.fromId(null))
        assertEquals(ReaderThemeChoice.FOLLOW_APP, ReaderThemeChoice.fromId("a-theme-we-removed"))
        assertEquals(ReaderThemeChoice.FOLLOW_APP, ReaderThemeChoice.Default)
    }

    @Test
    fun `every choice has an id of its own`() {
        val ids = ReaderThemeChoice.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
