package com.chmouel.liseur.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp

/*
 * Indeterminate progress, drawn so that electronic paper does not have to
 * repaint it forever.
 *
 * A spinner is a full redraw of its own area several times a second, and
 * the wait it stands for is exactly when an e-ink panel can least afford
 * that: the screen ghosts, the refresh fights whatever else is loading,
 * and the reader is left watching a smear. Held at a fixed arc it still
 * reads as "not finished", which is the only thing an indeterminate
 * indicator ever says.
 *
 * The held form is drawn with the determinate indicator, because that is
 * the one that will sit still, but it is announced as indeterminate:
 * nothing here knows how far along the work is, and a screen reader
 * saying "30 percent" would be inventing it.
 */

/** How much of the track a held indicator fills. Enough to read as partial. */
private const val EINK_BUSY_FRACTION = 0.3f

/**
 * Wraps [content] so it announces "working, no idea how far", whatever
 * the indicator inside it would otherwise have said about its progress.
 */
@Composable
private fun Indeterminately(content: @Composable () -> Unit) {
    Box(
        Modifier.clearAndSetSemantics {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        content()
    }
}

/** Indeterminate circular progress; held still on electronic paper. */
@Composable
fun BusyIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    if (LocalEInk.current) {
        Indeterminately {
            CircularProgressIndicator(
                progress = { EINK_BUSY_FRACTION },
                modifier = modifier,
                color = color,
                strokeWidth = strokeWidth,
            )
        }
    } else {
        CircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}

/** Indeterminate linear progress; held still on electronic paper. */
@Composable
fun BusyBar(modifier: Modifier = Modifier.fillMaxWidth()) {
    if (LocalEInk.current) {
        Indeterminately {
            LinearProgressIndicator(progress = { EINK_BUSY_FRACTION }, modifier = modifier)
        }
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}
