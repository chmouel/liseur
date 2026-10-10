package com.chmouel.liseur.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R

/**
 * The back arrow of a page opened from Settings.
 *
 * On a wide window the page sits beside the Settings list, and an arrow
 * there would point at a list that is already on screen, so it is left
 * out; Back still closes the page. A page inside the page — a server being
 * edited, the services a feature manages — clears the scene scope around
 * itself and keeps its arrow, since only that arrow leads back out of it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PaneBackButton(onBack: () -> Unit) {
    val listShown = LocalListDetailSceneScope.current
        ?.scaffoldTransitionScope
        ?.scaffoldStateTransition
        ?.targetState
        ?.get(ListDetailPaneScaffoldRole.List) == PaneAdaptedValue.Expanded
    if (listShown) return
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
    }
}

/** Gives a page nested inside a Settings page its own back arrow. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NestedPage(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalListDetailSceneScope provides null,
        content = content,
    )
}
