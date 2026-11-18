package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.theme.DarkHomePalette
import com.chmouel.liseur.ui.theme.homePalette
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.ui.withoutBottom
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
    val palette = homePalette()
    val containerColor = if (palette != null) {
        MaterialTheme.colorScheme.background
    } else {
        MaterialTheme.colorScheme.surface
    }
    Scaffold(
        containerColor = containerColor,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
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
                    color = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow,
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
            val readyState = state as BookReadingStatsUiState.Ready
            val stats = readyState.stats
            val etaMs = resolveBookEtaMs(
                stats = stats,
                serverInsights = serverInsights,
                progressionPerHour = readyState.progressionPerHour,
            )
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 28.dp + padding.calculateBottomPadding(),
                ),
            ) {
                item {
                    BookHeroCard(stats = stats, range = range)
                }
                item {
                    BookOverviewSection(
                        stats = stats,
                        etaMs = etaMs,
                    )
                }
                if (readyState.recent.any { it.totalMs > 0 }) {
                    item {
                        ActivitySection(days = readyState.recent, range = range)
                    }
                }
                item {
                    Spacer(Modifier.height(14.dp))
                    StatRows(
                        lines = bookLines(
                            stats = stats,
                            etaMs = etaMs,
                            progressionPerHour = readyState.progressionPerHour,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Resolves estimated remaining reading time in milliseconds, preferring
 * the cross-device `liseur-sync` estimate and falling back to local pace.
 */
internal fun resolveBookEtaMs(
    stats: BookReadingStats,
    serverInsights: WorkInsights?,
    progressionPerHour: Double?,
): Long? {
    if (stats.finished) return null
    serverInsights?.etaSeconds?.let { seconds ->
        if (seconds > 0) return TimeUnit.SECONDS.toMillis(seconds.toLong())
    }
    val prog = stats.progression ?: return null
    val pace = progressionPerHour ?: return null
    if (!prog.isFinite() || !pace.isFinite() || prog >= 1.0 || pace <= 0.0) return null
    val remaining = (1.0 - prog).coerceAtLeast(0.0)
    return ((remaining / pace) * 3_600_000.0).roundToLong().takeIf { it > 0L }
}

@Composable
private fun BookOverviewSection(
    stats: BookReadingStats,
    etaMs: Long?,
) {
    val zone = ZoneId.systemDefault()
    val daysWithBook = bookReadingDays(stats, zone, java.time.LocalDate.now(zone))
    val avgSitting = if (stats.sessions > 0 && stats.totalMs > 0) {
        val sitMs = stats.totalMs / stats.sessions
        compactDuration(sitMs) ?: readingDuration(sitMs)
    } else {
        stringResource(R.string.reading_stats_pace_unknown)
    }
    val timeLeftValue = when {
        stats.finished -> stringResource(R.string.state_finished)
        etaMs != null -> compactDuration(etaMs) ?: readingDuration(etaMs)
        else -> stringResource(R.string.reading_stats_pace_unknown)
    }

    val tiles = listOf(
        OverviewTileData(
            Icons.Outlined.BarChart,
            stringResource(R.string.reading_stats_tile_sessions),
            stats.sessions.toString(),
        ),
        OverviewTileData(
            Icons.Outlined.Schedule,
            stringResource(R.string.reading_stats_avg_sitting),
            avgSitting,
        ),
        OverviewTileData(
            Icons.Outlined.CalendarMonth,
            stringResource(R.string.reading_stats_reading_for_label),
            daysWithBook?.let {
                pluralStringResource(R.plurals.reading_stats_reading_for_days, it, it)
            } ?: stringResource(R.string.reading_stats_pace_unknown),
        ),
        OverviewTileData(
            Icons.Outlined.HourglassBottom,
            stringResource(R.string.reading_stats_time_left_label),
            timeLeftValue,
        ),
    )

    StatsSectionHeader(stringResource(R.string.reading_stats_overview))
    OverviewGrid(tiles)
}

internal fun bookReadingDays(stats: BookReadingStats, zone: ZoneId, today: java.time.LocalDate): Int? {
    val start = stats.firstReadAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return null
    val end = if (stats.finished) Instant.ofEpochMilli(stats.lastReadAt).atZone(zone).toLocalDate() else today
    return (ChronoUnit.DAYS.between(start, end) + 1).coerceAtLeast(1L).toInt()
}

/**
 * When the book was taken up and put down, and how often in between.
 *
 * The time left is an estimate from the server or local pace and stays
 * worded as one: "about", at the pace so far. A bare figure beside
 * "Time left" would read as a deadline.
 */
@Composable
private fun bookLines(
    stats: BookReadingStats,
    etaMs: Long?,
    progressionPerHour: Double?,
): List<StatLine> {
    val zone = ZoneId.systemDefault()
    val lastDate = Instant.ofEpochMilli(stats.lastReadAt).atZone(zone).toLocalDate()
    val firstDate = stats.firstReadAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val dateFormat = lastReadFormat()
    return buildList {
        if (firstDate != null) {
            add(StatLine(stringResource(R.string.reading_stats_started_label), firstDate.format(dateFormat)))
            // The tile above counts an unfinished book through today, so
            // this row asks the same helper rather than counting to the
            // last sitting, which would disagree from yesterday on.
            bookReadingDays(stats, zone, java.time.LocalDate.now(zone))?.let { daysWithBook ->
                add(
                    StatLine(
                        stringResource(R.string.reading_stats_reading_for_label),
                        pluralStringResource(R.plurals.reading_stats_reading_for_days, daysWithBook, daysWithBook),
                    ),
                )
            }
        }
        add(StatLine(stringResource(R.string.reading_stats_last_read_label), lastDate.format(dateFormat)))
        add(StatLine(stringResource(R.string.reading_stats_sessions), stats.sessions.toString()))
        progressionPerHour?.let { pace ->
            add(
                StatLine(
                    label = stringResource(R.string.reading_stats_tile_pace),
                    value = stringResource(R.string.reading_stats_pace_value, (pace * 100).roundToInt()),
                ),
            )
        }
        etaMs?.let { millis ->
            add(
                StatLine(
                    label = stringResource(R.string.reading_stats_time_left_label),
                    value = stringResource(
                        R.string.reading_stats_time_left_value,
                        readingDuration(millis),
                    ),
                    supporting = stringResource(R.string.reading_stats_time_left_note),
                ),
            )
        }
    }
}

/**
 * The book itself and the time read in the selected window inside a hero card.
 */
@Composable
private fun BookHeroCard(
    stats: BookReadingStats,
    range: StatsRange,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val dark = palette == DarkHomePalette
    val cardColor = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(22.dp)
    val backgroundModifier = if (!eInk && palette != null) {
        val start = lerp(cardColor, palette.heroWash, if (dark) 0.85f else 0.38f)
        val end = lerp(cardColor, palette.heroWash, if (dark) 0.24f else 0.14f)
        Modifier.background(Brush.linearGradient(listOf(start, end)))
    } else {
        Modifier.background(cardColor)
    }

    Surface(
        shape = shape,
        color = Color.Transparent,
        border = when {
            eInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            palette != null -> BorderStroke(1.dp, palette.cardBorder)
            else -> null
        },
        shadowElevation = if (palette != null && !dark) 3.dp else 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(backgroundModifier)
                .padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
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
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
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
                                tint = accent,
                            )
                            Text(
                                text = stringResource(R.string.state_finished),
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                            )
                        }
                    } else if (stats.progression != null) {
                        LinearProgressIndicator(
                            progress = { stats.progression.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = accent,
                            trackColor = palette?.pill ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.reading_stats_progress,
                                (stats.progression * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            StatsRowDivider()
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(
                    when (range) {
                        StatsRange.THIS_WEEK -> R.string.reading_stats_this_week_local
                        StatsRange.THIS_MONTH -> R.string.reading_stats_this_month_local
                        StatsRange.THIS_YEAR -> R.string.reading_stats_this_year_local
                        StatsRange.ALL_TIME -> R.string.reading_stats_in_total
                    },
                ).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                ),
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = readingDuration(stats.totalMs),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
