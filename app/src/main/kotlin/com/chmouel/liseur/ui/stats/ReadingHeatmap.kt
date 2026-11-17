package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.ui.theme.homePalette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

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
internal fun ReadingHeatmap(
    days: List<ReadingDay>,
    modifier: Modifier = Modifier,
    onSelectDay: ((ReadingDay?) -> Unit)? = null,
) {
    if (days.isEmpty()) return
    var selectedDate by remember(days) { mutableStateOf<LocalDate?>(null) }
    val locale = LocalLocale.current.platformLocale
    val firstDayOfWeek = localeWeekStart(locale)
    // Pad the first column so every row is one weekday, the way a wall
    // calendar reads. Without this the grid is a spiral and the weekday
    // labels down the side are a lie.
    val leading = ((days.first().date.dayOfWeek.value - firstDayOfWeek.value) + DAYS_IN_WEEK) %
        DAYS_IN_WEEK
    val weeks = remember(days, leading) {
        (List<ReadingDay?>(leading) { null } + days).chunked(DAYS_IN_WEEK)
    }
    val busiest = remember(days) { days.maxOf { it.totalMs }.coerceAtLeast(1) }
    // A column is captioned when its first real day opens a month the
    // column before it did not, which puts the name where the month
    // starts rather than at a fixed interval that drifts off it.
    val months = remember(weeks, locale) { weeks.map { week -> week.firstNotNullOfOrNull { it }?.date }
        .runningFold<java.time.LocalDate?, Pair<java.time.Month?, String>>(null to "") { previous, date ->
            when {
                date == null -> previous.first to ""
                date.month == previous.first -> previous.first to ""
                else -> date.month to date.month.getDisplayName(TextStyle.SHORT, locale)
            }
        }
        .drop(1)
        .map { it.second } }
    val scroll = rememberLazyListState()
    // A span of months opens at its far end. The reader came to see how
    // this week went, and a grid that starts a year ago hides that
    // behind a gesture nothing on the screen suggests.
    LaunchedEffect(weeks.size) { scroll.scrollToItem(weeks.lastIndex) }
    val labelStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()
    // Large text needs a taller month row than the default one.
    val monthRowHeight = with(LocalDensity.current) {
        maxOf(MONTH_ROW_HEIGHT, textMeasurer.measure("M", labelStyle, maxLines = 1).size.height.toDp())
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            // Keeps the weekday letters level with the grid, which the
            // month row above has pushed down.
            Spacer(Modifier.height(monthRowHeight))
            for (offset in 0 until DAYS_IN_WEEK) {
                val weekday = firstDayOfWeek.plus(offset.toLong())
                // Every other row, or the letters collide on a phone. A
                // letter taller than its row spills into the empty ones
                // beside it instead of being clipped.
                Box(
                    modifier = Modifier.widthIn(min = 16.dp).height(CELL_SIZE),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (offset % 2 == 0) weekday.initial(locale) else "",
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentHeight(unbounded = true),
                    )
                }
            }
        }
        LazyRow(
            // A year does not fit across a phone. Scrolled rather than
            // shrunk, because a square small enough to fit is a square
            // too small to tell apart from its neighbour.
            state = scroll,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            itemsIndexed(weeks, key = { _, week -> week.firstNotNullOf { it?.date }.toEpochDay() }) { index, week ->
                Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    Text(
                        text = months[index],
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        // Names are wider than a column and run over the
                        // ones beside them rather than being clipped to
                        // an initial. The month that follows is far
                        // enough away to have room.
                        overflow = TextOverflow.Visible,
                        modifier = Modifier
                            .size(width = CELL_SIZE, height = monthRowHeight)
                            .wrapContentWidth(align = Alignment.Start, unbounded = true),
                    )
                    week.forEach { day ->
                        val isSelected = day != null && day.date == selectedDate
                        HeatmapCell(
                            day = day,
                            busiest = busiest,
                            isSelected = isSelected,
                            onClick = if (onSelectDay != null && day != null) {
                                {
                                    val next = if (selectedDate == day.date) null else day.date
                                    selectedDate = next
                                    onSelectDay(if (next != null) day else null)
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(
    day: ReadingDay?,
    busiest: Long,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (day == null) {
        Box(Modifier.size(CELL_SIZE))
        return
    }
    val palette = homePalette()
    val activeColor = palette?.progress ?: MaterialTheme.colorScheme.primary
    val emptyColor = palette?.pill ?: MaterialTheme.colorScheme.surfaceVariant
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
    val cellShape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(CELL_SIZE)
            .clip(cellShape)
            .background(
                if (read) {
                    activeColor.copy(alpha = shade)
                } else {
                    emptyColor
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, cellShape)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) selected = isSelected
            },
    )
}

/** The one letter a weekday is known by in this language. */
private fun DayOfWeek.initial(locale: java.util.Locale): String =
    getDisplayName(TextStyle.NARROW, locale).take(1)

private const val DAYS_IN_WEEK = 7
private val CELL_SIZE = 13.dp
private val CELL_GAP = 3.5.dp
private val MONTH_ROW_HEIGHT = 16.dp
