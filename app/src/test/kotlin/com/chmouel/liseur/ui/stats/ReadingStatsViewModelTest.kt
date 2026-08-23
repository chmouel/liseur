package com.chmouel.liseur.ui.stats

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.chmouel.liseur.domain.StatsRange
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
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

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingStatsViewModelTest {

    private lateinit var db: LiseurDatabase
    private lateinit var models: ViewModelStore

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 8, 10)

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
    fun `with no server the headline is this device's own count for the span`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 60_000))
            val model = model()

            val loaded = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready

            assertEquals(60_000L, loaded.headline.totalMs)
            assertEquals(30, loaded.headline.rangeDays)
            // Sittings and the streak used to vanish without a server.
            // This device can count both perfectly well.
            assertEquals(1, loaded.headline.sessions)
            assertEquals(1, loaded.headline.streakDays)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a wider span reaches reading the default one cannot`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today.minusDays(200), 60_000))
            val model = model()

            val thirty = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready
            assertEquals(0L, thirty.headline.totalMs)

            model.selectRange(StatsRange.ALL_TIME)
            val everything = model.state.first {
                it is ReadingStatsUiState.Ready && it.range == StatsRange.ALL_TIME
            } as ReadingStatsUiState.Ready

            assertEquals(60_000L, everything.headline.totalMs)
            // All time has no window to name, so the caption says so.
            assertEquals(null, everything.headline.rangeDays)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the streak survives a span too narrow to contain it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            repeat(20) { back ->
                db.readingSessionDao().insert(session(url, today.minusDays(back.toLong()), 60_000))
            }
            val model = model()
            model.selectRange(StatsRange.LAST_7_DAYS)

            val week = model.state.first {
                it is ReadingStatsUiState.Ready && it.range == StatsRange.LAST_7_DAYS
            } as ReadingStatsUiState.Ready

            // Seven days of reading counted, but twenty days of streak:
            // a streak is a fact about the reader, not about the window.
            assertEquals(7, week.headline.sessions)
            assertEquals(20, week.headline.streakDays)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the server's count wins over the local one and is never summed`() {
        val local = localStats(totalMs = 60_000)
        val server = InsightsSummary(
            activeMinutes = 500.0,
            sessions = 12,
            streakDays = 4,
        )
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = server,
            range = StatsRange.LAST_30_DAYS,
            today = today,
        )

        // 500 minutes from the server, not 501 from adding the local one.
        assertEquals(TimeUnit.MINUTES.toMillis(500), headline.totalMs)
        assertEquals(30, headline.rangeDays)
        assertEquals(4, headline.streakDays)
        assertEquals(12, headline.sessions)
    }

    @Test
    fun `a server behind on uploads cannot shrink what this device counted`() {
        // The reader read for an hour ten minutes ago; the upload worker
        // has not run yet, so the server has only forty minutes of it.
        val local = localStats(totalMs = TimeUnit.MINUTES.toMillis(60)).copy(
            sessions = 3,
            streakDays = 9,
        )
        val server = InsightsSummary(
            activeMinutes = 40.0,
            sessions = 2,
            streakDays = 8,
        )

        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = server,
            range = StatsRange.LAST_30_DAYS,
            today = today,
        )

        assertEquals(TimeUnit.MINUTES.toMillis(60), headline.totalMs)
        assertEquals(3, headline.sessions)
        assertEquals(9, headline.streakDays)
    }

    @Test
    fun `the headline is never smaller than the rows beneath it`() {
        // A book read only on another device cannot appear as a row, so
        // the server's total legitimately exceeds their sum.
        val merged = localStats(totalMs = TimeUnit.MINUTES.toMillis(10))
        val server = InsightsSummary(
            activeMinutes = 90.0,
            sessions = 5,
            streakDays = 2,
        )

        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = merged,
            local = merged,
            server = server,
            range = StatsRange.LAST_30_DAYS,
            today = today,
        )

        assertTrue(headline.totalMs >= merged.totalMs)
        assertEquals(TimeUnit.MINUTES.toMillis(90), headline.totalMs)
    }

    @Test
    fun `with no server the merge keeps this device's own figures`() {
        val local = localStats(totalMs = 90_000).copy(
            sessions = 4,
            streakDays = 3,
            progressionPerHour = 0.2,
        )
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = null,
            range = StatsRange.LAST_30_DAYS,
            today = today,
        )
        assertEquals(90_000L, headline.totalMs)
        assertEquals(30, headline.rangeDays)
        assertEquals(4, headline.sessions)
        assertEquals(3, headline.streakDays)
        assertEquals(0.2, headline.progressionPerHour!!, 1e-9)
    }

    @Test
    fun `pace falls back to this device when the server reports none`() {
        val local = localStats(totalMs = 90_000).copy(progressionPerHour = 0.15)
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = InsightsSummary(
                activeMinutes = 2.0,
                sessions = 1,
                streakDays = 1,
                progressionPerHour = null,
            ),
            range = StatsRange.LAST_30_DAYS,
            today = today,
        )
        assertEquals(0.15, headline.progressionPerHour!!, 1e-9)
    }

    @Test
    fun `server calendar and book totals add the reading done elsewhere`() {
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
                    sessions = 3,
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
        assertEquals(98, merged.books.single().sessions)
    }

    /**
     * Half a book's time uploaded and half of it still queued is the
     * ordinary state of things, and the two totals then overlap without
     * either containing the other: the server has this device's morning
     * plus a laptop's evening, this device has its morning plus an hour
     * nobody has been told about. Taking the larger loses that hour. So
     * the queued time is offered to the server's figure, which is the
     * one thing certain not to already include it.
     */
    @Test
    fun `time not yet uploaded is added to what the server knows`() {
        val monday = LocalDate.of(2026, 8, 10)
        val local = ReadingStats(
            totalMs = 60 * 60_000L,
            pendingMs = 20 * 60_000L,
            booksRead = 1,
            booksFinished = 0,
            books = listOf(
                BookReadingStats(
                    bookUrl = "book",
                    title = "A book",
                    author = "An author",
                    totalMs = 60 * 60_000L,
                    pendingMs = 20 * 60_000L,
                    lastReadAt = 100,
                    progression = 0.5,
                    finished = false,
                    sessions = 3,
                ),
            ),
            recent = listOf(ReadingDay(monday, 60 * 60_000L, pendingMs = 20 * 60_000L)),
        )
        val known = mapOf(
            "book" to StatsBook("book", "A book", "An author", 0.5, finished = false),
        )

        val merged = ReadingStatsViewModel.mergeDashboard(
            local = local,
            knownBooks = known,
            serverRecent = listOf(InsightDay(monday, 90.0)),
            serverBooks = mapOf(
                "book" to WorkInsights(
                    workId = "w-1",
                    sessions = 5,
                    activeMinutes = 90.0,
                    etaSeconds = null,
                    lastReadAt = 200,
                ),
            ),
        )

        // 40 uploaded + 50 elsewhere on the server, 20 still queued here.
        assertEquals(110 * 60_000L, merged.books.single().totalMs)
        assertEquals(110 * 60_000L, merged.recent.single().totalMs)
        assertEquals(20 * 60_000L, merged.books.single().pendingMs)
    }

    /**
     * Sittings are counted the same way the minutes are, and for the
     * same reason: five uploaded here, five more on a laptop and one
     * still queued is eleven, and the larger of six and ten is not.
     */
    @Test
    fun `sittings not yet uploaded are added to what the server counted`() {
        val local = ReadingStats(
            totalMs = 60 * 60_000L,
            pendingMs = 10 * 60_000L,
            booksRead = 1,
            booksFinished = 0,
            sessions = 6,
            pendingSessions = 1,
            books = listOf(
                BookReadingStats(
                    bookUrl = "book",
                    title = "A book",
                    author = "An author",
                    totalMs = 60 * 60_000L,
                    pendingMs = 10 * 60_000L,
                    lastReadAt = 100,
                    progression = 0.5,
                    finished = false,
                    sessions = 6,
                    pendingSessions = 1,
                ),
            ),
            recent = emptyList(),
        )
        val known = mapOf(
            "book" to StatsBook("book", "A book", "An author", 0.5, finished = false),
        )
        val server = InsightsSummary(
            activeMinutes = 100.0,
            sessions = 10,
            streakDays = 0,
            progressionPerHour = null,
        )

        val merged = ReadingStatsViewModel.mergeDashboard(
            local = local,
            knownBooks = known,
            serverRecent = null,
            serverBooks = mapOf(
                "book" to WorkInsights(
                    workId = "w-1",
                    sessions = 10,
                    activeMinutes = 100.0,
                    etaSeconds = null,
                    lastReadAt = 200,
                ),
            ),
        )

        assertEquals(11, merged.books.single().sessions)
        assertEquals(
            11,
            ReadingStatsViewModel.mergeHeadline(
                merged, local, server, StatsRange.LAST_30_DAYS, today,
            ).sessions,
        )
    }

    /**
     * A reader who did a year on another device and installed this one
     * yesterday has a year the server can describe and this device
     * cannot. A series built only from the days this device knows about
     * would draw all of it blank.
     */
    @Test
    fun `days only the server knows about still appear`() {
        val monday = LocalDate.of(2026, 8, 10)
        val local = ReadingStats(
            totalMs = 0,
            booksRead = 0,
            booksFinished = 0,
            books = emptyList(),
            recent = emptyList(),
        )

        val merged = ReadingStatsViewModel.mergeDashboard(
            local = local,
            knownBooks = emptyMap(),
            serverRecent = listOf(
                InsightDay(monday.minusDays(1), 30.0),
                InsightDay(monday, 45.0),
            ),
            serverBooks = emptyMap(),
        )

        assertEquals(2, merged.recent.size)
        assertEquals(monday.minusDays(1), merged.recent.first().date)
        assertEquals(45 * 60_000L, merged.recent.last().totalMs)
    }

    /**
     * The upload worker runs on its own schedule, so this device is
     * routinely ahead of the server by an evening. A merge that trusted
     * the server outright would take that evening off the screen while
     * the reader was looking at it.
     */
    @Test
    fun `a server behind on uploads cannot empty a book row or a bar`() {
        val monday = LocalDate.of(2026, 8, 10)
        val tuesday = monday.plusDays(1)
        val local = ReadingStats(
            totalMs = 90 * 60_000L,
            booksRead = 1,
            booksFinished = 0,
            books = listOf(
                BookReadingStats(
                    bookUrl = "book",
                    title = "A book",
                    author = null,
                    totalMs = 90 * 60_000L,
                    lastReadAt = 500,
                    progression = 0.5,
                    finished = false,
                    sessions = 4,
                ),
            ),
            recent = listOf(ReadingDay(monday, 30 * 60_000L), ReadingDay(tuesday, 60 * 60_000L)),
        )
        val known = mapOf("book" to StatsBook("book", "A book", null, 0.5, finished = false))

        val merged = ReadingStatsViewModel.mergeDashboard(
            local = local,
            knownBooks = known,
            // The server has heard about Monday and nothing since.
            serverRecent = listOf(InsightDay(monday, 30.0)),
            serverBooks = mapOf(
                "book" to WorkInsights(
                    sessions = 2,
                    activeMinutes = 30.0,
                    etaSeconds = null,
                    lastReadAt = 100,
                    workId = "w-1",
                ),
            ),
        )

        assertEquals(30 * 60_000L, merged.recent[0].totalMs)
        assertEquals(60 * 60_000L, merged.recent[1].totalMs)
        assertEquals(90 * 60_000L, merged.books.single().totalMs)
        assertEquals(4, merged.books.single().sessions)
        assertEquals(500, merged.books.single().lastReadAt)
        assertEquals(90 * 60_000L, merged.totalMs)
    }

    /**
     * A file that moved has two URLs on this device and one work on the
     * server, which counts the reading once. Two rows would charge the
     * reader twice for the same evening and show one book as two.
     */
    @Test
    fun `one work with two local urls is one row counted once`() {
        val local = ReadingStats(
            totalMs = 50 * 60_000L,
            booksRead = 2,
            booksFinished = 0,
            books = listOf(
                BookReadingStats(
                    bookUrl = "old", title = "A book", author = null,
                    totalMs = 40 * 60_000L, lastReadAt = 100,
                    progression = 0.5, finished = false, sessions = 2,
                ),
                BookReadingStats(
                    bookUrl = "new", title = "A book", author = null,
                    totalMs = 10 * 60_000L, lastReadAt = 400,
                    progression = 0.5, finished = false, sessions = 1,
                ),
            ),
            recent = emptyList(),
        )
        val known = mapOf(
            "old" to StatsBook("old", "A book", null, 0.5, finished = false),
            "new" to StatsBook("new", "A book", null, 0.5, finished = false),
        )
        val one = WorkInsights(
            sessions = 3,
            activeMinutes = 50.0,
            etaSeconds = null,
            lastReadAt = 400,
            workId = "w-1",
        )

        val merged = ReadingStatsViewModel.mergeDashboard(
            local = local,
            knownBooks = known,
            serverRecent = null,
            serverBooks = mapOf("old" to one, "new" to one),
        )

        assertEquals(1, merged.books.size)
        assertEquals("old", merged.books.single().bookUrl)
        assertEquals(50 * 60_000L, merged.books.single().totalMs)
        assertEquals(50 * 60_000L, merged.totalMs)
    }

    @Test
    fun `book loading becomes empty or ready only after the dashboard loads`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 60_000))
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

    private fun localStats(totalMs: Long) = ReadingStats(
        totalMs = totalMs,
        booksRead = 1,
        booksFinished = 0,
        books = emptyList(),
        recent = emptyList(),
    )

    private fun session(url: String, day: LocalDate, durationMs: Long): ReadingSession {
        val at = day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        return ReadingSession(
            bookUrl = url,
            startedAt = at,
            endedAt = at + durationMs,
            lastCheckpointAt = at + durationMs,
            durationMs = durationMs,
        )
    }

    private fun model(): ReadingStatsViewModel {
        val factory = viewModelFactory {
            initializer {
                ReadingStatsViewModel(
                    sessionDao = db.readingSessionDao(),
                    bookDao = db.bookDao(),
                    progressDao = db.readingProgressDao(),
                    zone = { zone },
                    today = { today },
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
