package com.chmouel.liseur.reader.chrome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.LocalEInk

const val SIDE_PANE_MIN_WINDOW_WIDTH_DP = 720f
const val SIDE_PANE_WIDTH_DP = 380f
const val SIDE_PANE_MAX_FRACTION = 0.44f

private const val BOOK_SIDE_PANE_MIN_WIDTH_DP = 280f

data class ReaderSidePaneMetrics(
    val enabled: Boolean,
    val paneWidthDp: Float,
)

/**
 * On a book-posture fold the pane fills the right leaf, so it never crosses
 * the hinge. A leaf too narrow for the pane gets the full-screen sheet.
 */
fun resolveReaderSidePane(
    windowWidthDp: Float,
    foldPosture: ReaderFoldPosture = ReaderFoldPosture.Flat,
    density: Float = 1f,
): ReaderSidePaneMetrics {
    if (foldPosture is ReaderFoldPosture.Book && density > 0f) {
        val rightLeafDp = (windowWidthDp * density - foldPosture.hingeBoundsPx.right) / density
        return if (rightLeafDp >= BOOK_SIDE_PANE_MIN_WIDTH_DP) {
            ReaderSidePaneMetrics(enabled = true, paneWidthDp = rightLeafDp)
        } else {
            ReaderSidePaneMetrics(enabled = false, paneWidthDp = windowWidthDp)
        }
    }
    return if (windowWidthDp >= SIDE_PANE_MIN_WINDOW_WIDTH_DP) {
        ReaderSidePaneMetrics(
            enabled = true,
            paneWidthDp = minOf(SIDE_PANE_WIDTH_DP, windowWidthDp * SIDE_PANE_MAX_FRACTION),
        )
    } else {
        ReaderSidePaneMetrics(enabled = false, paneWidthDp = windowWidthDp)
    }
}

/**
 * Shows [content] in the side pane when [sidePane] allows it, full screen
 * otherwise. The content is movable, so a resize or a fold that crosses the
 * breakpoint keeps its own state (an edited query, the open tab).
 */
@Composable
fun ReaderAdaptivePane(
    sidePane: ReaderSidePaneMetrics,
    theme: ReaderTheme,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val current by rememberUpdatedState(content)
    val movable = remember { movableContentOf { current() } }
    // Registered once ahead of the content, so the content's own back
    // handlers (a nested tab, an open detail) keep precedence after a
    // resize moves it into the pane.
    BackHandler(enabled = sidePane.enabled, onBack = onDismiss)
    if (sidePane.enabled) {
        ReaderSupportingSidePane(
            theme = theme,
            paneWidthDp = sidePane.paneWidthDp,
            content = movable,
        )
    } else {
        movable()
    }
}

@Composable
private fun ReaderSupportingSidePane(
    theme: ReaderTheme,
    paneWidthDp: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val eInk = LocalEInk.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            modifier = Modifier
                .width(paneWidthDp.dp)
                .fillMaxHeight(),
            color = theme.background,
            contentColor = theme.foreground,
            shadowElevation = if (eInk) 0.dp else 8.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 1.dp),
                ) {
                    content()
                }
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(theme.foreground.copy(alpha = 0.18f)),
                )
            }
        }
    }
}
