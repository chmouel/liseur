package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.domain.LibraryFilterOption
import com.chmouel.liseur.domain.LibraryLayout
import com.chmouel.liseur.domain.PlankRow
import com.chmouel.liseur.domain.PlankSegment
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.ShelfGrouping
import com.chmouel.liseur.domain.ShelfLabel
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.planksOf
import com.chmouel.liseur.domain.seriesKey
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.library.ArchivedBadge
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.library.ContinueReadingCard
import com.chmouel.liseur.ui.library.DownloadOverlay
import com.chmouel.liseur.ui.library.FinishedBadge
import com.chmouel.liseur.ui.library.LibraryUiState
import com.chmouel.liseur.ui.library.OnServerBadge
import com.chmouel.liseur.ui.library.SeriesIndexRibbon
import com.chmouel.liseur.ui.library.bookStateDescription
import com.chmouel.liseur.ui.windowWidth

internal fun booksPerPlank(windowWidth: Dp): Int = when {
    windowWidth < 600.dp -> 3
    windowWidth < 840.dp -> 4
    else -> 5
}

/**
 * The Shelves style: a vertical bookcase of wooden/surface planks where
 * books stand 3–5 across per plank, grouped by series, author or reading
 * status, or arranged directly onto planks when the shelf is ungrouped.
 */
@Composable
internal fun ShelvesLibrary(
    state: LibraryUiState,
    listState: LazyListState,
    controls: @Composable () -> Unit,
    onSetLayout: (LibraryLayout) -> Unit,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: (SeriesShelf) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val columns = booksPerPlank(windowWidth())
    val packAuthors = state.layout.shelfGrouping == ShelfGrouping.AUTHOR
    val planks = remember(state.styleShelves, columns, packAuthors) {
        planksOf(
            shelves = state.styleShelves,
            columns = columns,
            packSmallShelves = packAuthors,
        )
    }
    val onShelf = !state.isSearchActive && !state.filters.archived

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.continueReading
            ?.takeIf { onShelf }
            ?.let { recent ->
                item(key = "shelves:hero") {
                    ContinueReadingCard(
                        entry = recent,
                        onClick = { onBookSelected(recent.book) },
                        onActions = { onBookLongPress(recent.book) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        item(key = "controls") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                controls()
                if (onShelf) {
                    ShelfGroupingBar(
                        selected = state.layout.shelfGrouping,
                        onSelect = { onSetLayout(state.layout.copy(shelfGrouping = it)) },
                    )
                }
            }
        }
        items(planks, key = { it.key }) { row ->
            PlankBoardRow(
                row = row,
                columns = columns,
                state = state,
                onSeriesSelected = onSeriesSelected,
                onBookSelected = onBookSelected,
                onBookLongPress = onBookLongPress,
            )
        }
    }
}

@Composable
private fun ShelfGroupingBar(
    selected: ShelfGrouping,
    onSelect: (ShelfGrouping) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.shelf_grouping),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        ShelfGrouping.entries.forEach { grouping ->
            val labelRes = when (grouping) {
                ShelfGrouping.SERIES -> R.string.sort_series
                ShelfGrouping.AUTHOR -> R.string.shelf_group_author
                ShelfGrouping.STATUS -> R.string.shelf_group_status
            }
            FilterChip(
                selected = grouping == selected,
                onClick = { onSelect(grouping) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun PlankBoardRow(
    row: PlankRow,
    columns: Int,
    state: LibraryUiState,
    onSeriesSelected: (SeriesShelf) -> Unit,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
) {
    val eInk = LocalEInk.current
    val hasLabels = row.segments.any { it.label != null }
    val slotBooks = remember(row.segments) {
        buildList {
            for (seg in row.segments) {
                val isSeries = seg.label is ShelfLabel.Series
                for (book in seg.books) {
                    add(book to isSeries)
                }
            }
        }
    }

    Column {
        if (hasLabels) {
            PlankHeaders(
                segments = row.segments,
                columns = columns,
                seriesList = state.series,
                onSeriesSelected = onSeriesSelected,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            BookcasePlank(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 14.dp,
                        end = 14.dp,
                        top = 4.dp,
                        // Rest the bottom of every book cover inside the
                        // perspective top surface of the plank so the books
                        // visibly stand on the board rather than floating.
                        bottom = if (eInk) 5.dp else 14.dp,
                    ),
            ) {
                for (slot in 0 until columns) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val entry = slotBooks.getOrNull(slot)
                        if (entry != null) {
                            val (book, inSeriesShelf) = entry
                            ShelfBookTile(
                                book = book,
                                download = state.downloads[book.url],
                                progression = state.progressions[book.url],
                                seriesIndex = inSeriesShelf || seriesKey(book.seriesName) in state.shownSeries,
                                onClick = { onBookSelected(book) },
                                onLongClick = { onBookLongPress(book) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlankHeaders(
    segments: List<PlankSegment>,
    columns: Int,
    seriesList: List<SeriesShelf>,
    onSeriesSelected: (SeriesShelf) -> Unit,
) {
    if (segments.size == 1) {
        val seg = segments.single()
        val label = seg.label ?: return
        val series = (label as? ShelfLabel.Series)?.let { s ->
            seriesList.firstOrNull { it.key == s.key }
        }
        SectionHeader(
            text = shelfTitle(label),
            trailing = pluralStringResource(R.plurals.library_book_count, seg.totalInShelf, seg.totalInShelf),
            onClick = series?.let { { onSeriesSelected(it) } },
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        return
    }

    val occupiedSlots = segments.sumOf { it.books.size }
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp, vertical = 2.dp),
    ) {
        for (seg in segments) {
            val label = seg.label
            val series = (label as? ShelfLabel.Series)?.let { s ->
                seriesList.firstOrNull { it.key == s.key }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(seg.books.size.toFloat())
                    .heightIn(min = 32.dp)
                    .then(
                        if (series != null) {
                            Modifier.combinedClickable(
                                onClick = { onSeriesSelected(series) },
                                onClickLabel = stringResource(R.string.series_open),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .semantics { heading() },
            ) {
                if (label != null) {
                    Text(
                        text = shelfTitle(label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (seg.totalInShelf > 1) {
                        Text(
                            text = seg.totalInShelf.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (series != null) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (occupiedSlots < columns) {
            Spacer(Modifier.weight((columns - occupiedSlots).toFloat()))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBookTile(
    book: Book,
    download: DownloadProgress?,
    progression: Double?,
    seriesIndex: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    val haptics = LocalHapticFeedback.current
    val state = bookStateDescription(book, download)
    val label = listOfNotNull(book.displayTitle, book.displayAuthor).joinToString(", ")
    val coverShape = RoundedCornerShape(
        topStart = 3.dp,
        topEnd = 8.dp,
        bottomEnd = 2.dp,
        bottomStart = 2.dp,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .then(
                if (eInk) {
                    Modifier
                } else {
                    Modifier.shadow(
                        elevation = 7.dp,
                        shape = coverShape,
                        clip = false,
                    )
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = stringResource(R.string.read_book),
                onLongClickLabel = stringResource(R.string.book_actions),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = state
            },
    ) {
        BookCover(
            book = book,
            modifier = Modifier
                .matchParentSize()
                .alpha(if (book.finished) 0.58f else 1f)
                .then(
                    if (eInk) {
                        Modifier
                    } else {
                        // A subtle left-edge spine crease gives the cover the
                        // profile of a bound book standing on the plank.
                        Modifier.drawWithContent {
                            drawContent()
                            val spineX = 6.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = 0.22f),
                                start = Offset(spineX - 1.5f, 0f),
                                end = Offset(spineX - 1.5f, size.height),
                                strokeWidth = 1.5f,
                            )
                            drawLine(
                                color = Color.Black.copy(alpha = 0.24f),
                                start = Offset(spineX, 0f),
                                end = Offset(spineX, size.height),
                                strokeWidth = 1.5f,
                            )
                        }
                    },
                ),
        )
        when {
            download != null -> DownloadOverlay(
                fraction = download.fraction,
                queued = download.queued,
                modifier = Modifier.matchParentSize(),
            )

            book.downloadState != DownloadState.DOWNLOADED ->
                OnServerBadge(Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        if (book.finished && download == null) {
            FinishedBadge(Modifier.align(Alignment.BottomEnd).padding(5.dp))
        }
        if (book.archived && download == null) {
            ArchivedBadge(Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
        if (download == null && seriesIndex) {
            SeriesIndexRibbon(index = book.seriesIndex, modifier = Modifier.align(Alignment.TopStart))
        }
        if (download == null && progression.isUnderway(book)) {
            LinearProgressIndicator(
                progress = { progression!!.toFloat() },
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun shelfTitle(label: ShelfLabel): String = when (label) {
    is ShelfLabel.Series -> label.name
    is ShelfLabel.Author -> label.name
    is ShelfLabel.Status -> stringResource(
        when (label.state) {
            LibraryFilterOption.IN_PROGRESS -> R.string.filter_reading
            LibraryFilterOption.FINISHED -> R.string.filter_finished
            else -> R.string.filter_unread
        },
    )
    ShelfLabel.Other -> stringResource(R.string.shelf_other_books)
}

/**
 * A 3D perspective shelf board: a receding trapezoid top surface where the
 * books rest, a front fascia with a lit top lip, and a cast shadow underneath.
 * On e-paper, it draws a crisp solid plank without gradients.
 */
@Composable
private fun BookcasePlank(modifier: Modifier = Modifier) {
    val eInk = LocalEInk.current
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f

    val topBackColor = if (isDark) {
        scheme.surfaceContainerHigh
    } else {
        scheme.surfaceContainerHighest.copy(alpha = 0.85f)
    }
    val topFrontColor = if (isDark) {
        scheme.surfaceContainerHighest
    } else {
        scheme.surfaceContainerHigh
    }
    val lipHighlight = if (isDark) {
        scheme.outlineVariant.copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.65f)
    }
    val fasciaTop = if (isDark) {
        scheme.surfaceBright
    } else {
        scheme.surfaceDim
    }
    val fasciaBottom = if (isDark) {
        scheme.surfaceContainerLow
    } else {
        scheme.outlineVariant
    }
    val bottomRule = scheme.outline.copy(alpha = if (isDark) 0.7f else 0.55f)
    val shadowColor = scheme.scrim.copy(alpha = if (isDark) 0.42f else 0.16f)
    val ink = scheme.onSurface

    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (eInk) 8.dp else 28.dp),
    ) {
        if (eInk) {
            val topY = 2.dp.toPx()
            drawLine(
                color = ink,
                start = Offset(0f, topY),
                end = Offset(size.width, topY),
                strokeWidth = 1.dp.toPx(),
            )
            drawRect(
                color = ink,
                topLeft = Offset(0f, topY + 2.dp.toPx()),
                size = Size(size.width, 4.dp.toPx()),
            )
            return@Canvas
        }

        val inset = 10.dp.toPx()
        val topDepth = 11.dp.toPx()
        val fasciaHeight = 9.dp.toPx()
        val frontY = topDepth
        val bottomY = frontY + fasciaHeight

        // 1. Perspective top surface (trapezoid receding toward the wall)
        val topSurface = Path().apply {
            moveTo(inset, 0f)
            lineTo(size.width - inset, 0f)
            lineTo(size.width, frontY)
            lineTo(0f, frontY)
            close()
        }
        drawPath(
            path = topSurface,
            brush = Brush.verticalGradient(
                colors = listOf(topBackColor, topFrontColor),
                startY = 0f,
                endY = frontY,
            ),
        )

        // Subtle back-wall crease line where the shelf meets the wall
        drawLine(
            color = bottomRule.copy(alpha = 0.25f),
            start = Offset(inset, 0.5f),
            end = Offset(size.width - inset, 0.5f),
            strokeWidth = 1f,
        )

        // 2. Front fascia of the shelf plank
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(fasciaTop, fasciaBottom),
                startY = frontY,
                endY = bottomY,
            ),
            topLeft = Offset(0f, frontY),
            size = Size(size.width, fasciaHeight),
        )

        // Lit top bevel along the front edge of the plank
        drawLine(
            color = lipHighlight,
            start = Offset(0f, frontY + 0.5f),
            end = Offset(size.width, frontY + 0.5f),
            strokeWidth = 1.5f,
        )

        // Crisp bottom edge of the plank fascia
        drawLine(
            color = bottomRule,
            start = Offset(0f, bottomY),
            end = Offset(size.width, bottomY),
            strokeWidth = 1.dp.toPx(),
        )

        // 3. Soft cast shadow below the plank
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(shadowColor, Color.Transparent),
                startY = bottomY,
                endY = size.height,
            ),
            topLeft = Offset(2.dp.toPx(), bottomY),
            size = Size(size.width - 4.dp.toPx(), size.height - bottomY),
        )
    }
}

