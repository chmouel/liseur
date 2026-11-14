package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.domain.LibraryFilterOption
import com.chmouel.liseur.domain.LibraryLayout
import com.chmouel.liseur.domain.PlankRow
import com.chmouel.liseur.domain.PlankSegment
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.ShelfBackdrop
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
import com.chmouel.liseur.ui.library.shownShelfGrouping
import com.chmouel.liseur.ui.library.DownloadOverlay
import com.chmouel.liseur.ui.library.LibraryControls
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
 * The Shelves style: a stone bookcase where books stand 3–5 across per
 * shelf, grouped by series, author or reading status, or arranged
 * directly onto shelves when the library is ungrouped. Empty places hang
 * finished books' covers as pictures; e-paper gets flat planks instead.
 */
@Composable
internal fun ShelvesLibrary(
    state: LibraryUiState,
    listState: LazyListState,
    controls: LibraryControls,
    onSetLayout: (LibraryLayout) -> Unit,
    onSetShelfGrouping: (ShelfGrouping) -> Unit,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: (SeriesShelf) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val columns = booksPerPlank(windowWidth())
    val packAuthors = state.shownShelfGrouping == ShelfGrouping.AUTHOR
    val planks = remember(state.styleShelves, columns, packAuthors) {
        planksOf(
            shelves = state.styleShelves,
            columns = columns,
            packSmallShelves = packAuthors,
        )
    }
    val onShelf = !state.isSearchActive && !state.filters.archived
    val backdrop = state.layout.shelfBackdrop
    val art = if (LocalEInk.current) null else rememberShelfArt(backdrop, state.layout.shelfTone)
    val finished = state.framedCovers
    // The hero comes and goes with the grouping; without this the list
    // stays anchored on the first shelf and the hero lands off-screen.
    val grouping = state.shownShelfGrouping
    var shownGrouping by remember { mutableStateOf(grouping) }
    LaunchedEffect(grouping) {
        if (grouping != shownGrouping) {
            shownGrouping = grouping
            listState.scrollToItem(0)
        }
    }

    val customUri = state.layout.shelfBackdropUri?.takeIf { backdrop == ShelfBackdrop.CUSTOM }
    Box(modifier = modifier) {
        if (art != null && customUri != null) {
            AsyncImage(
                model = customUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(art.customWash),
            )
        }
        LazyColumn(
            state = listState,
            modifier = if (art?.wall != null) {
                Modifier.fillMaxSize().drawBehind { drawShelfWall(art) }
            } else {
                Modifier.fillMaxSize()
            },
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.continueReading
                // Grouped by reading state, the Reading shelf comes first and
                // already shows the book; with the bottom bar on, its cover is
                // already sitting in the bar.
                ?.takeIf { onShelf && !state.layout.bottomBar && state.shownShelfGrouping != ShelfGrouping.STATUS }
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
                val panelShape = MaterialTheme.shapes.medium
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .then(
                            if (art != null) {
                                Modifier
                                    .clip(panelShape)
                                    .background(art.panel, panelShape)
                                    .border(Dp.Hairline, art.panelEdge, panelShape)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    controls(
                        if (onShelf) {
                            {
                                ShelfGroupingMenu(
                                    selected = state.shownShelfGrouping,
                                    onSelect = onSetShelfGrouping,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            items(planks, key = { it.key }) { row ->
                PlankBoardRow(
                    row = row,
                    columns = columns,
                    state = state,
                    art = art,
                    finished = finished,
                    onSeriesSelected = onSeriesSelected,
                    onBookSelected = onBookSelected,
                    onBookLongPress = onBookLongPress,
                )
            }
        }
    }
}

/** Picks what the shelves are grouped by, sitting on the controls line. */
@Composable
private fun ShelfGroupingMenu(
    selected: ShelfGrouping,
    onSelect: (ShelfGrouping) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val title = stringResource(R.string.shelf_grouping)
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.semantics { contentDescription = title },
        ) {
            Icon(
                imageVector = Icons.Outlined.ViewAgenda,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
            Text(
                text = stringResource(selected.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShelfGrouping.entries.forEach { grouping ->
                DropdownMenuItem(
                    text = { Text(stringResource(grouping.labelRes())) },
                    onClick = {
                        onSelect(grouping)
                        open = false
                    },
                    trailingIcon = {
                        if (grouping == selected) Icon(Icons.Outlined.Check, contentDescription = null)
                    },
                )
            }
        }
    }
}

private fun ShelfGrouping.labelRes(): Int = when (this) {
    ShelfGrouping.SERIES -> R.string.sort_series
    ShelfGrouping.AUTHOR -> R.string.shelf_group_author
    ShelfGrouping.STATUS -> R.string.shelf_group_status
}

@Composable
private fun PlankBoardRow(
    row: PlankRow,
    columns: Int,
    state: LibraryUiState,
    art: ShelfArt?,
    finished: List<Book>,
    onSeriesSelected: (SeriesShelf) -> Unit,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
) {
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
    val decor = remember(row.key, slotBooks, finished, columns, art) {
        art?.let {
            plankDecor(
                plankKey = row.key,
                onPlank = slotBooks.mapTo(HashSet()) { it.first.url },
                finished = finished,
                empty = columns - slotBooks.size,
            )
        }
    }
    val gutter = if (art != null) StoneShelfGutter else 14.dp
    val standOn = if (art != null) StoneLedgeHeight - StoneLedgeFoot else 5.dp

    Column {
        if (hasLabels) {
            PlankHeaders(
                segments = row.segments,
                columns = columns,
                gutter = gutter,
                seriesList = state.series,
                onSeriesSelected = onSeriesSelected,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            if (art != null) {
                StoneLedge(art, Modifier.align(Alignment.BottomCenter), hanging = decor?.hanging)
                decor?.pot?.let { end ->
                    ShelfPot(
                        art = art,
                        modifier = Modifier
                            .align(if (end == ShelfEnd.START) Alignment.BottomStart else Alignment.BottomEnd)
                            .padding(start = 5.dp, end = 5.dp, bottom = standOn),
                    )
                }
            } else {
                FlatPlank(Modifier.align(Alignment.BottomCenter))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = gutter,
                        end = gutter,
                        top = 4.dp,
                        // Stand every book on the top surface of the plank
                        // or ledge rather than floating above it.
                        bottom = standOn,
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
                        } else if (art != null) {
                            when (val item = decor?.slots?.getOrNull(slot - slotBooks.size)) {
                                is SlotDecor.Frame -> ShelfFrame(
                                    book = item.book,
                                    oval = item.oval,
                                    art = art,
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .padding(bottom = 14.dp),
                                )
                                is SlotDecor.Plant -> ShelfPlant(item.kind, art, Modifier.fillMaxWidth(0.95f))
                                null -> Unit
                            }
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
    gutter: Dp,
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
            modifier = Modifier.padding(horizontal = 8.dp + gutter),
        )
        return
    }

    val occupiedSlots = segments.sumOf { it.books.size }
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp + gutter, vertical = 2.dp),
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
    val coverShape = RoundedCornerShape(2.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
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
        val ribbon = book.finished && download == null
        Column {
            PageBlock(Modifier.padding(horizontal = 1.dp))
            Box(
                modifier = Modifier
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
                    ),
            ) {
                ShelfCoverLayers(book, download, progression, seriesIndex, ribbon, eInk, coverShape)
            }
        }
        if (ribbon) {
            FinishedRibbon(Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun BoxScope.ShelfCoverLayers(
    book: Book,
    download: DownloadProgress?,
    progression: Double?,
    seriesIndex: Boolean,
    ribbon: Boolean,
    eInk: Boolean,
    coverShape: Shape,
) {
    BookCover(
        book = book,
        shape = coverShape,
        modifier = Modifier
            .matchParentSize()
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
    if (book.archived && download == null) {
        ArchivedBadge(Modifier.align(Alignment.BottomStart).padding(5.dp))
    }
    if (download == null && seriesIndex) {
        SeriesIndexRibbon(
            index = book.seriesIndex,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = if (ribbon) 22.dp else 0.dp),
        )
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

/** The e-paper shelf: a crisp solid plank without gradients or textures. */
@Composable
private fun FlatPlank(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
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
    }
}
