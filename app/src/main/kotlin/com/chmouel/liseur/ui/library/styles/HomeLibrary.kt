package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.WidthClass
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.library.ContinueReading
import com.chmouel.liseur.ui.library.LibraryUiState
import com.chmouel.liseur.ui.library.SeriesStackCard
import com.chmouel.liseur.ui.widthClass

/**
 * The top of the Home style: the book being read, large, then strips of
 * what was opened lately, the series under way and what has not been
 * opened yet. The grid that follows is the Classic one, under an "All
 * books" heading, so nothing in the library is out of reach.
 *
 * Empty strips are left out; with all of them empty there is no heading
 * either, and the screen is the Classic grid with a bigger hero.
 */
internal fun LazyGridScope.homeDashboard(
    state: LibraryUiState,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: (SeriesShelf) -> Unit,
) {
    val home = state.home
    state.continueReading?.let { current ->
        item(key = "home:hero", span = { GridItemSpan(maxLineSpan) }) {
            HomeHero(
                entry = current,
                onOpen = { onBookSelected(current.book) },
                onActions = { onBookLongPress(current.book) },
            )
        }
    }
    if (home.recentlyOpened.isNotEmpty()) {
        item(key = "home:recent", span = { GridItemSpan(maxLineSpan) }) {
            BookStrip(
                title = stringResource(R.string.home_recently_opened),
                books = home.recentlyOpened,
                state = state,
                onBookSelected = onBookSelected,
                onBookLongPress = onBookLongPress,
            )
        }
    }
    if (home.seriesUnderway.isNotEmpty()) {
        item(key = "home:series", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeader(stringResource(R.string.home_series_underway))
                val width = stripCoverWidth() + 12.dp
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(home.seriesUnderway, key = { it.key }) { shelf ->
                        SeriesStackCard(
                            shelf = shelf,
                            onClick = { onSeriesSelected(shelf) },
                            onLongClick = { onSeriesSelected(shelf) },
                            modifier = Modifier.width(width),
                        )
                    }
                }
            }
        }
    }
    if (home.newBooks.isNotEmpty()) {
        item(key = "home:new", span = { GridItemSpan(maxLineSpan) }) {
            BookStrip(
                title = stringResource(R.string.home_new_books),
                books = home.newBooks,
                state = state,
                onBookSelected = onBookSelected,
                onBookLongPress = onBookLongPress,
            )
        }
    }
    if (!home.isEmpty) {
        item(key = "home:all", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                text = stringResource(R.string.home_all_books),
                trailing = pluralStringResource(R.plurals.library_book_count, state.books.size, state.books.size),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun stripCoverWidth(): Dp = when (widthClass()) {
    WidthClass.COMPACT -> 92.dp
    WidthClass.MEDIUM -> 112.dp
    WidthClass.EXPANDED -> 128.dp
}

@Composable
private fun BookStrip(
    title: String,
    books: List<Book>,
    state: LibraryUiState,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
) {
    Column {
        SectionHeader(title)
        val width = stripCoverWidth()
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 4.dp),
        ) {
            items(books, key = { it.id }) { book ->
                CoverTile(
                    book = book,
                    download = state.downloads[book.url],
                    progression = state.progressions[book.url],
                    onClick = { onBookSelected(book) },
                    onLongClick = { onBookLongPress(book) },
                    title = true,
                    modifier = Modifier.width(width),
                )
            }
        }
    }
}

/** The book being read, as the first and largest thing on the screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeHero(
    entry: ContinueReading,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    val eInk = LocalEInk.current
    val haptics = LocalHapticFeedback.current
    val wide = widthClass() != WidthClass.COMPACT
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onActions()
                },
                onClickLabel = stringResource(R.string.read_book),
                onLongClickLabel = stringResource(R.string.book_actions),
            ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                book = entry.book,
                modifier = Modifier
                    .width(if (wide) 140.dp else 108.dp)
                    .height(if (wide) 210.dp else 162.dp)
                    .then(if (eInk) Modifier else Modifier.shadow(10.dp, RoundedCornerShape(10.dp))),
            )
            Column(
                Modifier
                    .padding(start = 20.dp)
                    .weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.continue_reading).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onActions) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.book_actions))
                    }
                }
                Text(
                    text = entry.book.displayTitle,
                    style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.book.displayAuthor?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                entry.progression?.let { progression ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progression.toFloat() },
                            strokeCap = StrokeCap.Round,
                            drawStopIndicator = {},
                            modifier = Modifier.weight(1f).height(6.dp),
                        )
                        Text(
                            text = "${(progression * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                FilledTonalButton(
                    onClick = onOpen,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.home_continue))
                }
            }
        }
    }
}
