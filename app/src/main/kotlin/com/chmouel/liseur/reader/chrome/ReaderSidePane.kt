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
    val bookLeaveOpenOnNavigate: Boolean,
)

fun resolveReaderSidePane(
    windowWidthDp: Float,
    foldPosture: ReaderFoldPosture = ReaderFoldPosture.Flat,
    density: Float = 1f,
): ReaderSidePaneMetrics = when {
    foldPosture is ReaderFoldPosture.Book && density > 0f -> {
        val rightLeafDp = (windowWidthDp * density - foldPosture.hingeBoundsPx.right) / density
        ReaderSidePaneMetrics(
            enabled = true,
            paneWidthDp = rightLeafDp.coerceAtLeast(BOOK_SIDE_PANE_MIN_WIDTH_DP),
            bookLeaveOpenOnNavigate = true,
        )
    }

    windowWidthDp >= SIDE_PANE_MIN_WINDOW_WIDTH_DP -> ReaderSidePaneMetrics(
        enabled = true,
        paneWidthDp = minOf(SIDE_PANE_WIDTH_DP, windowWidthDp * SIDE_PANE_MAX_FRACTION),
        bookLeaveOpenOnNavigate = true,
    )

    else -> ReaderSidePaneMetrics(
        enabled = false,
        paneWidthDp = windowWidthDp,
        bookLeaveOpenOnNavigate = false,
    )
}

@Composable
fun ReaderSupportingSidePane(
    theme: ReaderTheme,
    paneWidthDp: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val eInk = LocalEInk.current
    BackHandler(onBack = onDismiss)
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
