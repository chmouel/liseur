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

        val snapshot = repository().load(context)
        val book = checkNotNull(snapshot.book)
        assertEquals("file:///new.epub", book.url)
        assertEquals("New Title", book.title)
        assertEquals("NT", book.initials)
    }

    @Test
    fun `the cover and library receivers are offered without configuration`() {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        assertEquals(
            setOf(CoverOnlyWidgetReceiver::class.java.name, LibraryWidgetReceiver::class.java.name),
            receivers.map { it.activityInfo.name }.toSet(),
        )
    }

    @Test
    fun `the library leads with the book read most recently on any device`() = runBlocking {
        insertBook("file:///here.epub", "Here", openedAt = 2_000L)
        insertBook("file:///elsewhere.epub", "Elsewhere", openedAt = 1_000L)
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookUrl = "file:///elsewhere.epub",
                locatorJson = "{}",
                totalProgression = 0.4,
                updatedAt = 3_000L,
                readAt = 3_000L,
            ),
        )

        val shelf = repository().loadLibrary(context, anchor = 0)
        assertEquals(2, shelf.count)
        assertEquals(
            listOf("file:///elsewhere.epub", "file:///here.epub"),
            shelf.page(start = 0, capacity = 8).map { it.url },
        )
    }

    @Test
    fun `the library decodes only the covers its widget sizes can show`() = runBlocking {
        repeat(30) { insertBook("file:///$it.epub", "Book $it", openedAt = 1_000L + it, coverPath = "/covers/$it.jpg") }
        val decoded = mutableListOf<String>()
        val featured = mutableListOf<String>()
        val shelf = WidgetRepository(
            bookDao = db.bookDao(),
            decodeCover = { featured += it; null },
            decodeShelfCover = { decoded += it; null },
        ).loadLibrary(context, anchor = 12)
        assertEquals(30, shelf.count)
        assertEquals(libraryWindow(12, 30), shelf.books.keys)
        assertEquals(shelf.books.size, decoded.size + featured.size)
        assertTrue(featured.isNotEmpty())
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
        decodeCover = decodeCover,
    )

    private suspend fun insertBook(url: String, title: String, openedAt: Long, coverPath: String? = null) {
        db.bookDao().upsert(
            Book(
                url = url,
                title = title,
                author = "Author",
                coverPath = coverPath,
                source = null,
                addedAt = openedAt,
                lastOpenedAt = openedAt,
            ),
        )
    }
}
