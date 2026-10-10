package com.chmouel.liseur.ui.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * The screens of the main activity, as entries on a back stack.
 *
 * The stack itself remembers where Back goes: the server screen returns to
 * the library or to Settings depending on which one put it there, and book
 * statistics carry the book they are about instead of keeping it in a
 * separate piece of state that a restore could lose.
 */
sealed interface Route {
    data object Library : Route
    data object Settings : Route
    data object SettingsBackup : Route
    data object ReadingAppearance : Route
    data object ReadingNavigation : Route
    data object ReadAloud : Route
    data object Translation : Route
    data object Services : Route
    data object HiddenBooks : Route
    data object ServerAccount : Route
    data object BrowseLibraries : Route
    data object Licences : Route
    data object About : Route
    data object Stats : Route
    data class BookStats(val bookUrl: String, val title: String) : Route
}

/** Where a widget or shortcut launch puts the reader. */
enum class LaunchStack { LIBRARY, STATS }

/** The tag a route is saved under; arguments follow it. */
private val Route.tag: String
    get() = when (this) {
        Route.Library -> "Library"
        Route.Settings -> "Settings"
        Route.SettingsBackup -> "SettingsBackup"
        Route.ReadingAppearance -> "ReadingAppearance"
        Route.ReadingNavigation -> "ReadingNavigation"
        Route.ReadAloud -> "ReadAloud"
        Route.Translation -> "Translation"
        Route.Services -> "Services"
        Route.HiddenBooks -> "HiddenBooks"
        Route.ServerAccount -> "ServerAccount"
        Route.BrowseLibraries -> "BrowseLibraries"
        Route.Licences -> "Licences"
        Route.About -> "About"
        Route.Stats -> "Stats"
        is Route.BookStats -> "BookStats"
    }

private val objectRoutes: Map<String, Route> = listOf(
    Route.Library, Route.Settings, Route.SettingsBackup, Route.ReadingAppearance,
    Route.ReadingNavigation, Route.ReadAloud, Route.Translation, Route.Services,
    Route.HiddenBooks, Route.ServerAccount, Route.BrowseLibraries, Route.Licences,
    Route.About, Route.Stats,
).associateBy { it.tag }

/**
 * A key unique to this route, used to tell entries apart: the tag, plus the
 * book for book statistics.
 */
val Route.contentKey: String
    get() = when (this) {
        is Route.BookStats -> "$tag:$bookUrl"
        else -> tag
    }

/** The stack as a flat list of strings a Bundle can hold. */
fun encodeRoutes(stack: List<Route>): List<String> = stack.flatMap { route ->
    when (route) {
        is Route.BookStats -> listOf(route.tag, route.bookUrl, route.title)
        else -> listOf(route.tag)
    }
}

/**
 * The stack back from [encodeRoutes].
 *
 * Anything it cannot read in full — an unknown tag, book statistics cut
 * short, a stack that does not start at the library — restores as the
 * library alone. Arguments are only ever read in the places after their
 * own tag, so a book called "Settings" stays a title.
 */
fun decodeRoutes(saved: List<String>): List<Route> {
    val routes = mutableListOf<Route>()
    var index = 0
    while (index < saved.size) {
        val tag = saved[index++]
        val route = if (tag == "BookStats") {
            if (index + 2 > saved.size) return listOf(Route.Library)
            Route.BookStats(bookUrl = saved[index], title = saved[index + 1]).also { index += 2 }
        } else {
            objectRoutes[tag] ?: return listOf(Route.Library)
        }
        routes += route
    }
    return if (routes.firstOrNull() == Route.Library) routes else listOf(Route.Library)
}

/** Opens [route] on top, unless it is already the screen on top. */
fun MutableList<Route>.push(route: Route) {
    if (lastOrNull() != route) add(route)
}

/** Closes the screen on top. The library at the bottom is never closed. */
fun MutableList<Route>.pop() {
    if (size > 1) removeAt(lastIndex)
}

/** Pages opened from Settings, shown beside it on a wide window. */
val Route.isSettingsPage: Boolean
    get() = when (this) {
        Route.ReadingAppearance, Route.ReadingNavigation, Route.ReadAloud,
        Route.Translation, Route.Services, Route.SettingsBackup,
        Route.HiddenBooks, Route.About, Route.ServerAccount,
        -> true
        else -> false
    }

/**
 * Opens a Settings page in place of whatever page is open beside Settings.
 *
 * Choosing another row on a wide window swaps the page rather than piling
 * it on top of the last one, so Back still returns to Settings in one step.
 * Choosing the row already open does nothing, keeping its place. Without
 * Settings on the stack, it is an ordinary [push].
 */
fun MutableList<Route>.openDetail(route: Route) {
    if (lastOrNull() == route) return
    val settings = lastIndexOf(Route.Settings)
    if (settings >= 0) {
        while (size > settings + 1) removeAt(lastIndex)
    }
    add(route)
}

/** Closes Settings and any page open beside it. */
fun MutableList<Route>.closeSettings() {
    val settings = lastIndexOf(Route.Settings)
    if (settings <= 0) {
        pop()
        return
    }
    while (size > settings) removeAt(lastIndex)
}

/** The stack a widget or shortcut launch starts from. */
fun launchStack(target: LaunchStack): List<Route> = when (target) {
    LaunchStack.LIBRARY -> listOf(Route.Library)
    LaunchStack.STATS -> listOf(Route.Library, Route.Stats)
}

/** Saves the back stack across recreation and process death. */
val RouteBackStackSaver: Saver<SnapshotStateList<Route>, Any> = listSaver(
    save = { stack -> encodeRoutes(stack) },
    restore = { saved -> decodeRoutes(saved).toMutableStateList() },
)
