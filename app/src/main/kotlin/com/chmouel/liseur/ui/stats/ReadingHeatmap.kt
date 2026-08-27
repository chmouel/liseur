package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.chmouel.liseur.domain.ReadingDay
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * A long span of reading, one square a day.
 *
 * Where the bar chart shows how much, this shows when: months of daily
 * bars are a picket fence nobody can read a date off, but the same
 * months as a grid make the shape of a reading habit visible at a
 * glance — the fortnight on holiday, the month nothing happened.
 *
 * Deliberately unlabelled beyond the weekday column and the month row.
 * Those two are what make a square findable at all: without them a
 * reader can see that a fortnight went quiet but not which fortnight.
 * The temptation with a grid like this is to score it, and the point
 * here is to show a reader their own year, not to grade it.
 */
@Composable
internal fun ReadingHeatmap(days: List<ReadingDay>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val locale = LocalLocale.current.platformLocale
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    // Pad the first column so every row is one weekday, the way a wall
    // calendar reads. Without this the grid is a spiral and the weekday
    // labels down the side are a lie.
    val leading = ((days.first().date.dayOfWeek.value - firstDayOfWeek.value) + DAYS_IN_WEEK) %
        DAYS_IN_WEEK
    val cells: List<ReadingDay?> = List(leading) { null } + days
    val weeks = cells.chunked(DAYS_IN_WEEK)
    val busiest = days.maxOfOrNull { it.totalMs }?.coerceAtLeast(1) ?: 1
    // A column is captioned when its first real day opens a month the
    // column before it did not, which puts the name where the month
    // starts rather than at a fixed interval that drifts off it.
    val months = weeks.map { week -> week.firstNotNullOfOrNull { it }?.date }
        .runningFold<java.time.LocalDate?, Pair<java.time.Month?, String>>(null to "") { previous, date ->
            when {
                date == null -> previous.first to ""
                date.month == previous.first -> previous.first to ""
                else -> date.month to date.month.getDisplayName(TextStyle.SHORT, locale)
            }
        }
        .drop(1)
        .map { it.second }
    val scroll = rememberScrollState()
    // A span of months opens at its far end. The reader came to see how
    // this week went, and a grid that starts a year ago hides that
    // behind a gesture nothing on the screen suggests.
    LaunchedEffect(weeks.size) { scroll.scrollTo(scroll.maxValue) }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            // Keeps the weekday letters level with the grid, which the
            // month row above has pushed down.
            Spacer(Modifier.height(MONTH_ROW_HEIGHT))
            for (offset in 0 until DAYS_IN_WEEK) {
                val weekday = firstDayOfWeek.plus(offset.toLong())
                Text(
                    // Every other row, or the letters collide on a phone.
                    text = if (offset % 2 == 0) weekday.initial(locale) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(width = 16.dp, height = CELL_SIZE),
                )
            }
        }
        Row(
            // A year does not fit across a phone. Scrolled rather than
            // shrunk, because a square small enough to fit is a square
            // too small to tell apart from its neighbour.
            modifier = Modifier.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            weeks.forEachIndexed { index, week ->
                Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    Text(
                        text = months[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        // Names are wider than a column and run over the
                        // ones beside them rather than being clipped to
                        // an initial. The month that follows is far
                        // enough away to have room.
                        overflow = TextOverflow.Visible,
                        modifier = Modifier
                            .height(MONTH_ROW_HEIGHT)
                            .wrapContentWidth(align = Alignment.Start, unbounded = true),
                    )
                    week.forEach { day -> HeatmapCell(day, busiest) }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(day: ReadingDay?, busiest: Long) {
    if (day == null) {
        Box(Modifier.size(CELL_SIZE))
        return
    }
    val read = day.totalMs > 0
    val amount = readingDuration(day.totalMs)
    val date = day.date.format(lastReadFormat())
    val spoken = if (read) {
        stringResource(R.string.reading_stats_heatmap_day, amount, date)
    } else {
        stringResource(R.string.reading_stats_heatmap_empty_day, date)
    }
    // Four steps rather than a continuous ramp. A gradient invites the
    // reader to compare two squares that differ by a minute, which is
    // noise; four bands say quiet day, ordinary day, long day.
    val shade = when {
        !read -> 0f
        else -> {
            val share = day.totalMs.toFloat() / busiest
            when {
                share > 0.66f -> 1f
                share > 0.33f -> 0.7f
                else -> 0.4f
            }
        }
    }
    Box(
        Modifier
            .size(CELL_SIZE)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (read) {
                    MaterialTheme.colorScheme.primary.copy(alpha = shade)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clearAndSetSemantics { contentDescription = spoken },
    )
}

/** The one letter a weekday is known by in this language. */
private fun DayOfWeek.initial(locale: java.util.Locale): String =
    getDisplayName(TextStyle.NARROW, locale).take(1)

private const val DAYS_IN_WEEK = 7
private val CELL_SIZE = 13.dp
private val CELL_GAP = 3.5.dp
private val MONTH_ROW_HEIGHT = 16.dp
