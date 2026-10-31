package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.TapLayout
import com.chmouel.liseur.data.settings.TapZones
import com.chmouel.liseur.reader.chrome.ReaderTapZones
import com.chmouel.liseur.reader.chrome.ReaderTapZones.Action

/** What a region of the preview does, one colour and label each. */
internal enum class TapArea { BACK, FORWARD, MENU, NOTHING }

/** One rectangle of the preview, in fractions of the window. */
internal data class TapRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val area: TapArea,
)

private fun Action.area(): TapArea = when (this) {
    Action.Chrome -> TapArea.MENU
    Action.Inert -> TapArea.NOTHING
    is Action.Turn -> if (forward) TapArea.FORWARD else TapArea.BACK
}

/**
 * The page under [layout], cut into rectangles by asking
 * [ReaderTapZones.actionAt] about the centre of each cell of a
 * [columns] by [rows] grid. Reading the answer from the reader's own
 * function, rather than drawing the shape again, keeps the picture
 * honest when the shape changes. Cells that agree with their neighbour
 * merge into runs, and identical rows of runs into one band, so the
 * drawing has a handful of rectangles rather than thousands.
 *
 * Drawn for a left-to-right book, page by page.
 */
internal fun pageTapRects(
    width: Float,
    height: Float,
    density: Float,
    layout: TapLayout,
    columns: Int = 100,
    rows: Int = 200,
): List<TapRect> {
    fun rowRuns(row: Int): List<Triple<Int, Int, TapArea>> {
        val y = (row + 0.5f) / rows * height
        val runs = mutableListOf<Triple<Int, Int, TapArea>>()
        for (column in 0 until columns) {
            val x = (column + 0.5f) / columns * width
            val area = ReaderTapZones.actionAt(x, y, width, height, density, false, layout, false).area()
            val last = runs.lastOrNull()
            if (last != null && last.third == area) {
                runs[runs.lastIndex] = last.copy(second = column + 1)
            } else {
                runs += Triple(column, column + 1, area)
            }
        }
        return runs
    }

    val rects = mutableListOf<TapRect>()
    var bandStart = 0
    var bandRuns = rowRuns(0)
    fun closeBand(end: Int) {
        bandRuns.forEach { (from, to, area) ->
            rects += TapRect(
                from.toFloat() / columns,
                bandStart.toFloat() / rows,
                to.toFloat() / columns,
                end.toFloat() / rows,
                area,
            )
        }
    }
    for (row in 1 until rows) {
        val runs = rowRuns(row)
        if (runs != bandRuns) {
            closeBand(row)
            bandStart = row
            bandRuns = runs
        }
    }
    closeBand(rows)
    return rects
}

private val TapArea.label: Int
    get() = when (this) {
        TapArea.BACK -> R.string.page_taps_back
        TapArea.FORWARD -> R.string.page_taps_forward
        TapArea.MENU -> R.string.page_taps_menu
        TapArea.NOTHING -> R.string.page_taps_nothing
    }

@Composable
private fun TapArea.color(): Color = when (this) {
    TapArea.BACK -> MaterialTheme.colorScheme.tertiaryContainer
    TapArea.FORWARD -> MaterialTheme.colorScheme.primaryContainer
    TapArea.MENU -> MaterialTheme.colorScheme.surfaceContainerHighest
    TapArea.NOTHING -> MaterialTheme.colorScheme.surface
}

@Composable
private fun TapArea.onColor(): Color = when (this) {
    TapArea.BACK -> MaterialTheme.colorScheme.onTertiaryContainer
    TapArea.FORWARD -> MaterialTheme.colorScheme.onPrimaryContainer
    TapArea.MENU, TapArea.NOTHING -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val TapZones.previewDescription: Int
    get() = when (this) {
        TapZones.BOTH -> R.string.page_taps_preview_both
        TapZones.LEFT -> R.string.page_taps_preview_left
        TapZones.RIGHT -> R.string.page_taps_preview_right
        TapZones.OFF -> R.string.page_taps_preview_off
    }

/**
 * A drawing of this window's page under [layout]: which parts go back,
 * go forward, open the menu or do nothing. It follows the setting as it
 * changes, the width slider included while it is dragged, and is not
 * itself tappable: it shows the page, it is not one.
 *
 * Each region carries its name where it fits, and the legend under it
 * names the colours, so the picture does not rely on colour alone.
 */
@Composable
internal fun PageTapsPreview(layout: TapLayout, modifier: Modifier = Modifier) {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val width = window.width.coerceAtLeast(1).toFloat()
    val height = window.height.coerceAtLeast(1).toFloat()
    val rects = remember(layout, width, height, density) {
        pageTapRects(width, height, density, layout)
    }
    val areas = TapArea.entries.filter { area -> rects.any { it.area == area } }
    val colors = TapArea.entries.associateWith { it.color() }
    val onColors = TapArea.entries.associateWith { it.onColor() }
    val labels = TapArea.entries.associateWith { stringResource(it.label) }
    val outline = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val description = stringResource(layout.zones.previewDescription)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .height(280.dp)
                .aspectRatio(width / height)
                .clip(shape)
                .border(2.dp, outline, shape)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                rects.forEach { rect ->
                    drawRect(
                        color = colors.getValue(rect.area),
                        topLeft = Offset(rect.left * size.width, rect.top * size.height),
                        size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                    )
                }
                // One label per kind of area, in the largest rectangle of
                // that kind, turned on its side in a tall narrow strip, and
                // only where it fits whole.
                rects.groupBy { it.area }.forEach { (area, group) ->
                    val rect = group.maxBy { (it.right - it.left) * (it.bottom - it.top) }
                    val boxWidth = (rect.right - rect.left) * size.width
                    val boxHeight = (rect.bottom - rect.top) * size.height
                    val text = measurer.measure(
                        labels.getValue(area),
                        style = labelStyle.copy(color = onColors.getValue(area)),
                        softWrap = false,
                        maxLines = 1,
                    )
                    val centre = Offset(
                        rect.left * size.width + boxWidth / 2f,
                        rect.top * size.height + boxHeight / 2f,
                    )
                    val topLeft = centre - Offset(text.size.width / 2f, text.size.height / 2f)
                    if (text.size.width <= boxWidth && text.size.height <= boxHeight) {
                        drawText(text, topLeft = topLeft)
                    } else if (text.size.width <= boxHeight && text.size.height <= boxWidth) {
                        rotate(-90f, pivot = centre) { drawText(text, topLeft = topLeft) }
                    }
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            areas.forEach { area ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, outline, RoundedCornerShape(3.dp))
                            .padding(1.dp),
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(colors.getValue(area)) }
                    }
                    Text(
                        labels.getValue(area),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
