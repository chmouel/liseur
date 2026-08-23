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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt
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
    onSelectRange: (StatsRange) -> Unit = {},
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
                actions = {
                    if (state is ReadingStatsUiState.Ready) {
                        RangeMenu(state.range, onSelectRange)
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
                // A reader with history who picked too narrow a span has
                // not got nothing recorded, they have got nothing here.
                // Telling them to start reading would be wrong.
                narrowedByRange = stats.streakDays > 0 || ready.range != StatsRange.ALL_TIME,
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
                // Bars while a day is still a readable unit, a grid once
                // it is not. Both draw the same days; only the span the
                // reader asked for decides which can be read.
                val daily = ready.range.suitsDailyBars(LocalDate.now())
                // "This past week" is only true when it is one. Thirty
                // bars under that heading is the screen telling the
                // reader something it can see is false.
                SectionTitle(
                    stringResource(
                        if (daily && ready.range == StatsRange.LAST_7_DAYS) {
                            R.string.reading_stats_recent
                        } else {
                            R.string.reading_stats_calendar
                        },
                    ),
                )
                if (daily) {
                    WeekBars(stats.recent)
                } else {
                    ReadingHeatmap(stats.recent)
                }
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
 * How far back to look.
 *
 * A menu rather than a row of chips: six spans across a phone would
 * either wrap or shrink to initials, and this is a control the reader
 * touches rarely and deliberately, not one they sweep through.
 */
@Composable
private fun RangeMenu(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Outlined.DateRange,
                contentDescription = stringResource(R.string.reading_stats_range),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StatsRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(stringResource(range.label)) },
                    onClick = {
                        open = false
                        onSelect(range)
                    },
                    trailingIcon = {
                        if (range == selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        }
    }
}

private val StatsRange.label: Int
    get() = when (this) {
        StatsRange.LAST_7_DAYS -> R.string.reading_stats_range_7d
        StatsRange.LAST_30_DAYS -> R.string.reading_stats_range_30d
        StatsRange.LAST_90_DAYS -> R.string.reading_stats_range_90d
        StatsRange.LAST_YEAR -> R.string.reading_stats_range_365d
        StatsRange.THIS_YEAR -> R.string.reading_stats_range_this_year
        StatsRange.ALL_TIME -> R.string.reading_stats_range_all
    }

/**
 * The numbers worth leading with, from wherever reading happened.
 *
 * One figure, not one per device. When the sync server answered, the
 * total is its count of every device for the span the reader chose;
 * when it did not, the total is this device's own for the same span.
 * The reader is never asked to reconcile two, and never sees their
 * history shrink because they connected a server.
 *
 * The tallies are always present, whether or not a server answered.
 * A row that changes shape depending on what a network call returned
 * reads as a fault, and this device can count its own sittings, streak
 * and pace perfectly well.
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
        // Wrapped rather than scrolled sideways. A row running off the
        // edge hides a tally behind a gesture nothing suggests, and a
        // half-drawn word at the margin reads as a fault rather than an
        // invitation.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Tally(stats.booksRead.toString(), stringResource(R.string.reading_stats_books_read))
            Tally(
                stats.booksFinished.toString(),
                stringResource(R.string.reading_stats_books_finished),
            )
            Tally(headline.streakDays.toString(), stringResource(R.string.reading_stats_streak))
            Tally(headline.sessions.toString(), stringResource(R.string.reading_stats_sessions))
            // Pace is the one tally that can genuinely be unknown: it
            // needs sessions that recorded where in the book they
            // happened, and the oldest ones did not. An invented figure
            // would be worse than a missing one.
            headline.progressionPerHour?.let { pace ->
                Tally(
                    stringResource(R.string.reading_stats_pace_value, (pace * 100).roundToInt()),
                    stringResource(R.string.reading_stats_pace),
                )
            }
        }
    }
}

@Composable
private fun Tally(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
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
 * A short span of reading, one bar a day.
 *
 * Bars rather than a line: a week is too few days for a line to mean
 * anything, and a bar of nothing reads correctly as a day with no
 * reading in it, which a line would smooth over.
 *
 * Only some of the bars are captioned once there are more than a
 * week of them. Three letters under a column a few millimetres wide
 * wrap to one letter a line, which is not a label but a puzzle; a
 * caption every seventh bar names the same weekday down the chart and
 * lets the reader count from it.
 */
@Composable
private fun WeekBars(days: List<ReadingDay>) {
    val busiest = days.maxOfOrNull { it.totalMs }?.coerceAtLeast(1) ?: 1
    // Read as observable state, so the letters change with the language
    // rather than staying in whatever it was when the screen was built.
    val locale = LocalLocale.current.platformLocale
    // Counted back from the end so the last bar — today — is always one
    // of the captioned ones.
    val lastIndex = days.lastIndex
    val everyBar = days.size <= DAYS_IN_WEEK
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            val captioned = everyBar || (lastIndex - index) % DAYS_IN_WEEK == 0
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
                    text = if (captioned) weekday else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

/** Days in a week, and so the spacing of the bar chart's captions. */
private const val DAYS_IN_WEEK = 7

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
private fun EmptyStats(modifier: Modifier = Modifier, narrowedByRange: Boolean = false) {
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
                text = stringResource(
                    if (narrowedByRange) {
                        R.string.reading_stats_empty_range
                    } else {
                        R.string.reading_stats_empty_detail
                    },
                ),
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
