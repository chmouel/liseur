package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.ComparisonDirection
import com.chmouel.liseur.domain.ComparisonPeriod
import com.chmouel.liseur.domain.ComparisonScope
import com.chmouel.liseur.domain.ReadingComparison
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.ReadingPeriod
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.StatsChartPeriod
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.domain.readingPeriods
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt

/**
 * What the reader has actually read, added up.
 *
 * The layout is a card grid now, but the rule behind it has not
 * changed. The temptation with reading statistics is to turn reading
 * into a score — streaks to keep, targets to miss, a best day to beat —
 * and a book people feel guilty about is a book they stop opening. So
 * every tile here reports and none of them grades: no figure is
 * singled out as an achievement, no day is drawn as a record, and the
 * streak is a count of days like any other count.
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
        // Capped and centred, like every other screen in the app. A card
        // stretched the width of a tablet puts its label and its figure
        // a hand apart, and thirty bars across that width are a fence.
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 36.dp,
                    top = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    BentoHero(stats, ready.headline, ready.range)
                }
                item {
                    ProvenanceLine(ready.provenance)
                }
                item {
                    ActivityCard(stats = stats, range = ready.range)
                }
                if (ready.range != StatsRange.THIS_WEEK) {
                    item {
                        DailyActivityCard(stats.recent)
                    }
                }
                if (stats.books.isNotEmpty()) {
                    item {
                        BooksSectionHeader(count = stats.books.size)
                    }
                    items(stats.books, key = { it.key }) { book ->
                        BookStatCard(
                            book = book,
                            onClick = if (book.isLocal) ({ onOpenBook(book) }) else null,
                        )
                    }
                }
            }
        }
    }
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
 * and pace perfectly well. Pace is the one that can genuinely be
 * unknown — it needs sessions that recorded where in the book they
 * happened, and the oldest ones did not — so it is left out entirely
 * rather than shown as nought, which would read as a verdict on the
 * reader.
 *
 * Laid out two to a row, in pairs, so a tile added or dropped rearranges
 * the grid instead of leaving a hole in it.
 */
@Composable
private fun BentoHero(stats: ReadingStats, headline: StatsHeadline, range: StatsRange) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The one figure the screen leads with.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.reading_stats_total).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = readingDuration(headline.totalMs),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // A period with nothing in it never reaches here: the
                // screen has already returned its empty state. That is
                // deliberate. A nought in this face, captioned with the
                // largest negative percentage there is, is the one thing
                // this design could say that would read as a reprimand,
                // and reading less is not a fault. See ADR 18.
                headline.comparison?.let { comparison ->
                    Spacer(Modifier.height(6.dp))
                    ComparisonLine(comparison)
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = stringResource(range.caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Each tally keeps its own plain label. "Books finished" over a
        // fraction would leave the reader guessing what the denominator
        // counted, and the two are different facts: one is how many
        // books had reading in them, the other how many were seen out.
        val tiles = buildList {
            add(
                Tally(
                    icon = Icons.Outlined.AutoStories,
                    value = stats.booksRead.toString(),
                    label = stringResource(R.string.reading_stats_books_read),
                ),
            )
            add(
                Tally(
                    icon = Icons.Outlined.Check,
                    value = stats.booksFinished.toString(),
                    label = stringResource(R.string.reading_stats_books_finished),
                ),
            )
            add(
                Tally(
                    icon = Icons.Outlined.CalendarMonth,
                    value = headline.streakDays.toString(),
                    label = stringResource(R.string.reading_stats_streak),
                ),
            )
            add(
                Tally(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    value = headline.sessions.toString(),
                    label = stringResource(R.string.reading_stats_sessions),
                ),
            )
            headline.progressionPerHour?.let { pace ->
                add(
                    Tally(
                        icon = Icons.Outlined.Speed,
                        value = stringResource(
                            R.string.reading_stats_pace_value,
                            (pace * 100).roundToInt(),
                        ),
                        label = stringResource(R.string.reading_stats_pace),
                    ),
                )
            }
        }
        tiles.chunked(TILES_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { tile ->
                    BentoTile(
                        icon = tile.icon,
                        value = tile.value,
                        label = tile.label,
                        modifier = Modifier.weight(1f),
                    )
                }
                // An odd tile last would otherwise stretch to the full
                // width and read as more important than the four above.
                repeat(TILES_PER_ROW - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** One figure and what it counts. */
private data class Tally(val icon: ImageVector, val value: String, val label: String)

/**
 * Where the figures above came from: this device, or all of them.
 *
 * A statement of provenance and nothing more (ADR-0021). Not a warning,
 * not an error, and nothing to dismiss: a reader offline on a train is
 * looking at their own reading, which is not a fault, and the screen
 * saying so is the difference between a number they can trust and one
 * they cannot place.
 */
@Composable
private fun ProvenanceLine(provenance: StatsProvenance) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = when (provenance) {
                StatsProvenance.THIS_DEVICE -> Icons.Outlined.PhoneAndroid
                StatsProvenance.ALL_DEVICES -> Icons.Outlined.Devices
            },
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                when (provenance) {
                    StatsProvenance.THIS_DEVICE -> R.string.reading_stats_from_this_device
                    StatsProvenance.ALL_DEVICES -> R.string.reading_stats_from_all_devices
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How many tallies share a row. */
private const val TILES_PER_ROW = 2

@Composable
private fun BentoTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The chart, in a card with its own heading.
 *
 * Each range uses the calendar unit beneath it: days in a week, weeks
 * in a month, months in a year and years over all time. The separate
 * heatmap below keeps the daily shape of longer spans available.
 */
@Composable
private fun ActivityCard(stats: ReadingStats, range: StatsRange) {
    val periods = readingPeriods(
        days = stats.recent,
        range = range,
        weekStart = localeWeekStart(LocalLocale.current.platformLocale),
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(range.chartHeading),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // The week's own sum, next to its heading, so the chart
                // can be read without adding seven bars in one's head.
                if (range == StatsRange.THIS_WEEK) {
                    val weekMs = stats.recent.sumOf { it.totalMs }
                    if (weekMs > 0) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.reading_stats_week_total,
                                    readingDuration(weekMs),
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PeriodBars(periods, range.chartPeriod)
        }
    }
}

/** The daily calendar retained alongside the range-sized summary bars. */
@Composable
private fun DailyActivityCard(days: List<ReadingDay>) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.reading_stats_calendar),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            ReadingHeatmap(days)
        }
    }
}

/**
 * A range of reading summarised in calendar-sized bars.
 *
 * Bars rather than a line: a week is too few days for a line to mean
 * anything, and a bar of nothing reads correctly as a day with no
 * reading in it, which a line would smooth over.
 *
 * When seven or fewer bars are present, each one carries its own figure.
 *
 * The last bar is drawn in full and the rest a shade quieter, marking
 * the calendar period currently in progress.
 */
@Composable
private fun PeriodBars(periods: List<ReadingPeriod>, unit: StatsChartPeriod) {
    val busiest = periods.maxOfOrNull { it.totalMs }?.coerceAtLeast(1) ?: 1
    // Read as observable state, so the letters change with the language
    // rather than staying in whatever it was when the screen was built.
    val locale = LocalLocale.current.platformLocale
    val lastIndex = periods.lastIndex
    val showAmounts = periods.size <= DAYS_IN_WEEK
    val scrollable = periods.size > DAYS_IN_WEEK
    // Gradients dither into stripes on an e-ink panel; flat ink reads.
    val eInk = LocalEInk.current
    val gap = when {
        periods.size <= DAYS_IN_WEEK -> 8.dp
        periods.size <= DAYS_IN_WEEK * 2 -> 5.dp
        periods.size <= DAYS_IN_WEEK * 3 -> 3.dp
        else -> 2.dp
    }
    val corner = if (periods.size <= DAYS_IN_WEEK) 6.dp else 2.dp
    // The figures above the bars need headroom of their own, or the
    // tallest bar pushes its label out of the card.
    val chartHeight = if (showAmounts) 150.dp else 130.dp
    val dateFormat = lastReadFormat()
    val scrollState = rememberScrollState()
    LaunchedEffect(periods, scrollable) {
        if (scrollable) scrollState.scrollTo(scrollState.maxValue)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        periods.forEachIndexed { index, period ->
            val isCurrent = index == lastIndex
            val amount = readingDuration(period.totalMs)
            val label = period.shortLabel(unit, locale)
            val spokenPeriod = period.spokenLabel(unit, locale, dateFormat)
            val barHeight = if (period.totalMs > 0) {
                (92.dp * (period.totalMs.toFloat() / busiest)).coerceAtLeast(8.dp)
            } else {
                4.dp
            }
            val spoken = if (period.totalMs > 0) {
                stringResource(R.string.reading_stats_period_read, amount, spokenPeriod)
            } else {
                stringResource(R.string.reading_stats_no_reading_period, spokenPeriod)
            }
            val primary = MaterialTheme.colorScheme.primary
            val barBrush = when {
                period.totalMs <= 0 -> SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
                eInk || isCurrent -> SolidColor(primary)
                // Earlier periods fade towards their base, leaving the
                // current calendar period as the visual cursor.
                else -> Brush.verticalGradient(
                    listOf(primary.copy(alpha = 0.8f), primary.copy(alpha = 0.45f)),
                )
            }
            Column(
                modifier = Modifier
                    .then(if (scrollable) Modifier.width(40.dp) else Modifier.weight(1f))
                    .fillMaxHeight()
                    // The bar and its letter are one fact, and read out
                    // separately they are two thirds of a sentence.
                    .clearAndSetSemantics { contentDescription = spoken },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (showAmounts) {
                    Text(
                        text = compactDuration(period.totalMs).orEmpty(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        // Heights must share the same scale. Weighting a
                        // lone child inside each separate column makes
                        // every bar fill its column and look identical.
                        .height(barHeight)
                        .clip(RoundedCornerShape(corner))
                        .background(barBrush),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

private val StatsRange.chartHeading: Int
    get() = when (chartPeriod) {
        StatsChartPeriod.DAY -> R.string.reading_stats_by_day
        StatsChartPeriod.WEEK -> R.string.reading_stats_by_week
        StatsChartPeriod.MONTH -> R.string.reading_stats_by_month
        StatsChartPeriod.YEAR -> R.string.reading_stats_by_year
    }

private fun ReadingPeriod.shortLabel(unit: StatsChartPeriod, locale: java.util.Locale): String =
    when (unit) {
        StatsChartPeriod.DAY -> from.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        StatsChartPeriod.WEEK -> if (from == to) {
            from.dayOfMonth.toString()
        } else {
            "${from.dayOfMonth}\u2013${to.dayOfMonth}"
        }
        StatsChartPeriod.MONTH -> from.month.getDisplayName(TextStyle.SHORT, locale)
        StatsChartPeriod.YEAR -> from.year.toString()
    }

private fun ReadingPeriod.spokenLabel(
    unit: StatsChartPeriod,
    locale: java.util.Locale,
    dateFormat: DateTimeFormatter,
): String = when (unit) {
    StatsChartPeriod.DAY -> from.format(dateFormat)
    StatsChartPeriod.WEEK -> if (from == to) {
        from.format(dateFormat)
    } else {
        "${from.format(dateFormat)} \u2013 ${to.format(dateFormat)}"
    }
    StatsChartPeriod.MONTH -> from.month.getDisplayName(TextStyle.FULL, locale) + " " + from.year
    StatsChartPeriod.YEAR -> from.year.toString()
}

/** Days in a week, and so the spacing of the bar chart's captions. */
private const val DAYS_IN_WEEK = 7

@Composable
private fun BooksSectionHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.reading_stats_by_book),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Editorial card for an individual book's reading breakdown.
 *
 * [onClick] is null for a book only another device has (ADR-0021).
 * There is no file here to open, so the card is not made to look like
 * something that would open one: no ripple, no cover, and a line saying
 * where it was read. Its place in the book is the server's and is drawn
 * like any other, so a book finished elsewhere reads as finished.
 */
@Composable
private fun BookStatCard(book: BookReadingStats, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick == null) it else it.clickable(onClick = onClick) },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Book Cover Thumbnail
            StatsCoverThumbnail(
                title = book.title,
                coverPath = book.coverPath,
                coverUrl = book.coverUrl,
                modifier = Modifier
                    .width(44.dp)
                    .height(64.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!book.isLocal) {
                    // Why this row has no cover and nothing to open. Said
                    // plainly rather than left to be inferred from what
                    // is missing; the place in the book beneath it is the
                    // server's, and is shown the same way as any other.
                    Text(
                        text = stringResource(R.string.reading_stats_book_elsewhere),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (book.finished) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.state_finished),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else if (book.progression != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { book.progression.toFloat() },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            strokeCap = StrokeCap.Round,
                        )
                        Text(
                            text = stringResource(R.string.reading_stats_progress, (book.progression * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = readingDuration(book.totalMs),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (book.sessions > 0) {
                    Text(
                        text = if (book.sessions == 1) {
                            stringResource(R.string.reading_stats_book_sessions_one)
                        } else {
                            stringResource(R.string.reading_stats_book_sessions, book.sessions)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Cover thumbnail for stats lists and headers.
 */
@Composable
internal fun StatsCoverThumbnail(
    title: String,
    coverPath: String?,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val artwork = coverPath ?: coverUrl
    val shape = RoundedCornerShape(8.dp)
    val borderModifier = modifier
        .clip(shape)
        .border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            shape,
        )

    if (artwork != null) {
        val context = LocalContext.current
        val eInk = LocalEInk.current
        val request = remember(artwork, eInk, context) {
            ImageRequest.Builder(context)
                .data(artwork)
                .crossfade(!eInk)
                .build()
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = borderModifier,
            error = { StatsCoverPlaceholder(title) },
            loading = { StatsCoverPlaceholder(title) },
        )
    } else {
        Box(modifier = borderModifier) {
            StatsCoverPlaceholder(title)
        }
    }
}

@Composable
private fun StatsCoverPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                text = title.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Dropdown menu for selecting stats time span.
 */
@Composable
private fun RangeMenu(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Icon(
                Icons.Outlined.DateRange,
                contentDescription = stringResource(R.string.reading_stats_range),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(selected.label),
                style = MaterialTheme.typography.labelMedium,
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
        StatsRange.THIS_WEEK -> R.string.reading_stats_range_7d
        StatsRange.THIS_MONTH -> R.string.reading_stats_range_this_month
        StatsRange.THIS_YEAR -> R.string.reading_stats_range_this_year
        StatsRange.ALL_TIME -> R.string.reading_stats_range_all
    }

/**
 * What the figure above it covers.
 *
 * Every span the reader can pick is calendar-named, so the caption names
 * the calendar too. It used to count days for anything that was not this
 * week, which read as "in the last 227 days" over a total captioned
 * "This year" in the menu that produced it.
 */
private val StatsRange.caption: Int
    get() = when (this) {
        StatsRange.THIS_WEEK -> R.string.reading_stats_this_week
        StatsRange.THIS_MONTH -> R.string.reading_stats_this_month
        StatsRange.THIS_YEAR -> R.string.reading_stats_this_year
        StatsRange.ALL_TIME -> R.string.reading_stats_in_total
    }

/**
 * Whether this period was more or less than the one before it.
 *
 * Deliberately plain. The arrow says which way and the sentence says the
 * same thing in words, so nothing here depends on seeing the icon or
 * telling two colours apart — and there are no two colours to tell
 * apart, because a quiet week is not an error state. Both take
 * `onSurfaceVariant`, the same weight as the caption below.
 *
 * The local fallback names this device. A comparison proved by the
 * server counts every device, matching the headline and its provenance
 * caption, so it needs no second scope qualifier.
 */
@Composable
private fun ComparisonLine(comparison: ReadingComparison) {
    val period = stringResource(
        when (comparison.period) {
            ComparisonPeriod.WEEK -> R.string.reading_stats_period_week
            ComparisonPeriod.MONTH -> R.string.reading_stats_period_month
            ComparisonPeriod.YEAR -> R.string.reading_stats_period_year
        },
    )
    val percent = comparison.percent
    val allDevices = comparison.scope == ComparisonScope.ALL_DEVICES
    val text = when {
        comparison.direction == ComparisonDirection.SAME ->
            stringResource(
                if (allDevices) R.string.reading_stats_compare_same_all
                else R.string.reading_stats_compare_same,
                period,
            )

        // No baseline to divide by. An infinity, or a number in the
        // hundreds of thousands, is not a fact about the reader's week.
        percent == null -> stringResource(
            if (allDevices) R.string.reading_stats_compare_more_than_all
            else R.string.reading_stats_compare_more_than,
            period,
        )

        comparison.direction == ComparisonDirection.MORE ->
            stringResource(
                if (allDevices) R.string.reading_stats_compare_more_all
                else R.string.reading_stats_compare_more,
                percent,
                period,
            )

        else -> stringResource(
            if (allDevices) R.string.reading_stats_compare_less_all
            else R.string.reading_stats_compare_less,
            percent,
            period,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = when (comparison.direction) {
                ComparisonDirection.MORE -> Icons.Outlined.ArrowUpward
                ComparisonDirection.LESS -> Icons.Outlined.ArrowDownward
                ComparisonDirection.SAME -> Icons.Outlined.Remove
            },
            // The sentence beside it already says which way, by how much
            // and against what.
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyStats(modifier: Modifier = Modifier, narrowedByRange: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoStories,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.reading_stats_empty_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
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
                    textAlign = TextAlign.Center,
                )
            }
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
