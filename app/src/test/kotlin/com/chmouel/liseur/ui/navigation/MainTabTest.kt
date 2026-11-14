package com.chmouel.liseur.ui.navigation

import androidx.compose.runtime.toMutableStateList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainTabTest {

    @Test
    fun selectingATabDropsWhateverWasOpenAboveIt() {
        val stack = mutableListOf(Route.Library, Route.Stats, Route.BookStats("content://books/1", "Title"))
        stack.selectTab(MainTab.SETTINGS)
        assertEquals(listOf(Route.Library, Route.Settings), stack)
        stack.selectTab(MainTab.SERIES)
        assertEquals(listOf(Route.Library), stack)
        stack.selectTab(MainTab.STATS)
        assertEquals(listOf(Route.Library, Route.Stats), stack)
    }

    @Test
    fun theBarShowsOnlyAtATabRoot() {
        assertEquals(MainTab.SERIES, listOf<Route>(Route.Library).toMutableStateList().mainTab(MainTab.SERIES))
        assertEquals(MainTab.STATS, listOf(Route.Library, Route.Stats).mainTab(MainTab.HOME))
        assertEquals(MainTab.SETTINGS, listOf(Route.Library, Route.Settings).mainTab(MainTab.HOME))
        assertNull(listOf(Route.Library, Route.Settings, Route.About).mainTab(MainTab.HOME))
        assertNull(listOf(Route.Library, Route.Stats, Route.Settings).mainTab(MainTab.HOME))
    }
}
