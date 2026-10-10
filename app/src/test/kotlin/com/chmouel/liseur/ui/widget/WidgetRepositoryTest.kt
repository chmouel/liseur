package com.chmouel.liseur.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import java.io.File
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
    fun `empty shelf has no book`() = runBlocking {
        assertNull(repository().load(context).book)
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
    fun `unknown progress is distinct from a measured zero`() = runBlocking {
        insertBook("file:///book.epub", "Book", openedAt = 1)
        assertNull(repository().load(context).book!!.progression)
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = "file:///book.epub", locatorJson = "{}", totalProgression = 0.0,
                updatedAt = 1, readAt = 1,
            ),
        )
        assertEquals(0.0, repository().load(context).book!!.progression!!, 0.0)
    }

    @Test
    fun `only the cover receiver is offered without configuration`() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        assertEquals(
            setOf(CoverOnlyWidgetReceiver::class.java.name),
            receivers.map { it.activityInfo.name }.toSet(),
        )
    }

    @Test
    fun `only the cover is decoded`() = runBlocking {
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
        val decoded = mutableListOf<String>()
        repository(decodeCover = { decoded += it; null }).load(context)
        assertEquals(listOf("/covers/a.jpg"), decoded)
    }

    @Test
    fun `every table the cover reads wakes the refresh`() = runBlocking {
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
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun `cover initials take two words`() {
        assertEquals("HP", coverInitials("Harry Potter"))
        assertEquals("MO", coverInitials("Moby"))
        assertEquals("?", coverInitials("   "))
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
}
