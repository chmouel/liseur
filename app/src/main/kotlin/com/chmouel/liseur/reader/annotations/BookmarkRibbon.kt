package com.chmouel.liseur.reader.annotations

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.chmouel.liseur.ui.LocalEInk
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme

private val RIBBON_WIDTH = 14.dp
private val RIBBON_HEIGHT = 30.dp

/**
 * The bookmark ribbon in the top corner of the page.
 *
 * It hangs into the page when the page is bookmarked and retracts out of
 * sight when it is not, which is both the Kindle gesture and, more usefully,
 * a state you can read at a glance without any chrome being on screen.
 *
 * Drawn small and hung from the very top edge: it is a marker, not a
 * decoration, and every pixel it takes is a pixel of page it costs. The
 * corner around it stays a full-sized tap target so it can still be hit
 * without looking.
 */
@Composable
fun BookmarkRibbon(
    bookmarked: Boolean,
    theme: ReaderTheme,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The spring is a flourish on a screen that can draw it. On
    // electronic paper it is a dozen full refreshes of the corner of the
    // page, so the ribbon simply appears.
    val extended by animateFloatAsState(
        targetValue = if (bookmarked) 1f else 0f,
        animationSpec = if (LocalEInk.current) {
            snap()
        } else {
            spring(dampingRatio = 0.55f, stiffness = 420f)
        },
        label = "bookmarkRibbon",
    )
    val label = stringResource(
        if (bookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark,
    )

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .size(width = 44.dp, height = 44.dp)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = label },
    ) {
        Canvas(
            Modifier
                .width(RIBBON_WIDTH)
                .height(RIBBON_HEIGHT)
                .graphicsLayer { transformOrigin = TransformOrigin(0.5f, 0f) }
                .scale(scaleX = 1f, scaleY = extended),
        ) {
            if (extended <= 0.01f) return@Canvas
            drawRibbon(theme.foreground.copy(alpha = 0.85f))
        }
    }
}

private fun DrawScope.drawRibbon(color: Color) {
    val notch = size.height * 0.28f
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(size.width / 2f, size.height - notch)
        lineTo(0f, size.height)
        close()
    }
    drawPath(path, color)
    // A hairline down the middle keeps the ribbon from reading as a flat
    // block on the darker themes.
    drawLine(
        color = color.copy(alpha = 0.25f),
        start = Offset(size.width / 2f, 0f),
        end = Offset(size.width / 2f, size.height - notch),
        strokeWidth = 1f,
    )
}
