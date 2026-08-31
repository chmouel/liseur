package com.chmouel.liseur.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.ReadingSessionDao
import com.chmouel.liseur.data.liseursync.InsightDay
import com.chmouel.liseur.data.liseursync.InsightsSummary
import com.chmouel.liseur.data.liseursync.LiseurSyncInsights
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.ComparisonSpans
import com.chmouel.liseur.domain.ReadingComparison
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.SpanTotals
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import com.chmouel.liseur.domain.compareReading
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.localeWeekStart
import com.chmouel.liseur.domain.readingStats
import com.chmouel.liseur.domain.readingTotals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReadingStatsUiState {
    data object Loading : ReadingStatsUiState
    data class Ready(
        val stats: ReadingStats,
        val headline: StatsHeadline,
        val range: StatsRange,
    ) : ReadingStatsUiState
}

/**
 * The one set of figures the screen leads with, merged across devices.
 *
 * The server's count is the same reading seen on every device, so where
 * it answers for the same span it is the superset and it wins; this
 * device's own sessions are part of it, so summing the two would count
 * them twice. When the server has nothing — offline, or no statistics
 * token — the local figures stand in.
 *
 * [comparison] is null for a span with no previous period to measure
 * against, which is only "all time".
 */
data class StatsHeadline(
    val totalMs: Long,
    val sessions: Int,
    val streakDays: Int,
    /** Fraction of a book per hour, or null with nothing to divide by. */
    val progressionPerHour: Double?,
    val comparison: ReadingComparison? = null,
)

sealed interface BookReadingStatsUiState {
    data object Loading : BookReadingStatsUiState
    data object Empty : BookReadingStatsUiState
    data class Ready(val stats: BookReadingStats) : BookReadingStatsUiState
}

/**
 * What the reading dashboard shows.
 *
 * Local sums are recomputed from the sessions each time rather than kept
 * as a running total anywhere. When liseur-sync answers, its calendar and
 * per-book aggregates replace the matching local slices because they
 * already contain this device's uploads as well as every other device's.
 *
 * Both sources are asked about the same [StatsRange], which is the whole
 * point of the range living in one place: while the server was pinned to
 * thirty days and the book list to a lifetime, the headline and the rows
 * under it could not be added up, and connecting a server made a
 * reader's total shrink.
 */
class ReadingStatsViewModel(
    sessionDao: ReadingSessionDao,
    bookDao: BookDao,
    progressDao: ReadingProgressDao,
    private val insights: LiseurSyncInsights? = null,
    private val settings: AppSettingsRepository? = null,
    initialRange: StatsRange = StatsRange.Default,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: (ZoneId) -> LocalDate = LocalDate::now,
    initialWeekStart: DayOfWeek = localeWeekStart(Locale.getDefault()),
) : ViewModel() {

    /**
     * Everything that decides which question the screen is asking.
     *
     * One value rather than four, because all four have to move
     * together. The span, the day it ends on, the day the reader's week
     * begins and the zone the sums are done in are a single question,
     * and the answers to it — this device's and the server's — are only
     * comparable if they were asked the same one. While the day and the
     * zone were sampled from the clock wherever they happened to be
     * needed, "this week" could be resolved twice in one emission and
     * come out differently either side of midnight.
     *
     * It also makes an answer refusable. A `combine` does not
     * synchronise its inputs, so a server reply can arrive between a
     * range change and the local recomputation it triggers; tagging each
     * reply with the window it was asked about is what stops a month's
     * total being merged into a week's.
     */
    private data class StatsWindow(
        val range: StatsRange,
        val weekStart: DayOfWeek,
        val zone: ZoneId,
        val today: LocalDate,
    )

    /** A server answer, and the question it was the answer to. */
    private data class Answered<T>(val window: StatsWindow, val value: T)

    private fun <T> Answered<T>?.forWindow(window: StatsWindow): T? =
        this?.takeIf { it.window == window }?.value

    private val _window = MutableStateFlow(
        zone().let { StatsWindow(initialRange, initialWeekStart, it, today(it)) },
    )

    val range: StateFlow<StatsRange> =
        _window.map { it.range }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _window.value.range,
        )

    /** Set once the reader has picked a span for themselves. */
    private var rangeChosen = false

    /** Which refresh the answers now arriving are allowed to be from. */
    private var generation = 0L
    private var refresh: Job? = null

    private val _acrossDevices = MutableStateFlow<Answered<InsightsSummary?>?>(null)
    private val _previousAcrossDevices = MutableStateFlow<Answered<InsightsSummary?>?>(null)
    private val _recentAcrossDevices = MutableStateFlow<Answered<List<InsightDay>?>?>(null)
    private val _booksAcrossDevices = MutableStateFlow<Answered<Map<String, WorkInsights>?>?>(null)

    /**
     * The same reading, counted on every device rather than this one.
     *
     * Folded into the headline rather than shown beside it: the reader
     * does not care which machine did the reading, and two figures that
     * answer the same question differently are a doubt, not a feature.
     */
    private val acrossDevices = _acrossDevices.asStateFlow()

    /**
     * The same, for the period this one is being measured against.
     *
     * Kept apart from [acrossDevices] rather than fetched with it so
     * that a slow baseline cannot hold up the headline. Either may fail
     * on its own; a baseline that does falls back to this device's own
     * history, which is what the whole screen does without a server.
     */
    private val previousAcrossDevices = _previousAcrossDevices.asStateFlow()
    private val recentAcrossDevices = _recentAcrossDevices.asStateFlow()
    private val booksAcrossDevices = _booksAcrossDevices.asStateFlow()

    init {
        settings?.let { store ->
            viewModelScope.launch {
                val saved = store.current().statsRange
                // A reader who reached the menu before the store answered
                // has already said what they want; the stored value is
                // then a stale answer to a question they have re-asked.
                if (!rangeChosen) _window.update { it.copy(range = saved) }
                refreshServerInsights()
            }
        }
    }

    /**
     * Looks at a different span.
     *
     * The server answers are dropped rather than kept while the new ones
     * are in flight: a thirty-day total sitting under a caption that now
     * says seven is a wrong number, and briefly showing the local figures
     * instead is not.
     */
    fun selectRange(range: StatsRange) {
        rangeChosen = true
        // Persisted even when nothing changed. The menu can be opened
        // before the store has answered, in which case tapping what is
        // already shown is still the reader saying they want it — and
        // `rangeChosen` is about to stop the stored value from being
        // applied over the top of it.
        viewModelScope.launch { settings?.setStatsRange(range) }
        if (_window.value.range == range) return
        _window.update { it.copy(range = range) }
        forgetServerAnswers()
        refreshServerInsights()
    }

    /**
     * Follows the reader's language to a different first day of week.
     *
     * Called from the screen, which reads the locale as Compose state,
     * because a view model outlives the configuration change that moved
     * it: `Locale.getDefault()` is right when this is constructed and
     * stale from then on.
     */
    fun setWeekStart(day: DayOfWeek) {
        if (_window.value.weekStart == day) return
        _window.update { it.copy(weekStart = day) }
        // The server was asked for a span that began on the old week's
        // first day. Its answer is about days this screen no longer
        // claims to be showing.
        forgetServerAnswers()
        refreshServerInsights()
    }

    private fun forgetServerAnswers() {
        _acrossDevices.value = null
        _previousAcrossDevices.value = null
        _recentAcrossDevices.value = null
        _booksAcrossDevices.value = null
    }

    /**
     * Refreshes the server decoration whenever a statistics screen opens.
     *
     * The window moves to today first, and before the check for a server.
     * Nothing here observes the clock, so this visit is the moment the
     * screen learns what day it is; doing it after the early return would
     * leave the one reader with no server — for whom the local figures
     * are the entire screen — looking at yesterday's week for as long as
     * the app stayed open.
     *
     * Each refresh then replaces the last outright: the previous one is
     * cancelled and its answers are refused by generation, not by which
     * span they were for. Comparing spans is not enough — a reader who
     * looks at a week, then a month, then the week again would otherwise
     * let the first request's answer land on top of the third's, and
     * both requests are for the same window, so nothing about the reply
     * would look wrong.
     */
    fun refreshServerInsights() {
        val zone = zone()
        _window.update { it.copy(zone = zone, today = today(zone)) }
        val window = _window.value
        val source = insights ?: return
        val token = ++generation
        refresh?.cancel()
        val spans = window.range.comparison(window.today, window.weekStart)
        // A span with nothing to compare against must not keep an answer
        // fetched while it had one.
        if (spans == null) _previousAcrossDevices.value = null
        refresh = viewModelScope.launch {
            val end = window.today
            val week = window.weekStart
            launch {
                val summary = source.summary(window.range, end, week)
                if (token == generation) _acrossDevices.value = Answered(window, summary)
            }
            if (spans != null) {
                launch {
                    val summary = source.summary(spans.previous.from, spans.previous.to)
                    if (token == generation) {
                        _previousAcrossDevices.value = Answered(window, summary)
                    }
                }
            }
            launch {
                val calendar = source.calendar(
                    from = window.range.startDate(end, week) ?: maxOf(
                        EARLIEST_PLAUSIBLE_DAY,
                        end.minusDays(CALENDAR_HORIZON_DAYS),
                    ),
                    to = end,
                )
                if (token == generation) _recentAcrossDevices.value = Answered(window, calendar)
            }
            launch {
                val books = source.allBooks(window.range, end, week)
                if (token == generation) _booksAcrossDevices.value = Answered(window, books)
            }
        }
    }

    /**
     * How much longer this book has, according to every device.
     *
     * This is the same aggregate used by the dashboard row, so its total
     * and estimate are fetched together and cannot describe different
     * snapshots of the server.
     */
    fun serverEstimateFor(bookUrl: String): StateFlow<WorkInsights?> =
        booksAcrossDevices.map { answered ->
            answered.forWindow(_window.value)?.get(bookUrl)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    private data class LocalStats(
        val stats: ReadingStats,
        val books: Map<String, StatsBook>,
        /**
         * When each URL's first sitting began, over everything on record.
         *
         * Kept beside the ranged stats because a row only exists for a
         * URL with reading inside the window: a moved file whose old URL
         * was last read before the span would otherwise lose the date it
         * was begun to the range the reader happens to be looking at.
         */
        val firstReadAtByUrl: Map<String, Long>,
        /**
         * The question [stats] answers.
         *
         * Carried with the figures rather than read again from `_window`
         * downstream: `combine` does not synchronise its inputs, so a
         * freshly selected span could otherwise be paired with sums
         * still describing the previous one — a caption over numbers
         * that do not answer it. It is also what every server answer is
         * checked against before being merged.
         */
        val window: StatsWindow,
        /** The two spans being compared, or null for a span with none. */
        val spans: ComparisonSpans?,
        /**
         * This device's own reading over [spans]'s baseline period.
         *
         * Reduced from the same session list, in the same zone, by the
         * same day rule as [stats]. Anything less and the two halves of
         * the comparison could disagree about which side of midnight an
         * evening fell on, and report a difference the reader never made.
         */
        val previous: SpanTotals?,
    )

    private val local = combine(
        sessionDao.observeAll(),
        bookDao.observeAll(),
        progressDao.observeProgressions(),
        _window,
    ) { sessions, books, progressions, window ->
        val progressionByUrl = progressions.associateBy({ it.bookUrl }, { it.totalProgression })
        val statsBooks = books.associate { book ->
            book.url to StatsBook(
                bookUrl = book.url,
                title = book.displayTitle,
                author = book.displayAuthor,
                progression = progressionByUrl[book.url],
                finished = book.finished,
                coverPath = book.coverPath,
                coverUrl = book.coverUrl,
            )
        }
        val spans = sessions.map {
            SessionSpan(
                bookUrl = it.bookUrl,
                startedAt = it.startedAt,
                durationMs = it.durationMs,
                lastReadAt = it.lastCheckpointAt,
                uploaded = it.uploadedAt != null,
                startProgression = it.startProgression,
                endProgression = it.endProgression,
            )
        }
        val comparison = window.range.comparison(window.today, window.weekStart)
        LocalStats(
            stats = readingStats(
                sessions = spans,
                books = statsBooks,
                zone = window.zone,
                today = window.today,
                range = window.range,
                weekStart = window.weekStart,
            ),
            books = statsBooks,
            firstReadAtByUrl = spans
                .filter { it.durationMs > 0 }
                .groupBy { it.bookUrl }
                .mapValues { (_, sittings) -> sittings.minOf { it.startedAt } },
            window = window,
            spans = comparison,
            previous = comparison?.let { readingTotals(spans, window.zone, it.previous) },
        )
    }

    val state: StateFlow<ReadingStatsUiState> = combine(
        local,
        acrossDevices,
        recentAcrossDevices,
        booksAcrossDevices,
        previousAcrossDevices,
    ) { local, server, recent, serverBooks, previousServer ->
        val window = local.window
        val merged = mergeDashboard(
            local.stats,
            local.books,
            recent.forWindow(window),
            serverBooks.forWindow(window),
            local.firstReadAtByUrl,
        )
        ReadingStatsUiState.Ready(
            stats = merged,
            headline = mergeHeadline(
                merged = merged,
                local = local.stats,
                server = server.forWindow(window),
                spans = local.spans,
                previousLocal = local.previous,
                previousServer = previousServer.forWindow(window),
            ),
            range = window.range,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ReadingStatsUiState.Loading,
    )

    /**
     * One book's line, taken from the same figures as the dashboard so
     * that the two can never disagree about the same book.
     */
    fun forBook(bookUrl: String): StateFlow<BookReadingStatsUiState> =
        state.map { current ->
            when (current) {
                ReadingStatsUiState.Loading -> BookReadingStatsUiState.Loading
                is ReadingStatsUiState.Ready -> current.stats.books
                    .firstOrNull { it.bookUrl == bookUrl }
                    ?.let(BookReadingStatsUiState::Ready)
                    ?: BookReadingStatsUiState.Empty
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            BookReadingStatsUiState.Loading,
        )

    companion object {
        /** Long enough to survive a rotation without recomputing. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Where "all time" starts when asking a server for a calendar.
         *
         * The app did not exist before this, so no session can predate
         * it, and it spares the server a query back to 1970.
         */
        internal val EARLIEST_PLAUSIBLE_DAY: LocalDate = LocalDate.of(2024, 1, 1)

        /**
         * The longest calendar anyone is asked for.
         *
         * A fixed first day walks steadily further from today, and a
         * server that refuses spans past some horizon would eventually
         * refuse every "all time" request — a heatmap that works until
         * one particular year and then quietly stops. Ten rolling years
         * is far more than the app's own history and stays under any
         * plausible limit for good.
         */
        private const val CALENDAR_HORIZON_DAYS = 3650L

        /**
         * The one headline, from both sources.
         *
         * The total is the larger of what the server counted for this
         * span and what the rows beneath add up to. Never the smaller,
         * in either direction, and for two different reasons: sessions
         * upload in the background, so a stretch read five minutes ago
         * is real but not yet on the server; and a book read on another
         * device may not be in this library at all, so it can never
         * appear as a row. Taking the maximum keeps the headline at
         * least as large as the list under it, which is the only
         * relation between the two that reads correctly.
         *
         * Never the sum, either. The server's count already includes
         * this device's uploads, so adding them would pay twice.
         *
         * Sittings, streak and pace come from the server when it
         * answered, because it can see reading done elsewhere that no
         * local arithmetic can produce. Each falls back independently: a
         * server that reports a streak but no pace should not cost the
         * reader the pace this device works out for itself.
         *
         * The baseline is merged by the same rule as the total it is
         * measured against, and that is the point of doing it here
         * rather than anywhere else: were the current period allowed to
         * count a second device and the previous one not, an evening on
         * a laptop would appear in one half of the comparison and vanish
         * from the other, and the screen would report a change in the
         * reader's habits that was really a change in its own arithmetic.
         *
         * A baseline the server could not answer for falls back to this
         * device's own history rather than hiding the comparison. That
         * is what the rest of the screen does without a server, and a
         * statistics screen is not worth an error.
         */
        internal fun mergeHeadline(
            merged: ReadingStats,
            local: ReadingStats,
            server: InsightsSummary?,
            spans: ComparisonSpans? = null,
            previousLocal: SpanTotals? = null,
            previousServer: InsightsSummary? = null,
        ): StatsHeadline {
            val totalMs = maxOf(
                merged.totalMs,
                (server?.activeMinutes?.minutesAsMillis() ?: 0L) + local.pendingMs,
            )
            return StatsHeadline(
                totalMs = totalMs,
                sessions = maxOf(local.sessions, (server?.sessions ?: 0) + local.pendingSessions),
                streakDays = maxOf(local.streakDays, server?.streakDays ?: 0),
                progressionPerHour = server?.progressionPerHour ?: local.progressionPerHour,
                comparison = spans?.let {
                    val baseline = previousLocal ?: SpanTotals.Empty
                    compareReading(
                        period = it.period,
                        currentMs = totalMs,
                        previousMs = maxOf(
                            baseline.totalMs,
                            (previousServer?.activeMinutes?.minutesAsMillis() ?: 0L) +
                                baseline.pendingMs,
                        ),
                    )
                },
            )
        }

        /**
         * Folds the server's aggregates into the local ones, never
         * downwards.
         *
         * Both sides were asked about the same days, so the two figures
         * for one book are the same span counted on different numbers of
         * devices — but neither is reliably the larger. The server sees
         * reading done on a laptop that this device knows nothing about;
         * this device has the last twenty minutes, which are still
         * queued to upload.
         *
         * Taking the larger of the two would lose whichever part the
         * other lacks, so the server's total is offered the local time
         * it cannot have heard about yet — the sittings not yet uploaded
         * — and the larger of *that* and the local total is kept. The
         * outer maximum still matters: after a server switch, rows
         * uploaded to a previous one count as neither pending nor known
         * here, and the local total is then the only complete figure.
         *
         * One work with more than one local URL — the same file, moved —
         * becomes one row rather than two. The server counts it once, so
         * showing it twice would both charge the reader twice in the
         * total and present one book as two.
         */
        internal fun mergeDashboard(
            local: ReadingStats,
            knownBooks: Map<String, StatsBook>,
            serverRecent: List<InsightDay>?,
            serverBooks: Map<String, WorkInsights>?,
            firstReadAtByUrl: Map<String, Long> = emptyMap(),
        ): ReadingStats {
            val mergedBooks = local.books.associateBy { it.bookUrl }.toMutableMap()
            serverBooks.orEmpty().entries
                .filter { knownBooks.containsKey(it.key) }
                .groupBy { it.value.workId }
                .forEach { (_, entries) ->
                    val urls = entries.map { it.key }
                    val insight = entries.first().value
                    val rows = urls.mapNotNull { mergedBooks[it] }
                    // The row the reader has put the most time into is
                    // the one the merged figure belongs on; the rest are
                    // the same book under a URL it used to have.
                    val canonical = rows.maxWithOrNull(
                        compareBy<BookReadingStats> { it.totalMs }.thenByDescending { it.bookUrl },
                    )?.bookUrl ?: urls.min()
                    val metadata = knownBooks[canonical] ?: return@forEach
                    val serverMs = insight.activeMinutes.minutesAsMillis()
                    val localMs = rows.sumOf { it.totalMs }
                    val pendingMs = rows.sumOf { it.pendingMs }
                    val lastReadAt = maxOf(
                        insight.lastReadAt ?: 0L,
                        rows.maxOfOrNull { it.lastReadAt } ?: 0L,
                    )
                    if (lastReadAt <= 0L) return@forEach
                    if (serverMs <= 0L && rows.isEmpty()) return@forEach
                    urls.forEach { mergedBooks.remove(it) }
                    mergedBooks[canonical] = BookReadingStats(
                        bookUrl = canonical,
                        title = metadata.title,
                        author = metadata.author,
                        totalMs = maxOf(localMs, serverMs + pendingMs),
                        pendingMs = pendingMs,
                        lastReadAt = lastReadAt,
                        // The server has no started-at; the earliest local
                        // start across the work's URLs is the only source.
                        // Read from the all-time map first: a URL whose
                        // reading all predates the window has no row to
                        // carry its date.
                        firstReadAt = urls.mapNotNull { firstReadAtByUrl[it] }.minOrNull()
                            ?: rows.mapNotNull { it.firstReadAt }.minOrNull(),
                        progression = metadata.progression,
                        finished = metadata.finished,
                        sessions = maxOf(
                            rows.sumOf { it.sessions },
                            insight.sessions + rows.sumOf { it.pendingSessions },
                        ),
                        pendingSessions = rows.sumOf { it.pendingSessions },
                        coverPath = metadata.coverPath,
                        coverUrl = metadata.coverUrl,
                    )
                }
            val books = mergedBooks.values.sortedWith(
                compareByDescending<BookReadingStats> { it.totalMs }.thenBy { it.title },
            )
            val recent = if (serverRecent == null) {
                local.recent
            } else {
                val minutesByDate = serverRecent.associateBy({ it.date }, { it.activeMinutes })
                val localByDate = local.recent.associateBy { it.date }
                // A day this device has nothing for is not an empty day.
                // A reader who did a year on a laptop and installed the
                // app yesterday has a whole year the server can describe
                // and this device cannot, and a series built only from
                // local days would draw it blank.
                val dates = (localByDate.keys + minutesByDate.keys).sorted()
                dates.map { date ->
                    val day = localByDate[date] ?: ReadingDay(date, 0)
                    // A day the server has not heard about yet is a day
                    // this device read on and has not uploaded, not an
                    // empty one.
                    day.copy(
                        totalMs = maxOf(
                            day.totalMs,
                            minutesByDate[date].orZero().minutesAsMillis() + day.pendingMs,
                        ),
                    )
                }
            }
            return local.copy(
                totalMs = books.sumOf { it.totalMs },
                pendingMs = books.sumOf { it.pendingMs },
                pendingSessions = books.sumOf { it.pendingSessions },
                booksRead = books.size,
                booksFinished = books.count { it.finished },
                books = books,
                recent = recent,
            )
        }

        private fun Double?.orZero(): Double = this ?: 0.0

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                ReadingStatsViewModel(
                    sessionDao = container.database.readingSessionDao(),
                    bookDao = container.database.bookDao(),
                    progressDao = container.database.readingProgressDao(),
                    insights = container.syncInsights,
                    settings = container.appSettings,
                )
            }
        }
    }
}

/**
 * Minutes as milliseconds, for figures a server reports in minutes.
 *
 * Rounded rather than truncated, and used for every such figure, so that
 * a headline and the rows under it cannot differ by the seconds one of
 * them threw away.
 */
internal fun Double.minutesAsMillis(): Long = (this * 60_000).roundToLong()
