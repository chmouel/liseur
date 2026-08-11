package com.chmouel.liseur.ui.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.data.liseursync.InsightDay
import com.chmouel.liseur.data.liseursync.InsightsSummary
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.StatsBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingStatsViewModelTest {

    private lateinit var db: LiseurDatabase
    private lateinit var models: ViewModelStore

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        models = ViewModelStore()
    }

    @After
    fun close() {
        models.clear()
        db.close()
    }

    @Test
    fun `loading is distinct from a loaded empty dashboard`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val model = model()

            assertEquals(ReadingStatsUiState.Loading, model.state.value)
            val loaded = model.state.first { it is ReadingStatsUiState.Ready }

            assertTrue((loaded as ReadingStatsUiState.Ready).stats.isEmpty)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `with no server the headline is the local lifetime`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = url,
                    startedAt = 0,
                    endedAt = 60_000,
                    lastCheckpointAt = 60_000,
                    durationMs = 60_000,
                ),
            )
            val model = model()

            val loaded = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready

            assertEquals(60_000L, loaded.headline.totalMs)
            // Local-only, so there is no range to caption it with.
            assertEquals(null, loaded.headline.rangeDays)
            assertEquals(null, loaded.headline.streakDays)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the server's count wins over the local one and is never summed`() {
        val local = localStats(totalMs = 60_000)
        val server = InsightsSummary(
            rangeDays = 30,
            activeMinutes = 500.0,
            sessions = 12,
            streakDays = 4,
        )
        val headline = ReadingStatsViewModel.mergeHeadline(local, server)

        // 500 minutes from the server, not 501 from adding the local one.
        assertEquals(TimeUnit.MINUTES.toMillis(500), headline.totalMs)
        assertEquals(30, headline.rangeDays)
        assertEquals(4, headline.streakDays)
        assertEquals(12, headline.sessions)
    }

    @Test
    fun `with no server the merge keeps the local total and no range`() {
        val headline = ReadingStatsViewModel.mergeHeadline(localStats(totalMs = 90_000), null)
        assertEquals(90_000L, headline.totalMs)
        assertEquals(null, headline.rangeDays)
        assertEquals(null, headline.streakDays)
    }

    @Test
    fun `server calendar and book totals replace their local device slices`() {
        val monday = LocalDate.of(2026, 8, 10)
        val tuesday = monday.plusDays(1)
        val local = ReadingStats(
            totalMs = 80 * 60_000L,
            booksRead = 1,
            booksFinished = 0,
            books = listOf(
                BookReadingStats(
                    bookUrl = "book",
                    title = "A book",
                    author = "An author",
                    totalMs = 80 * 60_000L,
                    lastReadAt = 100,
                    progression = 0.5,
                    finished = false,
                ),
            ),
            recent = listOf(ReadingDay(monday, 0), ReadingDay(tuesday, 80 * 60_000L)),
        )
        val known = mapOf(
            "book" to StatsBook("book", "A book", "An author", 0.5, finished = false),
        )

        val merged = ReadingStatsViewModel.mergeDashboard(
            local = local,
            knownBooks = known,
            serverRecent = listOf(InsightDay(monday, 23.5), InsightDay(tuesday, 137.0)),
            serverBooks = mapOf(
                "book" to WorkInsights(
                    sessions = 98,
                    activeMinutes = 160.5,
                    etaSeconds = null,
                    lastReadAt = 200,
                ),
            ),
        )

        assertEquals((23.5 * 60_000).toLong(), merged.recent[0].totalMs)
        assertEquals(137 * 60_000L, merged.recent[1].totalMs)
        assertEquals((160.5 * 60_000).toLong(), merged.books.single().totalMs)
        assertEquals(200, merged.books.single().lastReadAt)
    }

    private fun localStats(totalMs: Long) = ReadingStats(
        totalMs = totalMs,
        booksRead = 1,
        booksFinished = 0,
        books = emptyList(),
        recent = emptyList(),
    )

    @Test
    fun `book loading becomes empty or ready only after the dashboard loads`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = url,
                    startedAt = 0,
                    endedAt = 60_000,
                    lastCheckpointAt = 60_000,
                    durationMs = 60_000,
                ),
            )
            val model = model()
            val known = model.forBook(url)
            val missing = model.forBook("missing")

            assertEquals(BookReadingStatsUiState.Loading, known.value)
            assertTrue(
                known.first { it !is BookReadingStatsUiState.Loading } is
                    BookReadingStatsUiState.Ready,
            )
            assertEquals(
                BookReadingStatsUiState.Empty,
                missing.first { it !is BookReadingStatsUiState.Loading },
            )
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    private fun model(): ReadingStatsViewModel {
        val factory = viewModelFactory {
            initializer {
                ReadingStatsViewModel(
                    sessionDao = db.readingSessionDao(),
                    bookDao = db.bookDao(),
                    progressDao = db.readingProgressDao(),
                    zone = { ZoneId.of("Europe/Paris") },
                    today = { LocalDate.of(2026, 8, 10) },
                )
            }
        }
        return ViewModelProvider(models, factory)[ReadingStatsViewModel::class.java]
    }

    private fun book(url: String) = Book(
        url = url,
        title = "A book",
        author = "An author",
        coverPath = null,
        source = null,
        addedAt = 0,
        lastOpenedAt = null,
    )
}
