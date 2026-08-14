package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Starting a book over when the file at its path turns out to hold a
 * different book.
 *
 * The decision itself lives in `SwappedFile` and is tested there. What is
 * checked here is that acting on it really does clear everything, and
 * clears it only for the book concerned — a rescan touches every row in
 * the library, so a query missing its `WHERE` would empty the lot.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SwappedFileCleanupTest {

    private lateinit var db: LiseurDatabase

    private val swapped = "file:///books/one.epub"
    private val untouched = "file:///books/two.epub"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private suspend fun seed(url: String) {
        db.bookDao().upsert(
            Book(
                url = url,
                title = "A book",
                author = "Someone",
                coverPath = null,
                source = "tree",
                addedAt = 1,
                lastOpenedAt = 5,
                finishedAt = 9,
                workId = "urn:uuid:old",
            ),
        )
        db.readingProgressDao().recordLocal(
            bookUrl = url,
            locatorJson = """{"at":0.4}""",
            progression = 0.4,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 100,
        )
        db.annotationDao().upsert(
            BookAnnotation(
                id = "note-in-$url",
                bookId = url,
                kind = AnnotationKind.HIGHLIGHT.name,
                locatorJson = """{"at":0.2}""",
                text = "a sentence that is no longer in the book",
                createdAt = 1,
            ),
        )
    }

    @Test
    fun `nothing about the old book is left behind`() = runTest {
        seed(swapped)

        db.readingProgressDao().forget(swapped)
        db.annotationDao().deleteForBook(swapped)
        db.bookDao().forgetReadingHistory(swapped)

        assertNull("the old position is gone", db.readingProgressDao().get(swapped))
        assertEquals(0, db.annotationDao().count(swapped))
        val book = db.bookDao().getByUrl(swapped)
        assertNotNull("the book itself stays in the library", book)
        assertNull("it does not claim to have been opened", book?.lastOpenedAt)
        assertNull("nor to have been read", book?.finishedAt)
    }

    @Test
    fun `the rest of the library is untouched`() = runTest {
        seed(swapped)
        seed(untouched)

        db.readingProgressDao().forget(swapped)
        db.annotationDao().deleteForBook(swapped)
        db.bookDao().forgetReadingHistory(swapped)

        assertNotNull(db.readingProgressDao().get(untouched))
        assertEquals(1, db.annotationDao().count(untouched))
        assertEquals(5L, db.bookDao().getByUrl(untouched)?.lastOpenedAt)
    }

    @Test
    fun `re-reading the file records what it now is`() = runTest {
        seed(swapped)

        db.bookDao().refreshIndexedFile(
            url = swapped,
            title = "Something else",
            author = "Another",
            coverPath = null,
            fileModifiedAt = 2_000,
            workId = "urn:uuid:new",
            seriesName = null,
            seriesIndex = null,
        )

        val book = db.bookDao().getByUrl(swapped)
        assertEquals("Something else", book?.title)
        assertEquals("urn:uuid:new", book?.workId)
        assertEquals(2_000L, book?.fileModifiedAt)
    }
}
