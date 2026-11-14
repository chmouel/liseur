package com.chmouel.liseur.data.library

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.BackedUpBookSessions
import com.chmouel.liseur.domain.BackedUpSession
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadingSessionBackupRepositoryTest {
    private lateinit var db: LiseurDatabase
    private lateinit var repository: ReadingSessionBackupRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        repository = ReadingSessionBackupRepository(
            db.readingSessionDao(),
            db.bookDao(),
            inTransaction = { work -> db.withTransaction { work() } },
        )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `a sitting restored before its book was found is reattached, not counted again`() = runTest {
        val archivedUrl = "calibre:missing-book"
        val localUrl = "file:///data/user/0/com.chmouel.liseur/files/books/abc.epub"
        val books = listOf(
            BackedUpBookSessions(
                bookId = archivedUrl,
                title = "A Book",
                author = "An Author",
                sessions = listOf(BackedUpSession(1_000, 61_000, 60_000, 0, 0.1, 0.2)),
            ),
        )

        // First restore: no library row, so the sitting stays under the archived URL.
        assertEquals(SessionRestoreResult(added = 1), repository.restore(books))
        assertEquals(archivedUrl, db.readingSessionDao().allOnce().single().bookUrl)

        // The book turns up later, and restoring the same archive must not count the time twice.
        db.bookDao().upsert(
            Book(
                url = localUrl,
                title = "A Book",
                author = "An Author",
                coverPath = null,
                source = null,
                addedAt = 1,
                lastOpenedAt = null,
                localUri = localUrl,
            ),
        )
        val second = repository.restore(books, aliases = mapOf(archivedUrl to localUrl))
        assertEquals(SessionRestoreResult(alreadyPresent = 1), second)

        val row = db.readingSessionDao().allOnce().single()
        assertEquals(localUrl, row.bookUrl)
        assertEquals(1_000L, row.startedAt)
    }

    @Test
    fun `a collision after the book is found keeps one sitting, not two`() = runTest {
        val archivedUrl = "calibre:collision"
        val localUrl = "file:///data/user/0/com.chmouel.liseur/files/books/ghi.epub"
        val books = listOf(
            BackedUpBookSessions(
                bookId = archivedUrl,
                title = "A Book",
                author = "An Author",
                sessions = listOf(BackedUpSession(1_000, 61_000, 60_000, 0, 0.1, 0.2)),
            ),
        )

        // The sitting arrives once under the archived address, then the same archive resolves it.
        assertEquals(SessionRestoreResult(added = 1), repository.restore(books))
        db.bookDao().upsert(
            Book(
                url = localUrl, title = "A Book", author = "An Author", coverPath = null, source = null,
                addedAt = 1, lastOpenedAt = null, localUri = localUrl,
            ),
        )
        assertEquals(
            SessionRestoreResult(alreadyPresent = 1),
            repository.restore(books, aliases = mapOf(archivedUrl to localUrl)),
        )

        val rows = db.readingSessionDao().allOnce()
        assertEquals(1, rows.size)
        assertEquals(localUrl, rows.single().bookUrl)
        assertEquals(60_000L, rows.single().durationMs)
    }

    @Test
    fun `a sitting stays single when its book stops resolving to the same address`() = runTest {
        val archivedUrl = "calibre:forgotten"
        val localUrl = "file:///data/user/0/com.chmouel.liseur/files/books/jkl.epub"
        val books = listOf(
            BackedUpBookSessions(
                bookId = archivedUrl,
                title = "A Book",
                author = "An Author",
                sessions = listOf(BackedUpSession(2_000, 62_000, 60_000, 0, 0.1, 0.2)),
            ),
        )

        assertEquals(SessionRestoreResult(added = 1), repository.restore(books, aliases = mapOf(archivedUrl to localUrl)))
        // The book is gone now, so the same archive no longer resolves it.
        assertEquals(SessionRestoreResult(alreadyPresent = 1), repository.restore(books))

        assertEquals(1, db.readingSessionDao().allOnce().size)
    }

    @Test
    fun `a locally recorded sitting under the same address never moves`() = runTest {
        val archivedUrl = "calibre:local-book"
        val localUrl = "file:///data/user/0/com.chmouel.liseur/files/books/def.epub"
        db.readingSessionDao().insert(
            com.chmouel.liseur.data.db.ReadingSession(
                bookUrl = archivedUrl,
                startedAt = 1_000,
                endedAt = 61_000,
                lastCheckpointAt = 61_000,
                durationMs = 60_000,
            ),
        )
        val books = listOf(
            BackedUpBookSessions(
                bookId = archivedUrl,
                title = "A Book",
                author = "An Author",
                sessions = listOf(BackedUpSession(1_000, 61_000, 60_000, 0, 0.1, 0.2)),
            ),
        )

        assertEquals(SessionRestoreResult(alreadyPresent = 1), repository.restore(books, aliases = mapOf(archivedUrl to localUrl)))
        assertEquals(archivedUrl, db.readingSessionDao().allOnce().single().bookUrl)
    }
}
