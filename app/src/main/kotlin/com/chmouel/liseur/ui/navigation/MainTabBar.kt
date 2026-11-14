package com.chmouel.liseur.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.theme.homePalette

/** The destinations of the bottom bar. Home, Library and Series are three views of the library screen. */
enum class MainTab { HOME, LIBRARY, SERIES, STATS, SETTINGS }

/**
 * What the bar shares with the library screen, which draws three of its
 * tabs. [pickedHome] is the Home or catalogue choice the reader made, null
 * until then so the saved start tab applies. [libraryBar] is whether the
 * library screen has room for the bar right now (no search, archive or
 * onboarding). [topRequest] counts reselections of the shown tab, each one
 * a request to scroll back to the top.
 */
@Stable
class MainTabsState(pickedHome: Boolean? = null) {
    var pickedHome by mutableStateOf(pickedHome)
    var libraryBar by mutableStateOf(false)
    var topRequest by mutableIntStateOf(0)

    companion object {
        val Saver: Saver<MainTabsState, Int> = Saver(
            save = { when (it.pickedHome) { null -> 0; true -> 1; false -> 2 } },
            restore = { MainTabsState(when (it) { 1 -> true; 2 -> false; else -> null }) },
        )
    }
}

@Composable
fun rememberMainTabsState(): MainTabsState = rememberSaveable(saver = MainTabsState.Saver) { MainTabsState() }

/**
 * The tab a back stack is showing, when it is at a tab's root. [library]
 * is what the library screen itself shows, which the stack cannot tell.
 */
// Copied first: a snapshot state list does not compare equal to a plain list.
fun List<Route>.mainTab(library: MainTab): MainTab? = when (toList()) {
    listOf(Route.Library) -> library
    listOf(Route.Library, Route.Stats) -> MainTab.STATS
    listOf(Route.Library, Route.Settings) -> MainTab.SETTINGS
    else -> null
}

/** Puts the stack at [tab]'s root, in one step so the display never sees it empty. */
fun MutableList<Route>.selectTab(tab: MainTab) {
    val target = when (tab) {
        MainTab.STATS -> listOf(Route.Library, Route.Stats)
        MainTab.SETTINGS -> listOf(Route.Library, Route.Settings)
        MainTab.HOME, MainTab.LIBRARY, MainTab.SERIES -> listOf(Route.Library)
    }
    if (toList() == target) return
    while (size > 1) removeAt(lastIndex)
    addAll(target.drop(1))
}

private val BarHeight = 68.dp
private val BarMargin = 12.dp

/** How much of the bottom of the window the bar covers, navigation bar included. */
@Composable
fun mainTabBarSpace(): Dp =
    BarHeight + BarMargin * 2 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * The bottom bar: a floating pill holding the five tabs, the selected one
 * on a tinted pill of its own. Flat and outlined on e-ink.
 */
@Composable
fun MainTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = palette?.tabBar ?: MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, palette?.tabBarBorder ?: MaterialTheme.colorScheme.outline),
        shadowElevation = if (eInk) 0.dp else 8.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(BarMargin)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(BarHeight),
    ) {
        Row(
            modifier = Modifier
                .padding(6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MainTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val shape = RoundedCornerShape(22.dp)
    val tint = when {
        eInk -> MaterialTheme.colorScheme.onSurface
        selected -> palette?.onTabSelected ?: MaterialTheme.colorScheme.onSecondaryContainer
        else -> palette?.onTab ?: MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = shape,
        color = when {
            !selected -> Color.Transparent
            eInk -> Color.Transparent
            else -> palette?.tabSelected ?: MaterialTheme.colorScheme.secondaryContainer
        },
        border = if (selected && eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface) else null,
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = tab.icon(selected),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(tab.label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val MainTab.label: Int
    get() = when (this) {
        MainTab.HOME -> R.string.library_style_home
        MainTab.LIBRARY -> R.string.library_tab_library
        MainTab.SERIES -> R.string.home_tile_series
        MainTab.STATS -> R.string.tab_stats
        MainTab.SETTINGS -> R.string.settings
    }

private fun MainTab.icon(selected: Boolean): ImageVector = when (this) {
    MainTab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    MainTab.LIBRARY -> if (selected) Icons.Filled.LocalLibrary else Icons.Outlined.LocalLibrary
    MainTab.SERIES -> if (selected) Icons.Filled.Layers else Icons.Outlined.Layers
    MainTab.STATS -> if (selected) Icons.Filled.BarChart else Icons.Outlined.BarChart
    MainTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
