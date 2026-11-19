package com.chmouel.liseur.reader.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntRect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.reader.effectiveFor
import com.chmouel.liseur.ui.WidthClass
import kotlinx.coroutines.flow.map

private const val MIN_BOOK_LEAF_WIDTH_PX = 240

sealed interface ReaderFoldPosture {
    data object Flat : ReaderFoldPosture

    data class Book(
        val hingeBoundsPx: IntRect,
        val isHalfOpened: Boolean,
    ) : ReaderFoldPosture

    data class Tabletop(
        val hingeBoundsPx: IntRect,
        val topPaneHeightPx: Int,
        val bottomPaneHeightPx: Int,
    ) : ReaderFoldPosture
}

fun resolveReaderFoldPosture(
    windowWidthPx: Int,
    windowHeightPx: Int,
    orientationVertical: Boolean,
    isHalfOpened: Boolean,
    isSeparating: Boolean,
    boundsLeft: Int,
    boundsTop: Int,
    boundsRight: Int,
    boundsBottom: Int,
): ReaderFoldPosture {
    if ((!isHalfOpened && !isSeparating) || windowWidthPx <= 0 || windowHeightPx <= 0) {
        return ReaderFoldPosture.Flat
    }
    val bounds = IntRect(boundsLeft, boundsTop, boundsRight, boundsBottom)
    if (orientationVertical) {
        return ReaderFoldPosture.Book(
            hingeBoundsPx = bounds,
            isHalfOpened = isHalfOpened,
        )
    }
    if (isHalfOpened) {
        val topPaneHeightPx = boundsTop.coerceIn(0, windowHeightPx)
        val bottomPaneHeightPx = (windowHeightPx - boundsBottom).coerceIn(0, windowHeightPx)
        return ReaderFoldPosture.Tabletop(
            hingeBoundsPx = bounds,
            topPaneHeightPx = topPaneHeightPx,
            bottomPaneHeightPx = bottomPaneHeightPx,
        )
    }
    return ReaderFoldPosture.Flat
}

/**
 * Resolves the effective column mode for [widthClass] and [posture].
 *
 * Because [ReaderFoldPosture.Book] carries only [IntRect] hinge bounds, left
 * leaf width is derived from `hingeBoundsPx.left` and the fold is assumed to
 * sit near the horizontal midpoint when sizing the right leaf.
 */
fun ColumnMode.effectiveFor(widthClass: WidthClass, posture: ReaderFoldPosture): ColumnMode {
    if (posture is ReaderFoldPosture.Book && this == ColumnMode.AUTO) {
        if (posture.hingeBoundsPx.left >= MIN_BOOK_LEAF_WIDTH_PX) {
            return ColumnMode.TWO
        }
    }
    return effectiveFor(widthClass)
}

data class BookLeafBounds(
    val leftPx: Int,
    val widthPx: Int,
)

/**
 * Picks the left or right leaf of a vertical [ReaderFoldPosture.Book] fold so
 * floating cards stay on one side of the hinge rather than straddling the crease.
 */
fun resolveBookLeafBounds(
    windowWidthPx: Int,
    posture: ReaderFoldPosture,
    anchorX: Float? = null,
): BookLeafBounds? {
    if (posture !is ReaderFoldPosture.Book || windowWidthPx <= 0) return null
    val leftLeafRight = posture.hingeBoundsPx.left.coerceIn(0, windowWidthPx)
    val rightLeafLeft = posture.hingeBoundsPx.right.coerceIn(0, windowWidthPx)
    val leftWidth = leftLeafRight
    val rightWidth = (windowWidthPx - rightLeafLeft).coerceAtLeast(0)
    if (leftWidth <= 0 && rightWidth <= 0) return null
    val useRightLeaf = when {
        leftWidth <= 0 -> true
        rightWidth <= 0 -> false
        anchorX != null -> anchorX >= (leftLeafRight + rightLeafLeft) / 2f
        else -> rightWidth > leftWidth
    }
    return if (useRightLeaf) {
        BookLeafBounds(leftPx = rightLeafLeft, widthPx = rightWidth)
    } else {
        BookLeafBounds(leftPx = 0, widthPx = leftWidth)
    }
}

@Composable
fun rememberReaderFoldPosture(): ReaderFoldPosture {
    val activity = LocalContext.current.findActivity() ?: return ReaderFoldPosture.Flat
    val containerSize = LocalWindowInfo.current.containerSize
    val postureFlow = remember(activity, containerSize) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .map { layoutInfo ->
                val foldingFeature = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
                    ?: return@map ReaderFoldPosture.Flat
                val bounds = foldingFeature.bounds
                resolveReaderFoldPosture(
                    windowWidthPx = containerSize.width,
                    windowHeightPx = containerSize.height,
                    orientationVertical =
                        foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL,
                    isHalfOpened = foldingFeature.state == FoldingFeature.State.HALF_OPENED,
                    isSeparating = foldingFeature.isSeparating,
                    boundsLeft = bounds.left,
                    boundsTop = bounds.top,
                    boundsRight = bounds.right,
                    boundsBottom = bounds.bottom,
                )
            }
    }
    val posture by postureFlow.collectAsState(initial = ReaderFoldPosture.Flat)
    return posture
}
