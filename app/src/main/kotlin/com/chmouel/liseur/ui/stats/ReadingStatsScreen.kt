package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.ComparisonDirection
import com.chmouel.liseur.domain.ComparisonPeriod
import com.chmouel.liseur.domain.ComparisonScope
import com.chmouel.liseur.domain.ReadingComparison
import com.chmouel.liseur.domain.ReadingPeriod
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.StatsChartPeriod
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.domain.readingPeriods
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.browseCover
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.ui.withoutBottom
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlin.math.roundToInt

/**
 * What the reader has actually read, added up.
 *
 * One figure set large and a ledger of plain rows beneath it, but the
 * rule behind the layout has not changed. The temptation with reading
 * statistics is to turn reading into a score — streaks to keep, targets
 * to miss, a best day to beat — and a book people feel guilty about is a
 * book they stop opening. So every row here reports and none of them
 * grades: no figure is singled out as an achievement, no day is drawn as
 * a record, and the streak is a count of days like any other count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    state: ReadingStatsUiState,
    onOpenBook: (BookReadingStats) -> Unit,
    onBack: () -> Unit,
    onSelectRange: (StatsRange) -> Unit = {},
    onRetry: () -> Unit = {},
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
        if (state is ReadingStatsUiState.Unavailable) {
            StatsUnavailable(
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        val ready = state as ReadingStatsUiState.Ready
        val stats = ready.stats.hideBrieflyReadBooks()
        val empty = stats.isEmpty && ready.headline.totalMs <= 0
        // Capped and centred, like every other screen in the app. A row
        // stretched the width of a tablet puts its label and its figure
        // a hand apart, and thirty bars across that width are a fence.
        // The list takes the bottom inset itself so the rows scroll on
        // under the navigation bar.
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding.withoutBottom())
                .consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // No spacing between items: each section sets its own, so
            // the rows of the book list can sit flush as one card.
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 36.dp + padding.calculateBottomPadding(),
                    top = 4.dp,
                ),
            ) {
                // Above the empty state too: a span with nothing in it
                // must still offer a way to a longer one.
                item {
                    RangeChips(ready.range, onSelectRange)
                }
                if (empty) {
                    item {
                        EmptyStats(
                            Modifier.fillParentMaxHeight(EMPTY_HEIGHT_SHARE),
                            // A reader with history who picked too narrow a
                            // span has not got nothing recorded, they have
                            // got nothing here. Telling them to start reading
                            // would be wrong.
                            narrowedByRange = stats.streakDays > 0 || ready.range != StatsRange.ALL_TIME,
                        )
                    }
                } else {
                    item {
                        DashboardHero(ready.headline, ready.range, ready.provenance)
                    }
                    item {
                        StatsSectionHeader(stringResource(R.string.reading_stats_overview))
                        StatRows(overviewLines(stats, ready.headline, ready.provenance))
                    }
                    item {
                        ActivitySection(stats = stats, range = ready.range)
                    }
                    if (ready.range != StatsRange.THIS_WEEK) {
                        item {
                            StatsSectionHeader(stringResource(R.string.reading_stats_calendar))
                            StatsCard {
                                ReadingHeatmap(
                                    stats.recent,
                                    Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                                )
                            }
                        }
                    }
                    if (stats.books.isNotEmpty()) {
                        item {
                            StatsSectionHeader(
                                title = stringResource(R.string.reading_stats_by_book),
                                trailing = stats.books.size.toString(),
                            )
                        }
                        itemsIndexed(stats.books, key = { _, book -> book.key }) { index, book ->
                            BookStatRow(
                                book = book,
                                shape = groupedRowShape(index, stats.books.size),
                                divided = index > 0,
                                onClick = if (book.isLocal) ({ onOpenBook(book) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How much of the screen the empty state centres itself in. */
private const val EMPTY_HEIGHT_SHARE = 0.75f

/**
 * The span the figures cover, as chips across the top of the list.
 *
 * Out of the app bar, where the selected span was a pill pressed against
 * the edge and the others hidden behind it. Laid out in a flow so a
 * large font scale wraps them onto a second line rather than clipping
 * "This month" to "This m…".
 */
@Composable
private fun RangeChips(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(stringResource(range.chipLabel)) },
            )
        }
    }
}

/**
 * The total the screen leads with, from wherever reading happened.
 *
 * One figure, not one per device. When the sync server answered, the
 * total is its count of every device for the span the reader chose;
 * when it did not, the total is this device's own for the same span.
 * The reader is never asked to reconcile two, and never sees their
 * history shrink because they connected a server.
 *
 * The line above it says which (ADR-0021), and says it once. A
 * statement of provenance and nothing more: not a warning, not an
 * error, and nothing to dismiss. A reader offline on a train is looking
 * at their own reading, which is not a fault, and the screen saying so
 * is the difference between a number they can trust and one they
 * cannot place.
 */
@Composable
private fun DashboardHero(
    headline: StatsHeadline,
    range: StatsRange,
    provenance: StatsProvenance,
) {
    val scope = scopeLine(range, provenance)
    StatsHero(
        scope = stringResource(
            R.string.reading_stats_joined,
            stringResource(scope.range),
            stringResource(scope.devices),
        ),
        total = readingDuration(headline.totalMs),
        scopeIcon = when (provenance) {
            StatsProvenance.THIS_DEVICE -> Icons.Outlined.PhoneAndroid
            StatsProvenance.ALL_DEVICES -> Icons.Outlined.Devices
        },
        modifier = Modifier.padding(top = 20.dp, start = 4.dp, end = 4.dp),
    ) {
        // A period with nothing in it never reaches here: the screen
        // shows its empty state instead. That is deliberate. A nought in
        // this face, captioned with the largest negative percentage there
        // is, is the one thing this design could say that would read as
        // a reprimand, and reading less is not a fault. See ADR 18.
        headline.comparison?.let { comparison ->
            Spacer(Modifier.height(6.dp))
            ComparisonLine(comparison)
        }
    }
}

/**
 * The tallies under the total, one row each.
 *
 * Each keeps its own plain label. "Books finished" over a fraction
 * would leave the reader guessing what the denominator counted, and
 * the two are different facts: one is how many books had reading in
 * them, the other how many were seen out.
 *
 * Always present, whether or not a server answered. A list that changes
 * shape depending on what a network call returned reads as a fault, and
 * this device can count its own sittings, streak and pace perfectly
 * well. Pace is the one that can genuinely be unknown — it needs
 * sessions that recorded where in the book they happened, and the
 * oldest ones did not — so its row is left out entirely rather than
 * shown as nought, which would read as a verdict on the reader.
 */
@Composable
private fun overviewLines(
    stats: ReadingStats,
    headline: StatsHeadline,
    provenance: StatsProvenance,
): List<StatLine> = buildList {
    add(StatLine(stringResource(R.string.reading_stats_books_read), stats.booksRead.toString()))
    add(StatLine(stringResource(R.string.reading_stats_books_finished), stats.booksFinished.toString()))
    add(StatLine(stringResource(R.string.reading_stats_streak), headline.streakDays.toString()))
    add(StatLine(stringResource(R.string.reading_stats_sessions), headline.sessions.toString()))
    headline.progressionPerHour?.let { pace ->
        val label = if (provenance == StatsProvenance.ALL_DEVICES && headline.serverOnlyPace) {
            stringResource(R.string.reading_stats_pace_server_only)
        } else {
            stringResource(R.string.reading_stats_pace)
        }
        add(StatLine(label, stringResource(R.string.reading_stats_pace_value, (pace * 100).roundToInt())))
    }
}

/**
 * The chart, under a heading of its own.
 *
 * Each range uses the calendar unit beneath it: days in a week, weeks
 * in a month, months in a year and years over all time. The calendar
 * below keeps the daily shape of longer spans available. The span's sum
 * is not repeated here: it is the figure at the top of the screen.
 */
@Composable
private fun ActivitySection(stats: ReadingStats, range: StatsRange) {
    val periods = readingPeriods(
        days = stats.recent,
        range = range,
        weekStart = localeWeekStart(LocalLocale.current.platformLocale),
    )
    StatsSectionHeader(stringResource(range.chartHeading))
    StatsCard {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            PeriodBars(periods, range.chartPeriod)
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
 * the calendar period currently in progress. Flat colour throughout: a
 * gradient on a bar suggests a quantity it does not have.
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
    // Translucent ink dithers into a halftone on an e-ink panel; the
    // quieter bars take a solid grey of their own there instead.
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
    LaunchedEffect(periods, scrollable, scrollState.maxValue) {
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
                // A rule on the floor of its own slot, so a quiet day
                // still has a place in the row without looking like a
                // short one.
                2.dp
            }
            val spoken = if (period.totalMs > 0) {
                stringResource(R.string.reading_stats_period_read, amount, spokenPeriod)
            } else {
                stringResource(R.string.reading_stats_no_reading_period, spokenPeriod)
            }
            val primary = MaterialTheme.colorScheme.primary
            val barColor = when {
                period.totalMs <= 0 -> MaterialTheme.colorScheme.outlineVariant
                isCurrent -> primary
                // Earlier periods sit a shade back, leaving the current
                // calendar period as the visual cursor. Electronic paper
                // gets a solid mid grey, since it dithers translucency.
                eInk -> MaterialTheme.colorScheme.outlineVariant
                else -> primary.copy(alpha = 0.5f)
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
                        // A lone day this early in the week is a bar, not
                        // a slab across the card.
                        .widthIn(max = 48.dp)
                        .fillMaxWidth()
                        // Heights must share the same scale. Weighting a
                        // lone child inside each separate column makes
                        // every bar fill its column and look identical.
                        .height(barHeight)
                        .clip(RoundedCornerShape(corner))
                        .background(barColor),
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

/**
 * One book's reading, as a row in the grouped list.
 *
 * Cover, title and one line of where the reader stands, with the time on
 * the right where the eye looks for it. No progress bar: the percentage
 * says the same thing in fewer pixels, and a column of half-filled bars
 * is a column of things left undone.
 *
 * [onClick] is null for a book only another device has (ADR-0021).
 * There is no file here to open, so the row is not made to look like
 * something that would open one: no ripple, a placeholder for a cover,
 * and a line saying where it was read. Its place in the book is the
 * server's and is written like any other, so a book finished elsewhere
 * reads as finished.
 */
@Composable
private fun BookStatRow(
    book: BookReadingStats,
    shape: Shape,
    divided: Boolean,
    onClick: (() -> Unit)?,
) {
    // At large font scales a duration beside the title leaves the title
    // a word wide, so it moves under the author instead.
    val stacked = LocalDensity.current.fontScale > 1.5f
    val duration = @Composable {
        Text(
            text = readingDuration(book.totalMs),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .let { if (onClick == null) it else it.clickable(role = Role.Button, onClick = onClick) },
    ) {
        Column {
            if (divided) StatsRowDivider()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsCoverThumbnail(
                    title = book.title,
                    coverPath = book.coverPath,
                    coverUrl = book.coverUrl,
                    browseServerId = book.browseServerId,
                    modifier = Modifier
                        .width(40.dp)
                        .height(60.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                        ),
                        maxLines = 2,
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
                    if (stacked) duration()
                    bookStanding(book)?.let { standing ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = standing,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!book.isLocal) {
                        // Why this row has nothing to open. Said plainly
                        // rather than left to be inferred from what is
                        // missing, and on a line of its own so a long
                        // translation does not push the standing out.
                        Text(
                            text = stringResource(R.string.reading_stats_book_elsewhere),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!stacked) {
                    Spacer(Modifier.width(12.dp))
                    duration()
                }
            }
        }
    }
}

/** Finished or how far through, and in how many sittings, as one line. */
@Composable
private fun bookStanding(book: BookReadingStats): String? {
    val parts = buildList {
        when {
            book.finished -> add(stringResource(R.string.state_finished))
            book.progression != null ->
                add(stringResource(R.string.reading_stats_progress, (book.progression * 100).toInt()))
        }
        if (book.sessions > 0) {
            add(pluralStringResource(R.plurals.reading_stats_book_sessions, book.sessions, book.sessions))
        }
    }
    return parts.reduceOrNull { first, second -> stringResource(R.string.reading_stats_joined, first, second) }
}

/**
 * Cover thumbnail for stats lists and headers.
 */
@Composable
internal fun StatsCoverThumbnail(
    title: String,
    coverPath: String?,
    coverUrl: String?,
    browseServerId: Long? = null,
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
        val request = remember(artwork, eInk, context, browseServerId) {
            ImageRequest.Builder(context)
                .data(artwork)
                .browseCover(artwork, coverUrl, browseServerId)
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
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reading_stats_empty_title),
                style = MaterialTheme.typography.headlineSmall,
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

/**
 * The liseur-sync account could not answer. Nothing is drawn in its
 * place: this device's own figures are smaller, and shown here they
 * would read as the whole story (ADR-0060).
 */
@Composable
internal fun StatsUnavailable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reading_stats_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.catalog_retry))
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
