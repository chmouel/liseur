package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Is the app dark right now" now has two callers beyond the theme
 * itself — the reader's chrome and the reading theme that follows it —
 * so it is worth being sure the answer does not depend on which one
 * asks.
 */
class ThemeModeTest {

    @Test
    fun `a stated mode ignores the system`() {
        for (systemDark in listOf(false, true)) {
            assertEquals(false, ThemeMode.LIGHT.isDark(systemDark))
            assertEquals(true, ThemeMode.DARK.isDark(systemDark))
        }
    }

    @Test
    fun `following the system means following it`() {
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemDark = false))
        assertEquals(true, ThemeMode.SYSTEM.isDark(systemDark = true))
    }
}
