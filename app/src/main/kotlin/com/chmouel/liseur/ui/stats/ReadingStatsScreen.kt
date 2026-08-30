package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
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
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
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
                    BentoHero(stats, ready.headline)
                }
                item {
                    ActivityCard(stats = stats, range = ready.range)
                }
                if (stats.books.isNotEmpty()) {
                    item {
                        BooksSectionHeader(count = stats.books.size)
                    }
                    items(stats.books, key = { it.bookUrl }) { book ->
                        BookStatCard(book = book, onClick = { onOpenBook(book) })
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
private fun BentoHero(stats: ReadingStats, headline: StatsHeadline) {
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
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = headline.rangeDays?.let {
                            stringResource(R.string.reading_stats_in_last_days, it)
                        } ?: stringResource(R.string.reading_stats_in_total),
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
 * Bars while a day is still a readable unit, a grid once it is not.
 * Both draw the same days; only the span the reader asked for decides
 * which can be read. "This past week" is only true when it is one —
 * thirty bars under that heading is the screen telling the reader
 * something it can see is false.
 */
@Composable
private fun ActivityCard(stats: ReadingStats, range: StatsRange) {
    val daily = range.suitsDailyBars(LocalDate.now())
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            // Narrower at the sides than at the top and bottom: every dp
            // taken off the width here comes out of the bars, and a
            // month of them has none to spare.
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (daily && range == StatsRange.LAST_7_DAYS) {
                            R.string.reading_stats_recent
                        } else {
                            R.string.reading_stats_calendar
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // The week's own sum, next to its heading, so the chart
                // can be read without adding seven bars in one's head.
                if (daily && range == StatsRange.LAST_7_DAYS) {
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
            if (daily) {
                WeekBars(stats.recent)
            } else {
                ReadingHeatmap(stats.recent)
            }
        }
    }
}

/**
 * A short span of reading, one bar a day.
 *
 * Bars rather than a line: a week is too few days for a line to mean
 * anything, and a bar of nothing reads correctly as a day with no
 * reading in it, which a line would smooth over.
 *
 * At a week or less, each bar with reading on it carries its own
 * figure, because "how long on Tuesday" is the question the chart
 * exists to answer and a height alone answers it only relatively. Past
 * a week there is no room for figures, and the heights go back to
 * speaking for themselves.
 *
 * Today's bar is drawn in full and the rest a shade quieter — not as a
 * grade, but as a cursor: the eye needs to know which bar it is living
 * in before it can count backwards. The longest day is still not
 * singled out; the height already says which day was the longest.
 *
 * The gap between bars narrows as they multiply, because the bars are
 * weighted and the gaps are not: thirty-one fixed 8dp gaps eat almost
 * the whole width of a phone and leave hatching where the chart was.
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
    // Gradients dither into stripes on an e-ink panel; flat ink reads.
    val eInk = LocalEInk.current
    val gap = when {
        days.size <= DAYS_IN_WEEK -> 8.dp
        days.size <= DAYS_IN_WEEK * 2 -> 5.dp
        days.size <= DAYS_IN_WEEK * 3 -> 3.dp
        else -> 2.dp
    }
    // A 6dp radius on a bar 6dp wide is a lozenge, not a bar.
    val corner = if (days.size <= DAYS_IN_WEEK) 6.dp else 2.dp
    // The figures above the bars need headroom of their own, or the
    // tallest bar pushes its label out of the card.
    val chartHeight = if (everyBar) 150.dp else 130.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            val captioned = everyBar || (lastIndex - index) % DAYS_IN_WEEK == 0
            val isToday = index == lastIndex
            val amount = readingDuration(day.totalMs)
            val weekday = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val barHeight = if (day.totalMs > 0) {
                (92.dp * (day.totalMs.toFloat() / busiest)).coerceAtLeast(8.dp)
            } else {
                4.dp
            }
            val spoken = if (day.totalMs > 0) {
                stringResource(R.string.reading_stats_day_read, amount, weekday)
            } else {
                stringResource(R.string.reading_stats_no_reading_day, weekday)
            }
            val primary = MaterialTheme.colorScheme.primary
            val barBrush = when {
                day.totalMs <= 0 -> SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
                eInk || isToday -> SolidColor(primary)
                // Yesterday and before fade towards their base, so the
                // week reads as a shape with today at its leading edge.
                else -> Brush.verticalGradient(
                    listOf(primary.copy(alpha = 0.8f), primary.copy(alpha = 0.45f)),
                )
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
                if (everyBar) {
                    Text(
                        text = compactDuration(day.totalMs).orEmpty(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isToday) {
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
                    text = if (captioned) weekday else "",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isToday && everyBar) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isToday && everyBar) {
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
 */
@Composable
private fun BookStatCard(book: BookReadingStats, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
        StatsRange.LAST_7_DAYS -> R.string.reading_stats_range_7d
        StatsRange.LAST_30_DAYS -> R.string.reading_stats_range_30d
        StatsRange.LAST_90_DAYS -> R.string.reading_stats_range_90d
        StatsRange.LAST_YEAR -> R.string.reading_stats_range_365d
        StatsRange.THIS_YEAR -> R.string.reading_stats_range_this_year
        StatsRange.ALL_TIME -> R.string.reading_stats_range_all
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
