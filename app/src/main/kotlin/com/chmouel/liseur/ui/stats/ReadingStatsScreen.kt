package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
/**
 * What the reader has actually read, added up.
 *
 * Deliberately a small screen. The temptation with reading statistics
 * is to turn reading into a score — streaks to keep, targets to miss —
 * and a book people feel guilty about is a book they stop opening. So
 * this reports and does not grade: how long, which books, and which
 * days had reading in them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    state: ReadingStatsUiState,
    onOpenBook: (BookReadingStats) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reading_stats)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state is ReadingStatsUiState.Loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                BusyIndicator()
            }
            return@Scaffold
        }
        val ready = state as ReadingStatsUiState.Ready
        val stats = ready.stats
        if (stats.isEmpty && ready.headline.totalMs <= 0) {
            EmptyStats(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                end = 24.dp,
                bottom = 32.dp,
            ),
        ) {
            item {
                Headline(stats, ready.headline)
                Spacer(Modifier.height(24.dp))
            }
            item {
                SectionTitle(stringResource(R.string.reading_stats_recent))
                WeekBars(stats.recent)
                Spacer(Modifier.height(24.dp))
            }
            item { SectionTitle(stringResource(R.string.reading_stats_by_book)) }
            items(stats.books, key = { it.bookUrl }) { book ->
                BookRow(book, onClick = { onOpenBook(book) })
            }
        }
    }
}

/**
 * The numbers worth leading with, from wherever reading happened.
 *
 * One figure, not one per device. When the sync server answered, the
 * total is its count of every device for the range it used and says so;
 * when it did not, the total is this device's lifetime and says that
 * instead. The reader is never asked to reconcile two.
 */
@Composable
private fun Headline(stats: ReadingStats, headline: StatsHeadline) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.reading_stats_total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = readingDuration(headline.totalMs),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = headline.rangeDays?.let {
                stringResource(R.string.reading_stats_in_last_days, it)
            } ?: stringResource(R.string.reading_stats_in_total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Tally(stats.booksRead, stringResource(R.string.reading_stats_books_read))
            Tally(stats.booksFinished, stringResource(R.string.reading_stats_books_finished))
            headline.streakDays?.let {
                Tally(it, stringResource(R.string.reading_stats_streak))
            }
            headline.sessions?.let {
                Tally(it, stringResource(R.string.reading_stats_sessions))
            }
        }
    }
}

@Composable
private fun Tally(value: Int, label: String) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * The past week, one bar a day.
 *
 * Bars rather than a line: seven days is too few for a line to mean
 * anything, and a bar of nothing reads correctly as a day with no
 * reading in it, which a line would smooth over.
 */
@Composable
private fun WeekBars(days: List<ReadingDay>) {
    val busiest = days.maxOfOrNull { it.totalMs }?.coerceAtLeast(1) ?: 1
    // Read as observable state, so the letters change with the language
    // rather than staying in whatever it was when the screen was built.
    val locale = LocalLocale.current.platformLocale
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val amount = readingDuration(day.totalMs)
            val weekday = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val barHeight = if (day.totalMs > 0) {
                (88.dp * (day.totalMs.toFloat() / busiest)).coerceAtLeast(6.dp)
            } else {
                2.dp
            }
            val spoken = if (day.totalMs > 0) {
                stringResource(R.string.reading_stats_day_read, amount, weekday)
            } else {
                stringResource(R.string.reading_stats_no_reading_day, weekday)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The bar and its letter are one fact, and read out
                    // separately they are two thirds of a sentence.
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // Heights must share the same scale. Weighting a
                        // lone child inside each separate column makes
                        // every bar fill its column and look identical.
                        .height(barHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (day.totalMs > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = weekday,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BookRow(book: BookReadingStats, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.widthIn(min = 12.dp))
            Text(readingDuration(book.totalMs), style = MaterialTheme.typography.bodyMedium)
        }
        val subtitle = bookSubtitle(book)
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The line under a title: who wrote it and how far in. */
@Composable
private fun bookSubtitle(book: BookReadingStats): String = buildList {
    book.author?.let(::add)
    if (book.finished) {
        add(stringResource(R.string.state_finished))
    } else {
        book.progression?.let { add(stringResource(R.string.reading_stats_progress, (it * 100).toInt())) }
    }
}.joinToString(" · ")

@Composable
private fun EmptyStats(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.reading_stats_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.reading_stats_empty_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How a last-read date is written, in the reader's own language.
 *
 * Asked of the locale rather than spelled out as a pattern: the order
 * of day and month is not the same everywhere, and a hard-coded one is
 * wrong for most of the people who would ever read it.
 */
@Composable
internal fun lastReadFormat(): DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(LocalLocale.current.platformLocale)
