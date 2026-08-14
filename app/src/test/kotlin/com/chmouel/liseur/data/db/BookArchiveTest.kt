package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Putting a book away, against the real SQL.
 *
 * The one that is easy to miss is the Continue reading card: it is
 * chosen by its own query rather than by the library's filters, so a
 * archived book would otherwise carry on being the first thing offered
 * on opening the app.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookArchiveTest {

    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private suspend fun add(url: String, lastOpenedAt: Long?) = db.bookDao().upsert(
        Book(
            url = url,
            title = url,
            author = null,
            coverPath = null,
            source = null,
            addedAt = 0,
            lastOpenedAt = lastOpenedAt,
        ),
    )

    @Test
    fun `an archived book is not what you are offered to carry on with`() = runTest {
        add("recent", lastOpenedAt = 100)
        add("older", lastOpenedAt = 50)

        assertEquals("recent", db.bookDao().mostRecentlyOpened()?.url)

        db.bookDao().setArchived("recent", 1_000)

        assertEquals("older", db.bookDao().mostRecentlyOpened()?.url)
    }

    @Test
    fun `with everything archived there is nothing to carry on with`() = runTest {
        add("only", lastOpenedAt = 100)
        db.bookDao().setArchived("only", 1_000)

        assertNull(db.bookDao().mostRecentlyOpened())
    }

    @Test
    fun `putting a book back offers it again`() = runTest {
        add("recent", lastOpenedAt = 100)
        add("older", lastOpenedAt = 50)
        db.bookDao().setArchived("recent", 1_000)

        db.bookDao().setArchived("recent", null)

        assertEquals("recent", db.bookDao().mostRecentlyOpened()?.url)
        assertNull(db.bookDao().getByUrl("recent")?.archivedAt)
    }

    @Test
    fun `putting a book away keeps where you were in it`() = runTest {
        add("book", lastOpenedAt = 100)
        db.readingProgressDao().recordLocal(
            bookUrl = "book",
            locatorJson = """{"at":0.4}""",
            progression = 0.4,
            readingSpeed = null,
            status = "Reading",
            updatedAt = 1,
        )

        db.bookDao().setArchived("book", 1_000)

        assertEquals(0.4, db.readingProgressDao().get("book")?.totalProgression!!, 0.0)
        assertEquals(100L, db.bookDao().getByUrl("book")?.lastOpenedAt)
    }
}
