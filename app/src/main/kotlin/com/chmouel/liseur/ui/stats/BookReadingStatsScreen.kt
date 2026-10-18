package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.ui.withoutBottom
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * One book's reading: the book, the time spent in it, and the dates and
 * sittings around that time, laid out like the dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReadingStatsScreen(
    title: String,
    state: BookReadingStatsUiState,
    onBack: () -> Unit,
    serverInsights: WorkInsights? = null,
    range: StatsRange = StatsRange.ALL_TIME,
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
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
        // Only the stats list takes the bottom inset as its own content
        // padding, so it scrolls on under the navigation bar; the loading
        // and empty states stay clear of the bar.
        val ready = state is BookReadingStatsUiState.Ready
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (ready) padding.withoutBottom() else padding)
                .consumeWindowInsets(padding),
            contentAlignment = if (ready) {
                Alignment.TopCenter
            } else {
                Alignment.Center
            },
        ) {
            if (state is BookReadingStatsUiState.Loading) {
                BusyIndicator()
                return@Box
            }
            if (state is BookReadingStatsUiState.Unavailable) {
                StatsUnavailable(onRetry = onRetry)
                return@Box
            }
            if (state is BookReadingStatsUiState.Empty) {
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
                        Text(
                            text = stringResource(
                                if (range == StatsRange.ALL_TIME) {
                                    R.string.reading_stats_book_empty
                                } else {
                                    R.string.reading_stats_empty_range
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                return@Box
            }
            val stats = (state as BookReadingStatsUiState.Ready).stats
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 8.dp,
                    end = 20.dp,
                    bottom = 24.dp + padding.calculateBottomPadding(),
                ),
            ) {
                item {
                    BookHeroHeader(stats = stats)
                }
                item {
                    // No device named here, unlike the dashboard: this
                    // row comes from the merged list and which devices
                    // went into one book's figure is not kept.
                    StatsHero(
                        scope = stringResource(
                            when (range) {
                                StatsRange.THIS_WEEK -> R.string.reading_stats_this_week_local
                                StatsRange.THIS_MONTH -> R.string.reading_stats_this_month_local
                                StatsRange.THIS_YEAR -> R.string.reading_stats_this_year_local
                                StatsRange.ALL_TIME -> R.string.reading_stats_in_total
                            },
                        ),
                        total = readingDuration(stats.totalMs),
                        modifier = Modifier.padding(top = 24.dp, start = 4.dp, end = 4.dp),
                    )
                }
                item {
                    StatsSectionHeader(stringResource(R.string.reading_stats_overview))
                    StatRows(bookLines(stats, serverInsights))
                }
            }
        }
    }
}

/**
 * When the book was taken up and put down, and how often in between.
 *
 * The time left is an estimate from the server and stays worded as one:
 * "about", at the pace so far. A bare figure beside "Time left" would
 * read as a deadline.
 */
@Composable
private fun bookLines(stats: BookReadingStats, serverInsights: WorkInsights?): List<StatLine> {
    val zone = ZoneId.systemDefault()
    val lastDate = Instant.ofEpochMilli(stats.lastReadAt).atZone(zone).toLocalDate()
    val firstDate = stats.firstReadAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val dateFormat = lastReadFormat()
    return buildList {
        if (firstDate != null) {
            add(StatLine(stringResource(R.string.reading_stats_started_label), firstDate.format(dateFormat)))
            // Both endpoints count: begun and last read on the same day
            // is one day with the book, not none. A clock corrected
            // backwards can date the start after the end, which is still
            // a day with the book and never fewer.
            val daysWithBook = (ChronoUnit.DAYS.between(firstDate, lastDate) + 1)
                .coerceAtLeast(1L)
                .toInt()
            add(
                StatLine(
                    stringResource(R.string.reading_stats_reading_for_label),
                    pluralStringResource(R.plurals.reading_stats_reading_for_days, daysWithBook, daysWithBook),
                ),
            )
        }
        add(StatLine(stringResource(R.string.reading_stats_last_read_label), lastDate.format(dateFormat)))
        add(StatLine(stringResource(R.string.reading_stats_sessions), stats.sessions.toString()))
        serverInsights?.etaSeconds?.let { seconds ->
            add(
                StatLine(
                    label = stringResource(R.string.reading_stats_time_left_label),
                    value = stringResource(
                        R.string.reading_stats_time_left_value,
                        readingDuration(TimeUnit.SECONDS.toMillis(seconds.toLong())),
                    ),
                    supporting = stringResource(R.string.reading_stats_time_left_note),
                ),
            )
        }
    }
}

/**
 * The book itself: cover beside its title, as on a shelf card.
 *
 * Side by side rather than a cover centred over a centred title, which
 * spent a third of the screen before the first figure.
 */
@Composable
private fun BookHeroHeader(stats: BookReadingStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsCoverThumbnail(
            title = stats.title,
            coverPath = stats.coverPath,
            coverUrl = stats.coverUrl,
            browseServerId = stats.browseServerId,
            modifier = Modifier
                .width(72.dp)
                .height(108.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stats.title,
                style = MaterialTheme.typography.titleLarge,
            )
            stats.author?.let { author ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (stats.finished) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.state_finished),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (stats.progression != null) {
                LinearProgressIndicator(
                    progress = { stats.progression.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.reading_stats_progress, (stats.progression * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
