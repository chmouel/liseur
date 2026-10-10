package com.chmouel.liseur.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.toMutableStateList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RoutesTest {

    private val deep = listOf(
        Route.Library,
        Route.Stats,
        Route.BookStats("content://books/1", "Le Rouge et le Noir — tome 1"),
    )

    @Test
    fun aStackRoundTrips() {
        assertEquals(deep, decodeRoutes(encodeRoutes(deep)))
        val settings = listOf(Route.Library, Route.Settings, Route.About, Route.Licences)
        assertEquals(settings, decodeRoutes(encodeRoutes(settings)))
    }

    @Test
    fun bookArgumentsThatLookLikeTagsStayArguments() {
        val stack = listOf(
            Route.Library,
            Route.BookStats(bookUrl = "Settings", title = "BookStats"),
        )
        assertEquals(stack, decodeRoutes(encodeRoutes(stack)))
    }

    @Test
    fun anythingUnreadableRestoresAsTheLibrary() {
        val library = listOf(Route.Library)
        assertEquals(library, decodeRoutes(emptyList()))
        assertEquals(library, decodeRoutes(listOf("Library", "Nowhere")))
        assertEquals(library, decodeRoutes(listOf("Library", "BookStats", "content://books/1")))
        assertEquals(library, decodeRoutes(listOf("Settings")))
    }

    @Test
    fun theSaverRestoresWhatItSaved() {
        val scope = SaverScope { true }
        val saved = with(RouteBackStackSaver) { scope.save(deep.toMutableStateList()) }
        val restored = RouteBackStackSaver.restore(saved!!)
        assertEquals(deep, restored?.toList())
    }

    @Test
    fun popNeverClosesTheLibrary() {
        val stack = mutableListOf<Route>(Route.Library, Route.Settings)
        stack.pop()
        stack.pop()
        assertEquals(listOf(Route.Library), stack)
    }

    @Test
    fun pushingTheScreenOnTopDoesNothing() {
        val stack = mutableListOf<Route>(Route.Library, Route.Settings)
        stack.push(Route.Settings)
        assertEquals(listOf(Route.Library, Route.Settings), stack)
        stack.push(Route.ServerAccount)
        assertEquals(listOf(Route.Library, Route.Settings, Route.ServerAccount), stack)
    }

    @Test
    fun launchesStartFromTheLibrary() {
        assertEquals(listOf(Route.Library), launchStack(LaunchStack.LIBRARY))
        assertEquals(listOf(Route.Library, Route.Stats), launchStack(LaunchStack.STATS))
    }

    @Test
    fun contentKeysTellBooksApart() {
        val a = Route.BookStats("content://books/1", "A")
        val b = Route.BookStats("content://books/2", "A")
        assertNotEquals(a.contentKey, b.contentKey)
        assertEquals("Settings", Route.Settings.contentKey)
    }

    @Test
    fun anotherSettingsPageReplacesTheOpenOne() {
        val stack = mutableListOf(Route.Library, Route.Settings, Route.About, Route.Licences)
        stack.openDetail(Route.HiddenBooks)
        assertEquals(listOf(Route.Library, Route.Settings, Route.HiddenBooks), stack)
    }

    @Test
    fun theOpenSettingsPageIsLeftAlone() {
        val stack = mutableListOf(Route.Library, Route.Settings, Route.About, Route.Licences)
        stack.openDetail(Route.Licences)
        assertEquals(listOf(Route.Library, Route.Settings, Route.About, Route.Licences), stack)
    }

    @Test
    fun withoutSettingsAPageIsSimplyPushed() {
        val stack = mutableListOf<Route>(Route.Library)
        stack.openDetail(Route.ServerAccount)
        assertEquals(listOf(Route.Library, Route.ServerAccount), stack)
    }

    @Test
    fun closingSettingsClosesThePageBesideIt() {
        val stack = mutableListOf(Route.Library, Route.Settings, Route.About, Route.Licences)
        stack.closeSettings()
        assertEquals(listOf(Route.Library), stack)
        val alone = mutableListOf(Route.Library, Route.Settings)
        alone.closeSettings()
        assertEquals(listOf(Route.Library), alone)
    }
}
