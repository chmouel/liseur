package com.chmouel.liseur.data.library

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.domain.BackedUpReadingPosition
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.NarrativeCoverage
import com.chmouel.liseur.domain.decodeReadingPositionBackup
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadingPositionBackupRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var db: LiseurDatabase
    private val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml"}"""
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LiseurDatabase::class.java).build()
    }
    @After fun close() = db.close()
    private fun repository() = ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(),
        inTransaction = { work -> db.withTransaction { work() } })

    @Test
    fun `staged positions round trip while status-only rows are omitted and bytes are bounded`() = runTest {
        db.readingProgressDao().upsert(ReadingProgress("a", locator, 0.2, updatedAt = 20, readAt = 10))
        db.readingProgressDao().upsert(ReadingProgress("b", "{}", null, updatedAt = 30))
        val file = folder.newFile()
        repository().writeContents(file, 4096)
        assertEquals(listOf(BackedUpReadingPosition("a", null, null, locator, 0.2, 10, readingStatus = "automatic")), decodeReadingPositionBackup(file.readText()))
        assertTrue(runCatching { repository().writeContents(file, 100) }.exceptionOrNull() is ReadingPositionBackupTooLarge)
        assertTrue(file.length() <= 100)
    }

    @Test
    fun `export carries every position across several pages`() = runTest {
        val expected = List(75) { index ->
            BackedUpReadingPosition("book-${index.toString().padStart(3, '0')}", null, null, locator, 0.2, 10, readingStatus = "automatic")
        }
        expected.forEach {
            db.readingProgressDao().upsert(ReadingProgress(it.bookId, it.locatorJson, it.progression, updatedAt = it.readAt))
        }
        val file = folder.newFile()
        repository().writeContents(file, 65536)
        assertEquals(expected, decodeReadingPositionBackup(file.readText()))
    }

    @Test
    fun `partial restore requests sync for positions written before a later failure`() = runTest {
        val requests = mutableListOf<String>()
        val repository = ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao(), requests::add)
        db.readingProgressDao().openBooks.enter("second")
        val result = runCatching {
            repository.restore(listOf(
                BackedUpReadingPosition("first", null, null, locator, 0.3, 10),
                BackedUpReadingPosition("second", null, null, locator, 0.4, 10),
            ))
        }
        assertTrue(result.isFailure)
        assertEquals(listOf("first"), requests)
        assertEquals(locator, db.readingProgressDao().get("first")?.locatorJson)
        assertEquals(null, db.readingProgressDao().get("second"))
        db.readingProgressDao().openBooks.leave("second")
    }

    @Test
    fun `a status-only automatic withdrawal is backed up`() = runTest {
        db.readingProgressDao().upsert(ReadingProgress("book", "{}", null, updatedAt = 20, statusRevision = 1))
        val file = folder.newFile()
        repository().writeContents(file, 4096)
        assertEquals("automatic", decodeReadingPositionBackup(file.readText()).single().readingStatus)
    }

    @Test
    fun `status-only choice survives backup without moving a restored position`() = runTest {
        val progress = db.readingProgressDao()
        progress.upsert(ReadingProgress("book", "{}", null, updatedAt = 20, finishedOverride = FinishedOverride.PAUSED.ordinal))
        val file = folder.newFile()
        repository().writeContents(file, 4096)
        val positions = decodeReadingPositionBackup(file.readText())
        assertEquals("paused", positions.single().readingStatus)
        progress.upsert(ReadingProgress("book", locator, 0.7, updatedAt = 30))
        repository().restore(positions)
        val restored = progress.get("book")!!
        assertEquals(FinishedOverride.PAUSED, restored.override)
        assertEquals(0.7, restored.totalProgression!!, 0.0)
        assertEquals(locator, restored.locatorJson)
    }

    @Test
    fun `restoring an unread choice does not reset the existing position`() = runTest {
        val progress = db.readingProgressDao()
        progress.upsert(ReadingProgress("book", locator, 0.7, updatedAt = 30))
        repository().restore(listOf(BackedUpReadingPosition("book", null, null, "{}", null, 20,
            readingStatus = "unread")))
        val restored = progress.get("book")!!
        assertEquals(FinishedOverride.UNREAD, restored.override)
        assertEquals(0.7, restored.totalProgression!!, 0.0)
        assertEquals(locator, restored.locatorJson)
        assertNull(restored.unreadResetRevision)
    }

    @Test
    fun `percentage-only positions survive export and restore`() = runTest {
        db.readingProgressDao().upsert(ReadingProgress("book", "{}", 0.6, updatedAt = 20))
        val file = folder.newFile()
        repository().writeContents(file, 4096)
        val positions = decodeReadingPositionBackup(file.readText())
        assertEquals(listOf(BackedUpReadingPosition("book", null, null, "{}", 0.6, 20, readingStatus = "automatic")), positions)
        db.readingProgressDao().forget("book")
        assertEquals(1, repository().restore(positions))
        assertEquals(0.6, db.readingProgressDao().get("book")?.totalProgression)
        assertEquals("{}", db.readingProgressDao().get("book")?.locatorJson)
    }

    @Test
    fun `two backup identities cannot replace the same local book`() = runTest {
        db.bookDao().upsert(Book(url = "local", title = "Poems", author = "Blake", coverPath = null,
            source = null, addedAt = 0, lastOpenedAt = null))
        val local = ReadingProgress("local", locator, 0.8, updatedAt = 20)
        db.readingProgressDao().upsert(local)
        assertEquals(2, repository().restore(listOf(
            BackedUpReadingPosition("old-one", "Poems", "Blake", locator, 0.3, 10),
            BackedUpReadingPosition("old-two", "Poems", "Blake", locator, 0.5, 10),
        )))
        assertEquals(local, db.readingProgressDao().get("local"))
        assertEquals(0.3, db.readingProgressDao().get("old-one")?.totalProgression)
        assertEquals(0.5, db.readingProgressDao().get("old-two")?.totalProgression)
    }

    @Test
    fun `restored positions refresh finished shelf state while preserving explicit overrides`() = runTest {
        val url = "book"
        db.bookDao().upsert(Book(url = url, title = "Book", author = null, coverPath = null,
            source = null, addedAt = 0, lastOpenedAt = null, finishedAt = 10))
        db.readingProgressDao().upsert(ReadingProgress(url, locator, 1.0, updatedAt = 20))
        val backedUp = BackedUpReadingPosition(url, null, null, locator, 0.4, 10)
        repository().restore(listOf(backedUp))
        assertEquals(null, db.bookDao().getByUrl(url)?.finishedAt)
        repository().restore(listOf(backedUp.copy(progression = 1.0)))
        assertTrue(db.bookDao().getByUrl(url)?.finishedAt != null)
        FinishedState(db.bookDao(), db.readingProgressDao()).setFinished(url, true)
        repository().restore(listOf(backedUp))
        assertTrue(db.bookDao().getByUrl(url)?.finishedAt != null)
        assertEquals("Finished", db.readingProgressDao().get(url)?.status)
        FinishedState(db.bookDao(), db.readingProgressDao()).setFinished(url, false)
        repository().restore(listOf(backedUp.copy(progression = 1.0)))
        assertEquals(null, db.bookDao().getByUrl(url)?.finishedAt)
        assertEquals("ReadyToRead", db.readingProgressDao().get(url)?.status)
    }

    @Test
    fun `a partial recording survives the backup and does not finish the book on restore`() = runTest {
        // ADR-0066: a recording that covers part of the narrative never
        // finishes the ebook, and the coverage has to travel with the
        // place or a restored 0.998 is the end of the book.
        db.bookDao().upsert(Book(url = "book", title = "Book", author = null, coverPath = null,
            source = null, addedAt = 0, lastOpenedAt = null))
        db.readingProgressDao().upsert(
            ReadingProgress("book", locator, 0.998, updatedAt = 20, coverage = "partial"),
        )
        val file = folder.newFile()
        repository().writeContents(file, 4096)
        val positions = decodeReadingPositionBackup(file.readText())
        assertEquals(
            listOf(BackedUpReadingPosition("book", "Book", null, locator, 0.998, 20, "partial", "automatic")),
            positions,
        )

        db.readingProgressDao().forget("book")
        assertEquals(1, repository().restore(positions))

        val row = db.readingProgressDao().get("book")!!
        assertEquals(NarrativeCoverage.PARTIAL, row.narrativeCoverage)
        assertNull(db.bookDao().getByUrl("book")?.finishedAt)

        // Reading on here is an ebook's place again, and says so.
        db.readingProgressDao().recordLocal("book", locator, 0.5, null, null, null, null, null, 30)
        assertNull(db.readingProgressDao().get("book")!!.narrativeCoverage)
    }

    @Test
    fun `restore refuses to replace a position being used by an open reader`() = runTest {
        val saved = ReadingProgress("book", locator, 0.2, updatedAt = 20)
        db.readingProgressDao().upsert(saved)
        db.readingProgressDao().openBooks.enter("book")
        assertTrue(runCatching {
            repository().restore(listOf(BackedUpReadingPosition("book", null, null, locator, 0.5, 10)))
        }.isFailure)
        assertEquals(saved, db.readingProgressDao().get("book"))
        db.readingProgressDao().openBooks.leave("book")
    }

    @Test
    fun `an alias target still keeps the original id when two positions collide`() = runTest {
        db.bookDao().upsert(Book(url = "file:///files/books/shared.epub", title = "Poems", author = "Blake", coverPath = null,
            source = null, addedAt = 0, lastOpenedAt = null))
        val local = ReadingProgress("file:///files/books/shared.epub", locator, 0.8, updatedAt = 20)
        db.readingProgressDao().upsert(local)
        val aliases = mapOf(
            "calibre:one" to "file:///files/books/shared.epub",
            "calibre:two" to "file:///files/books/shared.epub",
        )
        assertEquals(2, repository().restore(
            listOf(
                BackedUpReadingPosition("calibre:one", "Poems", "Blake", locator, 0.3, 10),
                BackedUpReadingPosition("calibre:two", "Poems", "Blake", locator, 0.5, 10),
            ),
            aliases = aliases,
        ))
        assertEquals(local, db.readingProgressDao().get("file:///files/books/shared.epub"))
        assertEquals(0.3, db.readingProgressDao().get("calibre:one")?.totalProgression)
        assertEquals(0.5, db.readingProgressDao().get("calibre:two")?.totalProgression)
    }
}
