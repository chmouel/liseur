package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.ShelfEntry
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesKey
import com.chmouel.liseur.ui.library.ArchivedBadge
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.library.DownloadOverlay
import com.chmouel.liseur.ui.library.ContinueReadingCard
import com.chmouel.liseur.ui.library.LibraryControls
import com.chmouel.liseur.ui.library.LibraryUiState
import com.chmouel.liseur.ui.library.SeriesLine
import com.chmouel.liseur.ui.library.bookStateDescription
import com.chmouel.liseur.ui.library.seriesStateDescription

/**
 * The List style: one row per book, with the cover small and the
 * details spelled out. The progress track is as long as the book is
 * long, so a doorstop and a novella read differently before either is
 * opened, and a finished book carries a trophy.
 *
 * Laid out as an adaptive grid of rows so a tablet gets two columns of
 * them rather than one line stretched across the screen.
 */
@Composable
internal fun ListLibrary(
    state: LibraryUiState,
    gridState: LazyGridState,
    controls: LibraryControls,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: (SeriesShelf) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val compact = state.layout.compactList
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 340.dp),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + bottomInset),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        state.continueReading
            ?.takeIf { !state.layout.bottomBar && !state.isSearchActive && !state.filters.archived }
            ?.let { recent ->
                item(key = "list:hero", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(
                        entry = recent,
                        onClick = { onBookSelected(recent.book) },
                        onActions = { onBookLongPress(recent.book) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { controls(null) }
        if (state.filters.groupBySeries) {
            items(state.shelfEntries, key = { it.gridKey }) { entry ->
                when (entry) {
                    is ShelfEntry.Pile -> SeriesRow(
                        shelf = entry.shelf,
                        compact = compact,
                        onClick = { onSeriesSelected(entry.shelf) },
                    )

                    is ShelfEntry.Single -> BookRow(
                        book = entry.book,
                        state = state,
                        inASeries = false,
                        compact = compact,
                        onClick = { onBookSelected(entry.book) },
                        onLongClick = { onBookLongPress(entry.book) },
                    )
                }
            }
        } else {
            items(state.books, key = { it.id }) { book ->
                BookRow(
                    book = book,
                    state = state,
                    inASeries = seriesKey(book.seriesName) in state.shownSeries,
                    compact = compact,
                    onClick = { onBookSelected(book) },
                    onLongClick = { onBookLongPress(book) },
                )
            }
        }
    }
}

private fun coverWidth(compact: Boolean): Dp = if (compact) 40.dp else 60.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    state: LibraryUiState,
    inASeries: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val download = state.downloads[book.url]
    val description = bookStateDescription(book, download)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
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
                .semantics(mergeDescendants = true) { stateDescription = description }
                .padding(vertical = if (compact) 6.dp else 10.dp),
        ) {
            Box(Modifier.width(coverWidth(compact))) {
                BookCover(
                    book = book,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .alpha(if (book.finished) 0.55f else 1f),
                )
                download?.let {
                    DownloadOverlay(fraction = it.fraction, queued = it.queued, modifier = Modifier.matchParentSize())
                }
                if (book.archived && download == null) {
                    ArchivedBadge(Modifier.align(Alignment.BottomStart).padding(2.dp))
                }
            }
            Column(
                Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    text = book.displayTitle,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.displayAuthor?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (inASeries && !compact) SeriesLine(book, Modifier.padding(top = 2.dp))
                ProgressTrack(
                    book = book,
                    progression = state.progressions[book.url],
                    length = state.trackLengths[book.url] ?: 1f,
                    modifier = Modifier.padding(top = if (compact) 4.dp else 8.dp),
                )
            }
            StatusMark(book, download, Modifier.padding(start = 8.dp))
            IconButton(onClick = onLongClick) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.book_actions))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * A bar as long as the book, filled as far as the reader got, with the
 * percentage after it, "New" for a book not yet begun, or "Finished".
 */
@Composable
private fun ProgressTrack(
    book: Book,
    progression: Double?,
    length: Float,
    modifier: Modifier = Modifier,
) {
    val fraction = when {
        book.finished -> 1f
        else -> (progression ?: 0.0).toFloat().coerceIn(0f, 1f)
    }
    val label = when {
        book.finished -> stringResource(R.string.filter_finished)
        progression == null || progression <= 0.0 -> stringResource(R.string.list_new)
        else -> "${(progression * 100).toInt()}%"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { fraction },
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth(length)
                    .height(4.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** The trophy for a finished book, the cloud for one still on the server. */
@Composable
private fun StatusMark(book: Book, download: DownloadProgress?, modifier: Modifier = Modifier) {
    when {
        download != null -> Unit
        book.finished -> Icon(
            Icons.Outlined.EmojiEvents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(20.dp),
        )
        book.downloadState != DownloadState.DOWNLOADED -> Icon(
            Icons.Outlined.CloudQueue,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(20.dp),
        )
    }
}

/** A series pile as a row: its covers stepped behind each other, its name and how far through it the reader is. */
@Composable
private fun SeriesRow(shelf: SeriesShelf, compact: Boolean, onClick: () -> Unit) {
    val description = seriesStateDescription(shelf)
    val front = (shelf.nextUp ?: shelf.volumes.first()).book
    val behind = shelf.volumes.map { it.book }.filter { it.id != front.id }.take(2)
    val width = coverWidth(compact)
    val step = if (compact) 4.dp else 6.dp
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onClickLabel = stringResource(R.string.series_open))
                .semantics(mergeDescendants = true) { stateDescription = description }
                .padding(vertical = if (compact) 6.dp else 10.dp),
        ) {
            Box(Modifier.width(width + step * behind.size)) {
                behind.asReversed().forEachIndexed { index, book ->
                    val shift = step * (behind.size - index)
                    BookCover(
                        book = book,
                        modifier = Modifier
                            .offset(x = shift)
                            .width(width)
                            .aspectRatio(2f / 3f)
                            .padding(vertical = shift / 2)
                            .alpha(0.6f),
                    )
                }
                BookCover(book = front, modifier = Modifier.width(width).aspectRatio(2f / 3f))
            }
            Column(
                Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
            ) {
                Text(
                    text = shelf.name,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        shelf.author,
                        pluralStringResource(R.plurals.series_book_count, shelf.volumes.size, shelf.volumes.size),
                        stringResource(R.string.series_read_count, shelf.finishedCount),
                    ).joinToString(stringResource(R.string.series_meta_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { shelf.finishedCount.toFloat() / shelf.volumes.size },
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                    modifier = Modifier
                        .padding(top = if (compact) 4.dp else 8.dp)
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 12.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
