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
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.SessionSpan
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.readingStats
import com.chmouel.liseur.data.liseursync.InsightsSummary
import com.chmouel.liseur.data.liseursync.LiseurSyncInsights
import com.chmouel.liseur.data.liseursync.WorkInsights
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

sealed interface ReadingStatsUiState {
    data object Loading : ReadingStatsUiState
    data class Ready(val stats: ReadingStats) : ReadingStatsUiState
}

sealed interface BookReadingStatsUiState {
    data object Loading : BookReadingStatsUiState
    data object Empty : BookReadingStatsUiState
    data class Ready(val stats: BookReadingStats) : BookReadingStatsUiState
}

/**
 * What the reading dashboard shows.
 *
 * The sums are recomputed from the sessions each time rather than kept
 * as a running total anywhere: a total is a second copy of the truth,
 * and the two would drift the first time a book was deleted. There are
 * at most a few thousand rows and the arithmetic is addition.
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

    /**
     * The same reading, counted on every device rather than this one.
     *
     * Null until the server has answered, and null forever if it never
     * does. Nothing on this screen waits for it: the local figures are
     * complete in themselves and this appears beside them when it can.
     */
    val acrossDevices: StateFlow<InsightsSummary?> = _acrossDevices.asStateFlow()

    init {
        viewModelScope.launch { _acrossDevices.value = insights?.summary() }
    }

    /**
     * How much longer this book has, according to every device.
     *
     * A fresh request each time rather than a cached one: the number is
     * only interesting immediately after reading, which is exactly when
     * a cached copy would be stale.
     */
    fun serverEstimateFor(bookUrl: String): StateFlow<WorkInsights?> {
        val answer = MutableStateFlow<WorkInsights?>(null)
        viewModelScope.launch { answer.value = insights?.forBook(bookUrl) }
        return answer.asStateFlow()
    }

    val state: StateFlow<ReadingStatsUiState> = combine(
        sessionDao.observeAll(),
        bookDao.observeAll(),
        progressDao.observeProgressions(),
    ) { sessions, books, progressions ->
        val progressionByUrl = progressions.associateBy({ it.bookUrl }, { it.totalProgression })
        readingStats(
            sessions = sessions.map {
                SessionSpan(
                    bookUrl = it.bookUrl,
                    startedAt = it.startedAt,
                    durationMs = it.durationMs,
                    lastReadAt = it.lastCheckpointAt,
                )
            },
            books = books.associate { book ->
                book.url to StatsBook(
                    bookUrl = book.url,
                    title = book.displayTitle,
                    author = book.displayAuthor,
                    progression = progressionByUrl[book.url],
                    finished = book.finished,
                )
            },
            zone = zone(),
            today = today(),
        )
    }.map<ReadingStats, ReadingStatsUiState>(ReadingStatsUiState::Ready)
        .stateIn(
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
