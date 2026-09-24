package com.chmouel.liseur.ui.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingSession
import com.chmouel.liseur.ui.stats.DurationParts
import com.chmouel.liseur.ui.stats.durationParts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class WidgetRepositoryTest {

    private lateinit var db: LiseurDatabase
    private lateinit var context: Context
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 24) // Thursday
    private val weekStart = DayOfWeek.MONDAY

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty shelf has no book and zero week`() = runBlocking {
        val snapshot = repository().load(context, WidgetPeriod.WEEK)
        assertNull(snapshot.book)
        assertEquals(0L, snapshot.stats!!.figures.totalMs)
        assertEquals(0, snapshot.stats!!.figures.sessions)
        assertEquals(context.getString(com.chmouel.liseur.R.string.duration_none), snapshot.stats!!.totalLabel)
        assertNull(snapshot.stats!!.peakLabel)
    }

    @Test
    fun `a widget with no stored period shows today`() = runBlocking {
        assertEquals(WidgetPeriod.DAY, repository().load(context).stats!!.figures.period)
        assertEquals(WidgetPeriod.DAY, WidgetPeriod.fromId(null))
        assertEquals(WidgetPeriod.DAY, WidgetPeriod.fromId("fortnight"))
        assertEquals(WidgetPeriod.MONTH, WidgetPeriod.fromId("month"))
    }

    @Test
    fun `most recent book is the widget book`() = runBlocking {
        insertBook("file:///old.epub", "Old", openedAt = 1_000L)
        insertBook("file:///new.epub", "New Title", openedAt = 2_000L)
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = "file:///new.epub",
                locatorJson = "{}",
                totalProgression = 0.42,
                updatedAt = 2_000L,
                readAt = 2_000L,
            ),
        )

        val snapshot = repository().load(context)
        val book = checkNotNull(snapshot.book)
        assertEquals("file:///new.epub", book.url)
        assertEquals("New Title", book.title)
        assertEquals(0.42, book.progression!!, 0.001)
        assertEquals("NT", book.initials)
    }

    @Test
    fun `week stats count only this calendar week`() = runBlocking {
        insertBook("file:///a.epub", "A", openedAt = 1_000L)
        // Monday this week
        insertSession("file:///a.epub", today.minusDays(3), TimeUnit.HOURS.toMillis(1))
        // Previous Sunday — outside Mon–Thu week
        insertSession("file:///a.epub", today.minusDays(4), TimeUnit.HOURS.toMillis(2))

        val snapshot = repository().load(context, WidgetPeriod.WEEK)
        assertEquals(TimeUnit.HOURS.toMillis(1), snapshot.stats!!.figures.totalMs)
        assertEquals(1, snapshot.stats!!.figures.sessions)
        assertEquals(1, snapshot.stats!!.figures.booksRead)
        assertEquals(7, snapshot.stats!!.figures.bars.size)
        assertEquals("1h", snapshot.stats!!.peakLabel)
    }

    @Test
    fun `each widget loads only what it draws`() = runBlocking {
        db.bookDao().upsert(
            Book(
                url = "file:///a.epub",
                title = "A",
                author = "Author",
                coverPath = "/covers/a.jpg",
                source = null,
                addedAt = 1_000L,
                lastOpenedAt = 1_000L,
            ),
        )
        insertSession("file:///a.epub", today, TimeUnit.MINUTES.toMillis(5))
        val decoded = mutableListOf<String>()
        val repository = repository(decodeCover = { decoded += it; null })

        val cover = repository.load(context, content = WidgetContent.COVER)
        assertNull(cover.stats)
        assertEquals(listOf("/covers/a.jpg"), decoded)

        decoded.clear()
        val stats = repository.load(context, content = WidgetContent.STATS)
        assertEquals("file:///a.epub", stats.book?.url)
        assertEquals(TimeUnit.MINUTES.toMillis(5), stats.stats!!.figures.totalMs)
        assertTrue(decoded.isEmpty())

        val both = repository.load(context, content = WidgetContent.COVER_AND_STATS)
        assertEquals(TimeUnit.MINUTES.toMillis(5), both.stats!!.figures.totalMs)
        assertEquals(listOf("/covers/a.jpg"), decoded)
    }

    @Test
    fun `peak label uses the compact format`() {
        assertEquals("<1m", formatCompactDuration(context, 30_000L))
        assertEquals("45m", formatCompactDuration(context, TimeUnit.MINUTES.toMillis(45)))
        assertEquals("7h", formatCompactDuration(context, TimeUnit.HOURS.toMillis(7)))
        assertEquals("1h05", formatCompactDuration(context, TimeUnit.MINUTES.toMillis(65)))
    }

    @Test
    fun `every table a widget reads wakes the refresh`() = runBlocking {
        val seen = Channel<Set<String>>(Channel.UNLIMITED)
        val watching = launch { db.widgetInputs().collect { seen.send(it) } }
        try {
            withTimeout(5_000) { seen.receive() } // the initial state
            insertBook("file:///a.epub", "A", openedAt = 1_000L)
            assertTrue("books" in withTimeout(5_000) { seen.receive() })
            db.readingProgressDao().upsert(
                ReadingProgress(
                    bookUrl = "file:///a.epub",
                    locatorJson = "{}",
                    totalProgression = 0.1,
                    updatedAt = 2_000L,
                    readAt = 2_000L,
                ),
            )
            assertTrue("reading_progress" in withTimeout(5_000) { seen.receive() })
            insertSession("file:///a.epub", today, TimeUnit.MINUTES.toMillis(5))
            assertTrue("reading_sessions" in withTimeout(5_000) { seen.receive() })
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun `only Liseur's stats receivers can be configured`() {
        val pkg = context.packageName
        assertTrue(
            statsWidgetFor(pkg, ComponentName(pkg, WeekStatsWidgetReceiver::class.java.name)) is WeekStatsWidget,
        )
        assertTrue(
            statsWidgetFor(pkg, ComponentName(pkg, CoverStatsWidgetReceiver::class.java.name)) is CoverStatsWidget,
        )
        assertNull(statsWidgetFor(pkg, ComponentName(pkg, CoverOnlyWidgetReceiver::class.java.name)))
        assertNull(statsWidgetFor(pkg, ComponentName("evil", WeekStatsWidgetReceiver::class.java.name)))
        assertNull(statsWidgetFor(pkg, null))
    }

    @Test
    fun `cover initials take two words`() {
        assertEquals("HP", coverInitials("Harry Potter"))
        assertEquals("MO", coverInitials("Moby"))
        assertEquals("?", coverInitials("   "))
    }

    @Test
    fun `duration formatting matches durationParts`() {
        assertEquals(DurationParts.None, durationParts(0))
        assertEquals(
            context.getString(com.chmouel.liseur.R.string.duration_hours_minutes, 1, 30),
            formatReadingDuration(context, TimeUnit.MINUTES.toMillis(90)),
        )
    }

    @Test
    fun `large cover is capped before sending to the widget`() {
        val file = File.createTempFile("widget-cover", ".png", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(1023, 700, Bitmap.Config.ARGB_8888)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()

            val cover = checkNotNull(decodeCoverBitmap(file.path))
            assertTrue(maxOf(cover.width, cover.height) <= 256)
            assertTrue(cover.allocationByteCount <= 256 * 256 * 4)
            cover.recycle()
        } finally {
            file.delete()
        }
    }

    private fun repository(decodeCover: (String) -> Bitmap? = { null }) = WidgetRepository(
        bookDao = db.bookDao(),
        progressDao = db.readingProgressDao(),
        sessionDao = db.readingSessionDao(),
        zone = { zone },
        today = { today },
        weekStart = { weekStart },
        decodeCover = decodeCover,
    )

    private suspend fun insertBook(url: String, title: String, openedAt: Long) {
        db.bookDao().upsert(
            Book(
                url = url,
                title = title,
                author = "Author",
                coverPath = null,
                source = null,
                addedAt = openedAt,
                lastOpenedAt = openedAt,
            ),
        )
    }

    private suspend fun insertSession(bookUrl: String, day: LocalDate, durationMs: Long) {
        val started = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val ended = started + durationMs
        db.readingSessionDao().insert(
            ReadingSession(
                bookUrl = bookUrl,
                startedAt = started,
                endedAt = ended,
                lastCheckpointAt = ended,
                durationMs = durationMs,
                startProgression = 0.0,
                endProgression = 0.1,
            ),
        )
    }
}
