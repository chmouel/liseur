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
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.RECENT_DAYS
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.readingStats
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ReadingStatsUiState {
    data object Loading : ReadingStatsUiState
    data class Ready(val stats: ReadingStats, val headline: StatsHeadline) : ReadingStatsUiState
}

/**
 * The one set of figures the screen leads with, merged across devices.
 *
 * The server's count is the same reading seen on every device, so where
 * it answers it is the superset and it wins; this device's own sessions
 * are part of it, so summing the two would count them twice. When the
 * server has nothing — offline, or no statistics token — the local
 * lifetime figures stand in, and the range is left null so the caption
 * can say "in total" instead of pretending a window.
 */
data class StatsHeadline(
    val totalMs: Long,
    /** Days the total covers, or null when it is lifetime-local. */
    val rangeDays: Int?,
    val sessions: Int?,
    val streakDays: Int?,
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
 * per-book aggregates replace the matching local slices because they already
 * contain this device's uploads as well as every other device's.
 */
class ReadingStatsViewModel(
    sessionDao: ReadingSessionDao,
    bookDao: BookDao,
    progressDao: ReadingProgressDao,
    private val insights: LiseurSyncInsights? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) : ViewModel() {

    private val _acrossDevices = MutableStateFlow<InsightsSummary?>(null)
    private val _recentAcrossDevices = MutableStateFlow<List<InsightDay>?>(null)
    private val _booksAcrossDevices = MutableStateFlow<Map<String, WorkInsights>?>(null)

    /**
     * The same reading, counted on every device rather than this one.
     *
     * Folded into the headline rather than shown beside it: the reader
     * does not care which machine did the reading, and two figures that
     * answer the same question differently are a doubt, not a feature.
     */
    private val acrossDevices: StateFlow<InsightsSummary?> = _acrossDevices.asStateFlow()
    private val recentAcrossDevices = _recentAcrossDevices.asStateFlow()
    private val booksAcrossDevices = _booksAcrossDevices.asStateFlow()

    /** Refreshes the server decoration whenever a statistics screen opens. */
    fun refreshServerInsights() {
        val source = insights ?: return
        viewModelScope.launch { _acrossDevices.value = source.summary() }
        viewModelScope.launch {
            val end = today()
            _recentAcrossDevices.value = source.calendar(
                from = end.minusDays((RECENT_DAYS - 1).toLong()),
                to = end,
            )
        }
        viewModelScope.launch { _booksAcrossDevices.value = source.allBooks() }
    }

    /**
     * How much longer this book has, according to every device.
     *
     * This is the same aggregate used by the dashboard row, so its total
     * and estimate are fetched together and cannot describe different
     * snapshots of the server.
     */
    fun serverEstimateFor(bookUrl: String): StateFlow<WorkInsights?> =
        booksAcrossDevices.map { it?.get(bookUrl) }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    private data class LocalStats(
        val stats: ReadingStats,
        val books: Map<String, StatsBook>,
    )

    private val local = combine(
        sessionDao.observeAll(),
        bookDao.observeAll(),
        progressDao.observeProgressions(),
    ) { sessions, books, progressions ->
        val progressionByUrl = progressions.associateBy({ it.bookUrl }, { it.totalProgression })
        val statsBooks = books.associate { book ->
            book.url to StatsBook(
                bookUrl = book.url,
                title = book.displayTitle,
                author = book.displayAuthor,
                progression = progressionByUrl[book.url],
                finished = book.finished,
            )
        }
        LocalStats(
            stats = readingStats(
                sessions = sessions.map {
                    SessionSpan(
                        bookUrl = it.bookUrl,
                        startedAt = it.startedAt,
                        durationMs = it.durationMs,
                        lastReadAt = it.lastCheckpointAt,
                    )
                },
                books = statsBooks,
                zone = zone(),
                today = today(),
            ),
            books = statsBooks,
        )
    }

    val state: StateFlow<ReadingStatsUiState> = combine(
        local,
        acrossDevices,
        recentAcrossDevices,
        booksAcrossDevices,
    ) { local, server, recent, serverBooks ->
        ReadingStatsUiState.Ready(
            stats = mergeDashboard(local.stats, local.books, recent, serverBooks),
            headline = mergeHeadline(local.stats, server),
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
         * The one headline, from both sources.
         *
         * Prefer the server, fall back to local, never sum. The server's
         * count already includes this device's sessions, so adding the
         * two would count this device twice; and a null range is the
         * honest caption for a lifetime total.
         */
        internal fun mergeHeadline(
            local: ReadingStats,
            server: InsightsSummary?,
        ): StatsHeadline = if (server != null) {
            StatsHeadline(
                totalMs = TimeUnit.MINUTES.toMillis(server.activeMinutes.toLong()),
                rangeDays = server.rangeDays,
                sessions = server.sessions,
                streakDays = server.streakDays,
            )
        } else {
            StatsHeadline(
                totalMs = local.totalMs,
                rangeDays = null,
                sessions = null,
                streakDays = null,
            )
        }

        /**
         * Prefer server aggregates only where the server supplied them.
         * Local rows remain the fallback for an offline server, an unshared
         * book, and old sessions that could not name their progression.
         */
        internal fun mergeDashboard(
            local: ReadingStats,
            knownBooks: Map<String, StatsBook>,
            serverRecent: List<InsightDay>?,
            serverBooks: Map<String, WorkInsights>?,
        ): ReadingStats {
            val mergedBooks = local.books.associateBy { it.bookUrl }.toMutableMap()
            serverBooks?.forEach { (bookUrl, insight) ->
                val metadata = knownBooks[bookUrl] ?: return@forEach
                val localBook = mergedBooks[bookUrl]
                val lastReadAt = insight.lastReadAt ?: localBook?.lastReadAt ?: return@forEach
                if (insight.activeMinutes <= 0 && localBook == null) return@forEach
                mergedBooks[bookUrl] = BookReadingStats(
                    bookUrl = bookUrl,
                    title = metadata.title,
                    author = metadata.author,
                    totalMs = (insight.activeMinutes * 60_000).roundToLong(),
                    lastReadAt = lastReadAt,
                    progression = metadata.progression,
                    finished = metadata.finished,
                )
            }
            val books = mergedBooks.values.sortedWith(
                compareByDescending<BookReadingStats> { it.totalMs }.thenBy { it.title },
            )
            val recent = if (serverRecent == null) {
                local.recent
            } else {
                val minutesByDate = serverRecent.associateBy({ it.date }, { it.activeMinutes })
                local.recent.map { day ->
                    day.copy(
                        totalMs = (minutesByDate[day.date].orZero() * 60_000).roundToLong(),
                    )
                }
            }
            return ReadingStats(
                totalMs = books.sumOf { it.totalMs },
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
                )
            }
        }
    }
}
