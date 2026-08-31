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
import com.chmouel.liseur.domain.ComparisonDirection
import com.chmouel.liseur.domain.ComparisonPeriod
import com.chmouel.liseur.domain.ComparisonSpans
import com.chmouel.liseur.domain.DateSpan
import com.chmouel.liseur.domain.ReadingDay
import com.chmouel.liseur.domain.ReadingStats
import com.chmouel.liseur.domain.SpanTotals
import com.chmouel.liseur.domain.StatsBook
import com.chmouel.liseur.domain.StatsRange
import java.time.DayOfWeek
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
import org.junit.Assert.assertNull
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
    private val today = LocalDate.of(2026, 8, 9)

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

            val week = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready
            assertEquals(0L, week.headline.totalMs)

            model.selectRange(StatsRange.ALL_TIME)
            val everything = model.state.first {
                it is ReadingStatsUiState.Ready && it.range == StatsRange.ALL_TIME
            } as ReadingStatsUiState.Ready

            assertEquals(60_000L, everything.headline.totalMs)
            // All time has no period before it to be measured against.
            assertNull(everything.headline.comparison)
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
            model.selectRange(StatsRange.THIS_WEEK)

            val week = model.state.first {
                it is ReadingStatsUiState.Ready && it.range == StatsRange.THIS_WEEK
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
    fun `changing language moves the week without changing the reading`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // today is a Sunday. A Monday-first reader counts it as the
            // last day of this week; a Sunday-first reader as the first,
            // so the Saturday before belongs to one week and not the
            // other.
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 60_000))
            db.readingSessionDao().insert(session(url, today.minusDays(1), 60_000))
            val model = model()
            model.selectRange(StatsRange.THIS_WEEK)

            val mondayFirst = model.state.first {
                it is ReadingStatsUiState.Ready && it.range == StatsRange.THIS_WEEK
            } as ReadingStatsUiState.Ready
            assertEquals(120_000L, mondayFirst.headline.totalMs)
            assertEquals(7, mondayFirst.stats.recent.size)

            model.setWeekStart(DayOfWeek.SUNDAY)

            // Nothing about the sessions changed, only where the week
            // begins. The sums, the caption and the chart must all move
            // together, or the screen describes two different weeks.
            val sundayFirst = model.state.first {
                it is ReadingStatsUiState.Ready && it.stats.recent.size == 1
            } as ReadingStatsUiState.Ready
            assertEquals(60_000L, sundayFirst.headline.totalMs)
            assertEquals(listOf(today), sundayFirst.stats.recent.map { it.date })
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
        )

        // 500 minutes from the server, not 501 from adding the local one.
        assertEquals(TimeUnit.MINUTES.toMillis(500), headline.totalMs)
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
        )
        assertEquals(90_000L, headline.totalMs)
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
            ReadingStatsViewModel.mergeHeadline(merged, local, server).sessions,
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
                    totalMs = 40 * 60_000L, lastReadAt = 100, firstReadAt = 10,
                    progression = 0.5, finished = false, sessions = 2,
                ),
                BookReadingStats(
                    bookUrl = "new", title = "A book", author = null,
                    totalMs = 10 * 60_000L, lastReadAt = 400, firstReadAt = 300,
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
            // The old URL was begun before the window the rows describe;
            // only the all-time map remembers it.
            firstReadAtByUrl = mapOf("old" to 5L, "new" to 300L),
        )

        assertEquals(1, merged.books.size)
        assertEquals("old", merged.books.single().bookUrl)
        assertEquals(50 * 60_000L, merged.books.single().totalMs)
        // The book was begun when its earliest URL was first opened —
        // moving the file does not restart its history, and a start
        // older than the window is not forgotten by looking at a week.
        assertEquals(5L, merged.books.single().firstReadAt)
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

    // ---- The period before this one -------------------------------

    /**
     * A week is read against the same weekdays of the week before.
     *
     * `today` is a Sunday and the reader's week begins on a Monday, so
     * this week is the seven days ending today and the one before it is
     * the seven ending last Sunday. Twice as much reading this week is
     * a hundred per cent more, not two hundred.
     */
    @Test
    fun `a period is measured against the same days of the one before`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 120_000))
            db.readingSessionDao().insert(session(url, today.minusDays(7), 60_000))
            val model = model()

            val loaded = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready

            val comparison = loaded.headline.comparison!!
            assertEquals(ComparisonPeriod.WEEK, comparison.period)
            assertEquals(ComparisonDirection.MORE, comparison.direction)
            assertEquals(100, comparison.percent)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * The baseline stops where the current period began.
     *
     * Reading done the day before this week started belongs to last
     * week; reading done the day before *that* week started belongs to
     * neither, and must not be swept into the baseline by a span that
     * merely reaches back far enough.
     */
    @Test
    fun `the period before is a period, not everything earlier`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 60_000))
            db.readingSessionDao().insert(session(url, today.minusDays(7), 60_000))
            // A fortnight ago: older than the baseline's first day.
            db.readingSessionDao().insert(session(url, today.minusDays(14), 600_000))
            val model = model()

            val loaded = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready

            assertEquals(ComparisonDirection.SAME, loaded.headline.comparison!!.direction)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * A period with nothing before it says so without a figure.
     *
     * Dividing by an empty baseline is an infinity, and "∞% more than
     * last week" is not a sentence. The wording drops the number.
     */
    @Test
    fun `a first week of reading is more, without a percentage`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 60_000))
            val model = model()

            val loaded = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready

            val comparison = loaded.headline.comparison!!
            assertEquals(ComparisonDirection.MORE, comparison.direction)
            assertNull(comparison.percent)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * Having read nothing yet is a hundred per cent less.
     *
     * The screen never draws it: `ReadingStatsScreen` returns its empty
     * state before the hero whenever the span holds no reading, so this
     * value is computed and then not reached. It is pinned here because
     * the arithmetic must still be right — the early return is control
     * flow, and control flow moves.
     */
    @Test
    fun `a period with no reading in it is all of the last one lost`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today.minusDays(7), 60_000))
            val model = model()

            val loaded = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready

            val comparison = loaded.headline.comparison!!
            assertEquals(ComparisonDirection.LESS, comparison.direction)
            assertEquals(100, comparison.percent)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a month is measured against the month before, not against the week`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            db.readingSessionDao().insert(session(url, today, 60_000))
            db.readingSessionDao().insert(session(url, LocalDate.of(2026, 7, 5), 60_000))
            val model = model()
            model.selectRange(StatsRange.THIS_MONTH)

            val loaded = model.state.first {
                it is ReadingStatsUiState.Ready && it.range == StatsRange.THIS_MONTH
            } as ReadingStatsUiState.Ready

            assertEquals(ComparisonPeriod.MONTH, loaded.headline.comparison!!.period)
            assertEquals(ComparisonDirection.SAME, loaded.headline.comparison!!.direction)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    // ---- The window every answer is about ---------------------------

    /**
     * A day change moves the screen even with no server to ask.
     *
     * Nothing here watches the clock, so a visit to the statistics
     * screen is when it learns what day it is. That happens before the
     * check for a server, or the one reader whose figures are entirely
     * local would be the one left looking at yesterday's week.
     */
    @Test
    fun `a day change moves the window with no server to ask`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            // `today` is a Sunday and the reader's week begins on a
            // Monday, so both of these are in this week. Tomorrow they
            // are both in last week and this one is a day old.
            db.readingSessionDao().insert(session(url, today, 60_000))
            db.readingSessionDao().insert(session(url, today.minusDays(6), 60_000))
            var day = today
            val model = model(today = { day })

            val sunday = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready
            assertEquals(120_000L, sunday.headline.totalMs)

            day = today.plusDays(1)
            model.refreshServerInsights()

            val monday = model.state.first {
                it is ReadingStatsUiState.Ready && it.headline.totalMs == 0L
            } as ReadingStatsUiState.Ready
            // A Monday compares with the Monday before, which is the
            // one this reader read on.
            assertEquals(ComparisonDirection.LESS, monday.headline.comparison!!.direction)
            assertEquals(100, monday.headline.comparison!!.percent)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * A new zone recounts the reading even when the date is unchanged.
     *
     * A sitting half an hour after midnight in Paris happened the
     * evening before in London, and a reader whose week begins on a
     * Sunday has just watched it leave the week. The date did not move,
     * so the zone has to be part of what the screen is asking about, or
     * the sums stay where they were.
     */
    @Test
    fun `a new zone with the same date still recounts the reading`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val url = "calibre:one"
            db.bookDao().upsert(book(url))
            // 00:30 on the Sunday in Paris is 22:30 on the Saturday in
            // London.
            val at = today.atTime(LocalTime.of(0, 30)).atZone(zone).toInstant().toEpochMilli()
            db.readingSessionDao().insert(
                ReadingSession(
                    bookUrl = url,
                    startedAt = at,
                    endedAt = at + 60_000,
                    lastCheckpointAt = at + 60_000,
                    durationMs = 60_000,
                ),
            )
            var here = zone
            val model = model(zone = { here }, weekStart = DayOfWeek.SUNDAY)

            val paris = model.state.first { it is ReadingStatsUiState.Ready }
                as ReadingStatsUiState.Ready
            assertEquals(60_000L, paris.headline.totalMs)

            here = ZoneId.of("Europe/London")
            model.refreshServerInsights()

            val london = model.state.first {
                it is ReadingStatsUiState.Ready && it.headline.totalMs == 0L
            }
            assertTrue(london is ReadingStatsUiState.Ready)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    // ---- The baseline the server answers for ------------------------

    /**
     * The baseline is merged the same way the headline above it is.
     *
     * The server counts every device but not the last twenty minutes,
     * which are still queued to upload. Both periods are offered that
     * same allowance, or a second device appears in one of them and
     * vanishes from the other, and the percentage is a comparison of
     * two different populations.
     */
    @Test
    fun `the baseline counts other devices and this one's unsent time alike`() {
        val local = localStats(totalMs = TimeUnit.MINUTES.toMillis(60))
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = InsightsSummary(activeMinutes = 60.0, sessions = 2, streakDays = 1),
            spans = spans,
            previousLocal = SpanTotals(
                totalMs = TimeUnit.MINUTES.toMillis(20),
                pendingMs = TimeUnit.MINUTES.toMillis(20),
            ),
            previousServer = InsightsSummary(activeMinutes = 100.0, sessions = 4, streakDays = 2),
        )

        // A hundred server minutes plus the twenty this device has not
        // sent: a hundred and twenty against this period's sixty.
        assertEquals(ComparisonDirection.LESS, headline.comparison!!.direction)
        assertEquals(50, headline.comparison!!.percent)
    }

    /** A baseline the server could not answer falls back to this device. */
    @Test
    fun `a baseline the server declined falls back to this device`() {
        val local = localStats(totalMs = TimeUnit.MINUTES.toMillis(60))
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = null,
            spans = spans,
            previousLocal = SpanTotals(totalMs = TimeUnit.MINUTES.toMillis(30), pendingMs = 0),
            previousServer = null,
        )

        assertEquals(ComparisonDirection.MORE, headline.comparison!!.direction)
        assertEquals(100, headline.comparison!!.percent)
    }

    /**
     * A server that answers nought is answering, not failing.
     *
     * Both cases arrive as the same value, and they mean the same thing
     * here: nothing to divide by, so the sentence drops its figure.
     */
    @Test
    fun `a server baseline of nothing reads as no baseline at all`() {
        val local = localStats(totalMs = TimeUnit.MINUTES.toMillis(60))
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = null,
            spans = spans,
            previousLocal = SpanTotals.Empty,
            previousServer = InsightsSummary(activeMinutes = 0.0, sessions = 0, streakDays = 0),
        )

        assertEquals(ComparisonDirection.MORE, headline.comparison!!.direction)
        assertNull(headline.comparison!!.percent)
    }

    /** With no spans there is no comparison, whatever the server said. */
    @Test
    fun `a span with nothing before it keeps no comparison`() {
        val local = localStats(totalMs = TimeUnit.MINUTES.toMillis(60))
        val headline = ReadingStatsViewModel.mergeHeadline(
            merged = local,
            local = local,
            server = null,
            spans = null,
            previousLocal = SpanTotals(totalMs = TimeUnit.MINUTES.toMillis(30), pendingMs = 0),
            previousServer = InsightsSummary(activeMinutes = 30.0, sessions = 1, streakDays = 1),
        )

        assertNull(headline.comparison)
    }

    private val spans = ComparisonSpans(
        period = ComparisonPeriod.WEEK,
        current = DateSpan(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9)),
        previous = DateSpan(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2)),
    )

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

    private fun model(
        zone: () -> ZoneId = { this.zone },
        today: () -> LocalDate = { this.today },
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
    ): ReadingStatsViewModel {
        val factory = viewModelFactory {
            initializer {
                ReadingStatsViewModel(
                    sessionDao = db.readingSessionDao(),
                    bookDao = db.bookDao(),
                    progressDao = db.readingProgressDao(),
                    zone = zone,
                    today = { today() },
                    // Pinned rather than read off the JVM's locale: the
                    // week's first day is what decides how far the
                    // default span reaches, and a test that reaches a
                    // different distance on a different machine is not
                    // testing anything.
                    initialWeekStart = weekStart,
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
