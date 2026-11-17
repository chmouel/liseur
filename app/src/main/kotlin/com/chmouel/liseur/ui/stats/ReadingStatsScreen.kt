package com.chmouel.liseur.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
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
import com.chmouel.liseur.ui.browseCover
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.theme.DarkHomePalette
import com.chmouel.liseur.ui.theme.homePalette
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.ui.withoutBottom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What the reader has actually read, added up.
 *
 * One figure set large and a ledger of plain cards beneath it, keeping
 * the rule that every card reports and none of them grades.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingStatsScreen(
    state: ReadingStatsUiState,
    onOpenBook: (BookReadingStats) -> Unit,
    /** Null when Stats is a tab of the bottom bar, which has no way back. */
    onBack: (() -> Unit)?,
    onSelectRange: (StatsRange) -> Unit = {},
    onRetry: () -> Unit = {},
    onSetWeeklyGoalMinutes: (Int) -> Unit = {},
    /** The room the bottom bar covers, navigation bar included. */
    bottomBarSpace: Dp = 0.dp,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.reading_stats)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            } else {
                StatsBrandHeader()
            }
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
            // The chips stay: the span itself may be what the server
            // cannot answer, and Retry alone would ask it again.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    Modifier
                        .widthIn(max = contentWidthCap(windowWidth()))
                        .fillMaxSize()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                ) {
                    RangeChips(state.range, onSelectRange)
                    StatsUnavailable(
                        onRetry = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
            return@Scaffold
        }
        val ready = state as ReadingStatsUiState.Ready
        val stats = ready.stats.hideBrieflyReadBooks()
        val empty = stats.isEmpty && ready.headline.totalMs <= 0
        var showGoalDialog by rememberSaveable { mutableStateOf(false) }
        if (showGoalDialog) {
            WeeklyGoalDialog(
                currentMinutes = ready.weeklyGoalMinutes,
                onSelectMinutes = { minutes ->
                    onSetWeeklyGoalMinutes(minutes)
                    showGoalDialog = false
                },
                onDismiss = { showGoalDialog = false },
            )
        }
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
            val locale = LocalLocale.current.platformLocale
            val dateFormat = lastReadFormat()
            val activityPeriods = remember(stats.recent, ready.range, locale) {
                readingPeriods(
                    days = stats.recent,
                    range = ready.range,
                    weekStart = localeWeekStart(locale),
                )
            }
            var selectedActivityIndex by remember(activityPeriods, ready.range) {
                mutableStateOf<Int?>(null)
            }
            val selectedActivityPeriod = selectedActivityIndex?.let { activityPeriods.getOrNull(it) }
            // Books the list above leaves out as briefly read stay out of the breakdown too.
            val listedKeys = remember(stats) { stats.books.mapTo(HashSet()) { it.key } }
            val selectedActivityBooks = remember(selectedActivityPeriod, ready.provenance, stats) {
                if (selectedActivityPeriod != null && ready.provenance == StatsProvenance.THIS_DEVICE) {
                    stats.booksIn(selectedActivityPeriod.from, selectedActivityPeriod.to)
                        .filter { it.key in listedKeys }
                } else {
                    emptyList()
                }
            }
            var selectedCalendarDay by remember(stats.recent, ready.range) {
                mutableStateOf<ReadingDay?>(null)
            }
            val calendarDay = selectedCalendarDay
            val selectedCalendarBooks = remember(calendarDay, ready.provenance, stats) {
                if (calendarDay != null && ready.provenance == StatsProvenance.THIS_DEVICE) {
                    stats.booksIn(calendarDay.date, calendarDay.date)
                        .filter { it.key in listedKeys }
                } else {
                    emptyList()
                }
            }

            // No spacing between items: each section sets its own, so
            // the rows of the book list can sit flush as one card.
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 36.dp + maxOf(padding.calculateBottomPadding(), bottomBarSpace),
                    top = 4.dp,
                ),
            ) {
                // Above the empty state too: a span with nothing in it
                // must still offer a way to a longer one.
                item {
                    RangeChips(ready.range, onSelectRange)
                    ProvenanceLine(ready.range, ready.provenance)
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
                    ready.weekReadingMs?.let { weekReadingMs ->
                        item {
                            WeeklyGoalCard(
                                weekReadingMs = weekReadingMs,
                                weeklyGoalMinutes = ready.weeklyGoalMinutes,
                                onClick = { showGoalDialog = true },
                            )
                        }
                    }
                } else {
                    item {
                        DashboardHero(
                            stats = stats,
                            headline = ready.headline,
                            range = ready.range,
                        )
                    }
                    item {
                        OverviewSection(
                            stats = stats,
                            headline = ready.headline,
                            provenance = ready.provenance,
                        )
                    }
                    item {
                        ActivitySection(
                            days = stats.recent,
                            range = ready.range,
                            periods = activityPeriods,
                            selectedIndex = selectedActivityIndex,
                            onSelectIndex = { selectedActivityIndex = it },
                        )
                    }
                    if (selectedActivityPeriod != null && selectedActivityBooks.isNotEmpty()) {
                        val groupCount = selectedActivityBooks.size + 1
                        item(key = "activity-books-header") {
                            Spacer(Modifier.height(8.dp))
                            SelectedPeriodBooksHeader(
                                periodLabel = selectedActivityPeriod.spokenLabel(
                                    ready.range.chartPeriod,
                                    locale,
                                    dateFormat,
                                ),
                                books = selectedActivityBooks,
                                shape = groupedRowShape(0, groupCount),
                                onClear = { selectedActivityIndex = null },
                            )
                        }
                        itemsIndexed(
                            items = selectedActivityBooks,
                            key = { _, book -> "activity-book-${book.key}" },
                        ) { index, book ->
                            SelectedPeriodBookRow(
                                book = book,
                                shape = groupedRowShape(index + 1, groupCount),
                                onClick = if (book.isLocal) ({ onOpenBook(book) }) else null,
                            )
                        }
                    }
                    item {
                        ReadingRhythmCard(
                            stats = stats,
                            headline = ready.headline,
                            range = ready.range,
                        )
                    }
                    ready.weekReadingMs?.let { weekReadingMs ->
                        item {
                            WeeklyGoalCard(
                                weekReadingMs = weekReadingMs,
                                weeklyGoalMinutes = ready.weeklyGoalMinutes,
                                onClick = { showGoalDialog = true },
                            )
                        }
                    }
                    if (ready.range != StatsRange.THIS_WEEK) {
                        item {
                            CalendarSection(
                                days = stats.recent,
                                selectedDay = calendarDay,
                                onSelectDay = { selectedCalendarDay = it },
                            )
                        }
                        if (calendarDay != null && selectedCalendarBooks.isNotEmpty()) {
                            val groupCount = selectedCalendarBooks.size + 1
                            item(key = "calendar-books-header") {
                                Spacer(Modifier.height(8.dp))
                                SelectedPeriodBooksHeader(
                                    periodLabel = calendarDay.date.format(dateFormat),
                                    books = selectedCalendarBooks,
                                    shape = groupedRowShape(0, groupCount),
                                    onClear = { selectedCalendarDay = null },
                                )
                            }
                            itemsIndexed(
                                items = selectedCalendarBooks,
                                key = { _, book -> "calendar-book-${book.key}" },
                            ) { index, book ->
                                SelectedPeriodBookRow(
                                    book = book,
                                    shape = groupedRowShape(index + 1, groupCount),
                                    onClick = if (book.isLocal) ({ onOpenBook(book) }) else null,
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

/**
 * Brand mark and large serif heading when Reading Stats sits as a primary tab.
 */
@Composable
private fun StatsBrandHeader() {
    val palette = homePalette()
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.reading_stats),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}


/** How much of the screen the empty state centres itself in. */
private const val EMPTY_HEIGHT_SHARE = 0.75f

/**
 * The span the figures cover, as four equal-width pill buttons across the top.
 *
 * Falls back to a wrapping row of chips when some label would not fit its
 * share of the width in two lines without breaking a word, so long
 * translations never break mid-word or clip.
 */
@Composable
private fun RangeChips(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    val labels = StatsRange.entries.map { stringResource(it.chipLabel) }
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val count = StatsRange.entries.size
        val slot = (maxWidth - 8.dp * (count - 1)) / count - RANGE_CHIP_CHROME
        val slotPx = with(density) { slot.roundToPx() }.coerceAtLeast(0)
        val fits = labels.all { label ->
            label.split(' ').all { word ->
                with(density) {
                    textMeasurer.measure(word, style, maxLines = 1, softWrap = false).size.width.toDp()
                } <= slot
            } && !textMeasurer.measure(
                label, style, maxLines = 2, constraints = Constraints(maxWidth = slotPx),
            ).hasVisualOverflow
        }
        RangeChipRow(selected, onSelect, segmented = fits)
    }
}

/** A range chip's width besides its label: horizontal padding and border. */
private val RANGE_CHIP_CHROME = 6.dp * 2 + 2.dp

@Composable
private fun RangeChipRow(selected: StatsRange, onSelect: (StatsRange) -> Unit, segmented: Boolean) {
    val palette = homePalette()
    val eInk = LocalEInk.current
    if (!segmented) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsRange.entries.forEach { range ->
                FilterChip(
                    selected = range == selected,
                    onClick = { onSelect(range) },
                    label = { Text(stringResource(range.chipLabel)) },
                )
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsRange.entries.forEach { range ->
            val isSelected = range == selected
            val shape = RoundedCornerShape(12.dp)
            val bg = when {
                eInk && isSelected -> MaterialTheme.colorScheme.secondaryContainer
                eInk -> Color.Transparent
                isSelected -> palette?.tabSelected ?: MaterialTheme.colorScheme.secondaryContainer
                else -> palette?.card?.copy(alpha = 0.65f) ?: MaterialTheme.colorScheme.surfaceContainerLow
            }
            val borderColor = when {
                eInk && isSelected -> MaterialTheme.colorScheme.onSurface
                eInk -> MaterialTheme.colorScheme.outline
                isSelected -> (palette?.progress ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.55f)
                else -> palette?.cardBorder ?: MaterialTheme.colorScheme.outlineVariant
            }
            val contentColor = when {
                isSelected -> palette?.onTabSelected ?: MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = shape,
                color = bg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(range) },
                        role = Role.Tab,
                    ),
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Two lines, so a long translation such as "Gesamte
                    // Zeit" wraps instead of losing its end.
                    Text(
                        text = stringResource(range.chipLabel),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Provenance scope line beneath the range selector (ADR-0021).
 */
@Composable
private fun ProvenanceLine(
    range: StatsRange,
    provenance: StatsProvenance,
) {
    val scope = scopeLine(range, provenance)
    val palette = homePalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 12.dp, start = 2.dp),
    ) {
        Icon(
            imageVector = when (provenance) {
                StatsProvenance.THIS_DEVICE -> Icons.Outlined.PhoneAndroid
                StatsProvenance.ALL_DEVICES -> Icons.Outlined.Devices
            },
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = palette?.progress ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.reading_stats_joined,
                stringResource(scope.range),
                stringResource(scope.devices),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The total the screen leads with, inside an illustrated hero card.
 *
 * One figure, not one per device. When the sync server answered, the
 * total is its count of every device for the span the reader chose;
 * when it did not, the total is this device's own for the same span.
 */
@Composable
private fun DashboardHero(
    stats: ReadingStats,
    headline: StatsHeadline,
    range: StatsRange,
) {
    val eInk = LocalEInk.current
    val palette = homePalette()
    val dark = palette == DarkHomePalette
    val cardColor = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow
    val shape = RoundedCornerShape(22.dp)
    val backgroundModifier = if (!eInk && palette != null) {
        val start = lerp(cardColor, palette.heroWash, if (dark) 0.9f else 0.42f)
        val end = lerp(cardColor, palette.heroWash, if (dark) 0.28f else 0.18f)
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
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(backgroundModifier),
        ) {
            if (!eInk) {
                Image(
                    painter = painterResource(
                        if (dark) R.drawable.stats_hero_art_night else R.drawable.stats_hero_art,
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(195.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(range.eyebrowRes).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.3.sp,
                    ),
                    color = palette?.progress ?: MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = readingDuration(headline.totalMs),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = TABULAR,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                headline.comparison?.let { comparison ->
                    Spacer(Modifier.height(4.dp))
                    ComparisonLine(comparison)
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HeroPill(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        label = pluralStringResource(
                            R.plurals.reading_stats_pill_sessions,
                            headline.sessions,
                            headline.sessions,
                        ),
                    )
                    HeroPill(
                        icon = Icons.Outlined.LocalFireDepartment,
                        label = pluralStringResource(
                            R.plurals.reading_stats_pill_streak,
                            headline.streakDays,
                            headline.streakDays,
                        ),
                    )
                    HeroPill(
                        icon = Icons.Outlined.Layers,
                        label = pluralStringResource(
                            R.plurals.reading_stats_pill_books,
                            stats.booksRead,
                            stats.booksRead,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPill(
    icon: ImageVector,
    label: String,
) {
    val palette = homePalette()
    val eInk = LocalEInk.current
    val pillShape = RoundedCornerShape(50)
    Surface(
        shape = pillShape,
        color = when {
            eInk -> Color.Transparent
            palette != null -> palette.pill.copy(alpha = 0.9f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            when {
                eInk -> MaterialTheme.colorScheme.outline
                palette != null -> palette.cardBorder
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette?.progress ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = TABULAR,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The 2x2 overview grid under the hero card.
 */
@Composable
private fun OverviewSection(
    stats: ReadingStats,
    headline: StatsHeadline,
    provenance: StatsProvenance,
) {
    val paceLabel = if (provenance == StatsProvenance.ALL_DEVICES && headline.serverOnlyPace) {
        stringResource(R.string.reading_stats_pace_server_only)
    } else {
        stringResource(R.string.reading_stats_tile_pace)
    }
    val paceValue = headline.progressionPerHour?.let { pace ->
        stringResource(R.string.reading_stats_pace_value, (pace * 100).roundToInt())
    } ?: stringResource(R.string.reading_stats_pace_unknown)

    val tiles = listOf(
        Triple(
            Icons.AutoMirrored.Outlined.MenuBook,
            stringResource(R.string.reading_stats_books_finished),
            stats.booksFinished.toString(),
        ),
        Triple(
            Icons.Outlined.LocalFireDepartment,
            stringResource(R.string.reading_stats_tile_streak),
            pluralStringResource(
                R.plurals.reading_stats_tile_streak_days,
                headline.streakDays,
                headline.streakDays,
            ),
        ),
        Triple(
            Icons.Outlined.BarChart,
            stringResource(R.string.reading_stats_tile_sessions),
            headline.sessions.toString(),
        ),
        Triple(Icons.Outlined.Speed, paceLabel, paceValue),
    )
    StatsSectionHeader(stringResource(R.string.reading_stats_overview))
    OverviewGrid(tiles)
}

@Composable
internal fun OverviewGrid(tiles: List<Triple<ImageVector, String, String>>) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val valueStyle = overviewValueStyle()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints {
        // Tiles go two to a row only when every figure fits half the width
        // on one line and every label fits it in two lines without breaking
        // a word; otherwise they stack rather than cut text short.
        val halfText = (maxWidth - 10.dp) / 2 - TILE_CHROME
        fun width(text: String, style: ComposeTextStyle) = with(density) {
            textMeasurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp()
        }
        val halfPx = with(density) { halfText.roundToPx() }.coerceAtLeast(0)
        val fitsTwo = tiles.all { (_, label, value) ->
            width(value, valueStyle) <= halfText &&
                label.split(' ').all { width(it, labelStyle) <= halfText } &&
                !textMeasurer.measure(
                    label, labelStyle, maxLines = 2,
                    constraints = Constraints(maxWidth = halfPx),
                ).hasVisualOverflow
        }
        val perRow = if (fitsTwo) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { (icon, label, value) ->
                        OverviewTile(
                            icon = icon,
                            label = label,
                            value = value,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** A tile's width besides its text: padding, badge, the gap after it and the border. */
private val TILE_CHROME = 14.dp * 2 + 38.dp + 12.dp + 2.dp

@Composable
private fun overviewValueStyle() = MaterialTheme.typography.titleLarge.copy(
    fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = TABULAR,
)

@Composable
internal fun OverviewTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val palette = homePalette()
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    val badgeBg = palette?.pill ?: MaterialTheme.colorScheme.secondaryContainer
    StatsCard(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$label, $value"
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = value,
                    style = overviewValueStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The activity bar chart inside its own card with a header and Y-axis scale.
 */
@Composable
internal fun ActivitySection(
    days: List<ReadingDay>,
    range: StatsRange,
    periods: List<ReadingPeriod>? = null,
    selectedIndex: Int? = null,
    onSelectIndex: ((Int?) -> Unit)? = null,
) {
    val locale = LocalLocale.current.platformLocale
    val resolvedPeriods = periods ?: readingPeriods(
        days = days,
        range = range,
        weekStart = localeWeekStart(locale),
    )
    var internalSelectedIndex by remember(resolvedPeriods, range) { mutableStateOf<Int?>(null) }
    val activeSelectedIndex = if (onSelectIndex != null) selectedIndex else internalSelectedIndex
    val updateSelectedIndex: (Int?) -> Unit = onSelectIndex ?: { internalSelectedIndex = it }
    val selectedPeriod = activeSelectedIndex?.let { resolvedPeriods.getOrNull(it) }
    val dateFormat = lastReadFormat()
    val subtitle = if (selectedPeriod != null) {
        val spokenPeriod = selectedPeriod.spokenLabel(range.chartPeriod, locale, dateFormat)
        if (selectedPeriod.totalMs > 0) {
            stringResource(
                R.string.reading_stats_period_read,
                readingDuration(selectedPeriod.totalMs),
                spokenPeriod,
            )
        } else {
            stringResource(R.string.reading_stats_no_reading_period, spokenPeriod)
        }
    } else {
        stringResource(range.chartSubtitleRes)
    }
    Spacer(Modifier.height(14.dp))
    StatsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text(
                text = stringResource(range.chartTitleRes),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedPeriod != null) {
                    homePalette()?.progress ?: MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(18.dp))
            PeriodBars(
                periods = resolvedPeriods,
                unit = range.chartPeriod,
                today = days.lastOrNull()?.date,
                selectedIndex = activeSelectedIndex,
                onSelectIndex = { index ->
                    updateSelectedIndex(if (activeSelectedIndex == index) null else index)
                },
            )
        }
    }
}

/**
 * Chooses a clean top scale (in minutes) divisible by 4 so the 5 Y-axis
 * guide lines land on round minute/hour boundaries.
 */
internal fun chartAxisMaxMinutes(busiestMs: Long): Int {
    val busiestMin = ceil(busiestMs.coerceAtLeast(0L) / 60_000.0).toInt()
    if (busiestMin <= 60) return 80
    val stepCandidates = intArrayOf(20, 30, 45, 60, 120, 180, 240, 360, 480, 720, 1440)
    for (step in stepCandidates) {
        val top = step * 4
        if (top >= busiestMin) return top
    }
    val stepHours = ceil(busiestMin / (4.0 * 60.0)).toInt().coerceAtLeast(1)
    return stepHours * 60 * 4
}

/** Formats a Y-axis step value in minutes as a compact label (`80m`, `2h`, `0m`). */
internal fun chartAxisStepLabel(minutes: Int): String {
    if (minutes <= 0) return "0m"
    if (minutes >= 120 && minutes % 60 == 0) return "${minutes / 60}h"
    return "${minutes}m"
}

/**
 * A range of reading summarised in calendar-sized bars with a Y-axis scale.
 */
@Composable
private fun PeriodBars(
    periods: List<ReadingPeriod>,
    unit: StatsChartPeriod,
    today: LocalDate? = null,
    selectedIndex: Int? = null,
    onSelectIndex: ((Int) -> Unit)? = null,
) {
    val busiest = periods.maxOfOrNull { it.totalMs }?.coerceAtLeast(1L) ?: 1L
    val axisMaxMinutes = chartAxisMaxMinutes(busiest)
    val axisMaxMs = axisMaxMinutes * 60_000L
    val locale = LocalLocale.current.platformLocale
    val currentIndex = periods.indexOfLast { today != null && !it.from.isAfter(today) }
        .takeIf { it >= 0 } ?: periods.lastIndex
    val showAmounts = periods.size <= DAYS_IN_WEEK
    val eInk = LocalEInk.current
    val palette = homePalette()
    val gap = when {
        periods.size <= DAYS_IN_WEEK -> 5.dp
        periods.size <= DAYS_IN_WEEK * 2 -> 5.dp
        periods.size <= DAYS_IN_WEEK * 3 -> 3.dp
        else -> 2.dp
    }
    val corner = if (periods.size <= DAYS_IN_WEEK) 8.dp else 3.dp
    val dateFormat = lastReadFormat()
    val scrollState = rememberScrollState()
    // Each slot must hold its callout at the current text size; when the
    // slots do not fit the card, the chart scrolls rather than clip them.
    val calloutStyle = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = TABULAR,
    )
    // A callout shows on every bar of a week, and on the selected bar
    // anywhere else. Both the room above the plot and the width of a slot
    // have to hold it.
    val calloutsShown = showAmounts || onSelectIndex != null
    val axisStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Vertical space follows the text size too, so large fonts grow the chart
    // instead of pushing the day labels below the zero line.
    fun lineHeight(style: ComposeTextStyle) =
        with(density) { textMeasurer.measure("0h", style, maxLines = 1).size.height.toDp() }
    val axisLabelHalf = lineHeight(axisStyle) / 2
    val plotHeight = maxOf(136.dp, axisLabelHalf * 2 * 5)
    val labelAreaHeight = maxOf(26.dp, lineHeight(MaterialTheme.typography.labelSmall) + 8.dp)
    val calloutHeadroom = maxOf(
        axisLabelHalf,
        if (calloutsShown) lineHeight(calloutStyle) + CALLOUT_CHROME else 6.dp,
    )
    // The date under each bar must fit its slot too; bold is the widest weight.
    val dateStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val widestDate = periods.maxOfOrNull {
        textMeasurer.measure(it.shortLabel(unit, locale), dateStyle, maxLines = 1, softWrap = false).size.width
    } ?: 0
    // A slot must hold the callout drawn on a selected bar as well, or the
    // pill spills over its neighbours once the chart is wider than a week.
    val widestCallout = if (calloutsShown) {
        periods.mapNotNull { compactDuration(it.totalMs) }.maxOfOrNull {
            textMeasurer.measure(it, calloutStyle, maxLines = 1, softWrap = false).size.width
        }
    } else {
        null
    }
    val slotWidth = with(density) {
        maxOf(
            MIN_BAR_SLOT,
            widestDate.toDp() + 4.dp,
            widestCallout?.let { it.toDp() + CalloutPadding * 2 + 2.dp } ?: 0.dp,
        )
    }

    val gridLineColor = (palette?.cardBorder ?: MaterialTheme.colorScheme.outlineVariant)
        .copy(alpha = if (eInk) 1f else 0.55f)
    val steps = remember(axisMaxMinutes) {
        listOf(
            axisMaxMinutes,
            (axisMaxMinutes * 3) / 4,
            axisMaxMinutes / 2,
            axisMaxMinutes / 4,
            0,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Y-axis labels overhang the plot by half a line so the first and last
        // label centre on the top and bottom grid lines.
        Column(
            modifier = Modifier
                .padding(
                    top = calloutHeadroom - axisLabelHalf,
                    bottom = labelAreaHeight - axisLabelHalf,
                    end = 10.dp,
                )
                .height(plotHeight + axisLabelHalf * 2),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            steps.forEach { stepMinutes ->
                Text(
                    text = chartAxisStepLabel(stepMinutes),
                    style = axisStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Plot area with horizontal guide lines and bars
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(calloutHeadroom + plotHeight + labelAreaHeight),
        ) {
            val scrollable = slotWidth * periods.size + gap * (periods.size - 1) > maxWidth
            LaunchedEffect(periods, scrollable, scrollState.maxValue, currentIndex, maxWidth) {
                if (scrollable) {
                    val target = currentIndex.takeIf { it >= 0 }?.let { index ->
                        with(density) { (slotWidth * (index + 1) + gap * index - maxWidth).roundToPx() }
                            .coerceAtLeast(0)
                    } ?: scrollState.maxValue
                    scrollState.scrollTo(target.coerceAtMost(scrollState.maxValue))
                }
            }
            // Horizontal guide lines aligned with the 5 Y-axis steps
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = calloutHeadroom, bottom = labelAreaHeight)
                    .height(plotHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                repeat(steps.size) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(gridLineColor),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.Bottom,
            ) {
                periods.forEachIndexed { index, period ->
                    val isCurrent = index == currentIndex
                    val isSelected = index == selectedIndex
                    val isHighlighted = if (selectedIndex != null) isSelected else isCurrent
                    val amount = readingDuration(period.totalMs)
                    val label = period.shortLabel(unit, locale)
                    val spokenPeriod = period.spokenLabel(unit, locale, dateFormat)
                    val fraction = (period.totalMs.toFloat() / axisMaxMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    val barHeight = if (period.totalMs > 0) {
                        (plotHeight * fraction).coerceAtLeast(8.dp)
                    } else {
                        2.dp
                    }
                    val spoken = if (period.totalMs > 0) {
                        stringResource(R.string.reading_stats_period_read, amount, spokenPeriod)
                    } else {
                        stringResource(R.string.reading_stats_no_reading_period, spokenPeriod)
                    }
                    val topTint = palette?.progress ?: MaterialTheme.colorScheme.primary
                    val bottomTint = palette?.accent ?: MaterialTheme.colorScheme.primary
                    val barShape = RoundedCornerShape(
                        topStart = corner,
                        topEnd = corner,
                        bottomStart = 3.dp,
                        bottomEnd = 3.dp,
                    )
                    Column(
                        modifier = Modifier
                            .then(if (scrollable) Modifier.width(slotWidth) else Modifier.weight(1f))
                            .fillMaxHeight()
                            .then(
                                if (onSelectIndex != null) {
                                    Modifier.clickable(role = Role.Button) { onSelectIndex(index) }
                                } else {
                                    Modifier
                                },
                            )
                            .clearAndSetSemantics {
                                contentDescription = spoken
                                if (onSelectIndex != null) selected = isSelected
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        val compact = compactDuration(period.totalMs) ?: if (isSelected) "0m" else null
                        if ((showAmounts || isSelected) && compact != null) {
                            BarCalloutPill(
                                text = compact,
                                highlighted = isHighlighted,
                            )
                            Spacer(Modifier.height(3.dp))
                        }
                        Box(
                            Modifier
                                .widthIn(max = 36.dp)
                                .fillMaxWidth()
                                .height(barHeight)
                                .clip(barShape)
                                .then(
                                    when {
                                        period.totalMs <= 0 -> Modifier.background(gridLineColor)
                                        eInk -> Modifier.background(
                                            if (isHighlighted) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                        )
                                        else -> {
                                            val alpha = when {
                                                isHighlighted -> 1f
                                                selectedIndex != null -> 0.45f
                                                else -> 0.82f
                                            }
                                            Modifier.background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        topTint.copy(alpha = alpha),
                                                        bottomTint.copy(alpha = alpha),
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                ),
                        )
                        Box(
                            modifier = Modifier.height(labelAreaHeight),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (isHighlighted) {
                                    MaterialTheme.colorScheme.onSurface
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
        }
    }
}

/** The narrowest a bar's slot gets, and the callout's side padding. */
private val MIN_BAR_SLOT = 34.dp
private val CalloutPadding = 4.dp

/** Callout height besides its text: padding, border, pointer and gap above the bar. */
private val CALLOUT_CHROME = 2.dp * 2 + 1.dp * 2 + 4.dp + 3.dp + 2.dp

@Composable
private fun BarCalloutPill(
    text: String,
    highlighted: Boolean,
) {
    val palette = homePalette()
    val eInk = LocalEInk.current
    val pillBg = when {
        eInk -> MaterialTheme.colorScheme.surfaceContainerHigh
        highlighted -> palette?.tabSelected ?: MaterialTheme.colorScheme.secondaryContainer
        else -> palette?.pill ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = when {
        eInk -> MaterialTheme.colorScheme.outline
        highlighted -> (palette?.progress ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.55f)
        else -> palette?.cardBorder ?: MaterialTheme.colorScheme.outlineVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = pillBg,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = TABULAR,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = CalloutPadding, vertical = 2.dp),
            )
        }
        if (!eInk) {
            Canvas(Modifier.size(width = 8.dp, height = 4.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, color = pillBg)
            }
        }
    }
}

/**
 * Chooses the rhythm card headline resource from the number of active days
 * in the selected window.
 */
@StringRes
internal fun readingRhythmTitleRes(activeDays: Int, totalDays: Int): Int {
    val safeTotal = totalDays.coerceAtLeast(1)
    return when {
        activeDays >= safeTotal -> R.string.reading_stats_rhythm_title_daily
        activeDays * 2 >= safeTotal -> R.string.reading_stats_rhythm_title_steady
        else -> R.string.reading_stats_rhythm_title_starting
    }
}

/**
 * Reading rhythm card summarizing how many days in the current window had reading,
 * plus average sitting length and peak day.
 */
@Composable
private fun ReadingRhythmCard(
    stats: ReadingStats,
    headline: StatsHeadline,
    range: StatsRange,
) {
    val palette = homePalette()
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    val activeDays = stats.recent.count { it.totalMs > 0 }
    val totalDays = stats.recent.size.coerceAtLeast(1)
    val titleRes = readingRhythmTitleRes(activeDays, totalDays)
    val detail = if (range == StatsRange.THIS_WEEK) {
        pluralStringResource(R.plurals.reading_stats_rhythm_detail_week, activeDays, activeDays, totalDays)
    } else {
        pluralStringResource(R.plurals.reading_stats_rhythm_detail_period, activeDays, activeDays, totalDays)
    }
    val avgSittingMs = if (headline.sessions > 0 && headline.totalMs > 0) {
        headline.totalMs / headline.sessions
    } else {
        null
    }
    val peakDay = stats.recent.filter { it.totalMs > 0 }.maxByOrNull { it.totalMs }
    val locale = LocalLocale.current.platformLocale
    val dateFormat = lastReadFormat()

    Spacer(Modifier.height(14.dp))
    StatsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(palette?.pill ?: MaterialTheme.colorScheme.secondaryContainer)
                        .border(
                            1.dp,
                            (palette?.progress ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.35f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(palette?.cardBorder ?: MaterialTheme.colorScheme.outlineVariant),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reading_stats_rhythm_eyebrow).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                        ),
                        color = accent,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (avgSittingMs != null || peakDay != null) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    avgSittingMs?.let { ms ->
                        HeroPill(
                            icon = Icons.Outlined.Schedule,
                            label = stringResource(
                                R.string.reading_stats_joined,
                                stringResource(R.string.reading_stats_avg_sitting),
                                readingDuration(ms),
                            ),
                        )
                    }
                    peakDay?.let { day ->
                        val dayLabel = if (range == StatsRange.THIS_WEEK) {
                            day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                        } else {
                            day.date.format(dateFormat)
                        }
                        HeroPill(
                            icon = Icons.Outlined.WbSunny,
                            label = stringResource(
                                R.string.reading_stats_joined,
                                dayLabel,
                                readingDuration(day.totalMs),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSection(
    days: List<ReadingDay>,
    selectedDay: ReadingDay?,
    onSelectDay: (ReadingDay?) -> Unit,
) {
    val dateFormat = lastReadFormat()
    val trailing = selectedDay?.let { day ->
        val date = day.date.format(dateFormat)
        if (day.totalMs > 0) {
            stringResource(R.string.reading_stats_heatmap_day, readingDuration(day.totalMs), date)
        } else {
            stringResource(R.string.reading_stats_heatmap_empty_day, date)
        }
    }
    StatsSectionHeader(
        title = stringResource(R.string.reading_stats_calendar),
        trailing = trailing,
    )
    StatsCard {
        ReadingHeatmap(
            days = days,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            selectedDate = selectedDay?.date,
            onSelectDay = onSelectDay,
        )
    }
}

/**
 * Header item for the lazy breakdown of books read on a selected bar or heatmap day.
 */
@Composable
private fun SelectedPeriodBooksHeader(
    periodLabel: String,
    books: List<BookReadingStats>,
    shape: Shape,
    onClear: () -> Unit,
) {
    val palette = homePalette()
    val eInk = LocalEInk.current
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    val totalMs = books.sumOf { it.totalMs }
    val totalSessions = books.sumOf { it.sessions }
    val headerSummary = buildString {
        append(
            stringResource(
                R.string.reading_stats_joined,
                periodLabel,
                readingDuration(totalMs),
            ),
        )
        if (totalSessions > 0) {
            append(" \u00b7 ")
            append(
                pluralStringResource(
                    R.plurals.reading_stats_book_sessions,
                    totalSessions,
                    totalSessions,
                ),
            )
        }
    }

    Surface(
        shape = shape,
        color = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = headerSummary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accent,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectedPeriodBookRow(
    book: BookReadingStats,
    shape: Shape,
    onClick: (() -> Unit)?,
) {
    val palette = homePalette()
    val eInk = LocalEInk.current
    val sessionsLine = if (book.sessions > 0) {
        pluralStringResource(R.plurals.reading_stats_book_sessions, book.sessions, book.sessions)
    } else {
        null
    }
    val subline = when {
        book.author != null && sessionsLine != null -> stringResource(
            R.string.reading_stats_joined,
            book.author,
            sessionsLine,
        )
        book.author != null -> book.author
        else -> sessionsLine
    }

    Surface(
        shape = shape,
        color = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) {
        Column {
            StatsRowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsCoverThumbnail(
                    title = book.title,
                    coverPath = book.coverPath,
                    coverUrl = book.coverUrl,
                    browseServerId = book.browseServerId,
                    modifier = Modifier
                        .width(34.dp)
                        .height(51.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subline?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = readingDuration(book.totalMs),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = TABULAR,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Weekly reading goal card with progress bar and tap-to-edit target duration.
 */
@Composable
private fun WeeklyGoalCard(
    weekReadingMs: Long,
    weeklyGoalMinutes: Int,
    onClick: () -> Unit,
) {
    val palette = homePalette()
    val eInk = LocalEInk.current
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    val weeklyReadMs = weekReadingMs
    val goalMs = weeklyGoalMinutes * 60_000L
    val goalHours = weeklyGoalMinutes / 60
    val progress = (weeklyReadMs.toFloat() / goalMs.toFloat()).coerceIn(0f, 1f)
    val currentText = if (weeklyReadMs <= 0L) {
        "0m"
    } else {
        readingDuration(weeklyReadMs)
    }
    val goalText = compactDuration(goalMs) ?: "${goalHours}h"
    val title = stringResource(R.string.reading_stats_weekly_goal_title)
    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
        fontWeight = FontWeight.Bold,
    )
    val progressLabel = stringResource(
        R.string.reading_stats_weekly_goal_progress,
        currentText,
        goalText,
    )
    val progressStyle = MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = TABULAR,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val progressText = @Composable {
        Text(text = progressLabel, style = progressStyle, color = MaterialTheme.colorScheme.onSurface)
    }

    Spacer(Modifier.height(14.dp))
    StatsCard(
        modifier = Modifier
            .clip(RoundedCornerShape(StatsCardCorner))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // The figure sits beside the title only when both fit on one line;
            // otherwise it moves under the subtitle so the title keeps its width.
            val stackProgress = with(density) {
                val measured =
                    textMeasurer.measure(title, titleStyle, maxLines = 1, softWrap = false).size.width +
                        textMeasurer.measure(progressLabel, progressStyle, maxLines = 1, softWrap = false).size.width
                measured.toDp() > maxWidth - 40.dp - 12.dp * 2
            }
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(palette?.pill ?: MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TrackChanges,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = titleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.reading_stats_weekly_goal_subtitle,
                                goalHours,
                                goalHours,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (stackProgress) progressText()
                    }
                    if (!stackProgress) progressText()
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette?.pill ?: MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (progress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .then(
                                    if (eInk) {
                                        Modifier.background(MaterialTheme.colorScheme.primary)
                                    } else {
                                        Modifier.background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    palette?.accent ?: MaterialTheme.colorScheme.primary,
                                                    accent,
                                                ),
                                            ),
                                        )
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

private val WEEKLY_GOAL_HOUR_CHOICES = listOf(1, 2, 3, 5, 7, 10)

@Composable
private fun WeeklyGoalDialog(
    currentMinutes: Int,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.reading_stats_weekly_goal_dialog_title))
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                WEEKLY_GOAL_HOUR_CHOICES.forEach { hours ->
                    val minutes = hours * 60
                    val selected = currentMinutes == minutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(
                                selected = selected,
                                onClick = { onSelectMinutes(minutes) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.reading_stats_weekly_goal_subtitle,
                                hours,
                                hours,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private val StatsRange.eyebrowRes: Int
    get() = when (this) {
        StatsRange.THIS_WEEK -> R.string.reading_stats_eyebrow_week
        StatsRange.THIS_MONTH -> R.string.reading_stats_eyebrow_month
        StatsRange.THIS_YEAR -> R.string.reading_stats_eyebrow_year
        StatsRange.ALL_TIME -> R.string.reading_stats_eyebrow_all
    }

private val StatsRange.chartTitleRes: Int
    get() = when (chartPeriod) {
        StatsChartPeriod.DAY -> R.string.reading_stats_chart_title_day
        StatsChartPeriod.WEEK -> R.string.reading_stats_chart_title_week
        StatsChartPeriod.MONTH -> R.string.reading_stats_chart_title_month
        StatsChartPeriod.YEAR -> R.string.reading_stats_chart_title_year
    }

private val StatsRange.chartSubtitleRes: Int
    get() = when (chartPeriod) {
        StatsChartPeriod.DAY -> R.string.reading_stats_chart_subtitle_day
        StatsChartPeriod.WEEK -> R.string.reading_stats_chart_subtitle_week
        StatsChartPeriod.MONTH -> R.string.reading_stats_chart_subtitle_month
        StatsChartPeriod.YEAR -> R.string.reading_stats_chart_subtitle_year
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
    val palette = homePalette()
    val eInk = LocalEInk.current
    Surface(
        shape = shape,
        color = palette?.card ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
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
                    val progressFraction = when {
                        book.finished -> 1f
                        book.progression != null -> book.progression.toFloat().coerceIn(0f, 1f)
                        else -> null
                    }
                    if (progressFraction != null && progressFraction > 0f) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    palette?.pill ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        palette?.progress ?: MaterialTheme.colorScheme.primary,
                                    ),
                            )
                        }
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
