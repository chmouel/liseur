package com.chmouel.liseur.data.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.BackedUpBook
import com.chmouel.liseur.domain.encodeAnnotationBackup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class AnnotationBackupRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: LiseurDatabase
    private val requested = mutableListOf<String>()
    private val committedCounts = mutableListOf<Int>()

    @Before
    fun open() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiseurDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
    }

    private fun repository(dao: BookAnnotationDao = db.annotationDao()) =
        AnnotationBackupRepository(context, dao, db.bookDao()) { bookId ->
            requested += bookId
            committedCounts += runBlocking { db.annotationDao().count(bookId) }
        }

    private fun mark(id: String, bookId: String = "book-one") = BookAnnotation(
        id = id,
        bookId = bookId,
        kind = AnnotationKind.BOOK_NOTE.name,
        locatorJson = "",
        note = "A restored note",
        createdAt = 123,
    )

    private fun source(vararg marks: BookAnnotation): Uri {
        val text = encodeAnnotationBackup(
            marks.groupBy { it.bookId }.map { (id, annotations) ->
                BackedUpBook(id, "A book", "An author", annotations)
            },
        )
        val uri = Uri.parse("content://test/backup")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(text.toByteArray())
        }
        return uri
    }

    @Test
    fun `imports request each changed book once after all inserts commit`() = runTest {
        val result = repository().importFrom(
            source(mark("one"), mark("two"), mark("three", "book-two")),
        )

        assertEquals(BackupResult.Imported(3, 0), result)
        assertEquals(listOf("book-one", "book-two"), requested)
        assertEquals(listOf(2, 1), committedCounts)
    }

    @Test
    fun `duplicates do not request sync even beside a new book`() = runTest {
        db.annotationDao().upsert(mark("existing"))
        val repo = repository()
        val source = source(mark("existing"), mark("new", "book-two"))

        assertEquals(BackupResult.Imported(1, 1), repo.importFrom(source))
        assertEquals(listOf("book-two"), requested)
        requested.clear()
        assertEquals(BackupResult.Imported(0, 2), repo.importFrom(source))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `the matched local book is requested rather than the backup path`() = runTest {
        db.bookDao().upsert(
            Book(
                url = "local-book",
                title = "A book",
                author = "An author",
                coverPath = null,
                source = null,
                addedAt = 0,
                lastOpenedAt = null,
            ),
        )

        assertEquals(BackupResult.Imported(2, 0), repository().importFrom(source(mark("one"), mark("two"))))
        assertEquals(listOf("local-book"), requested)
        assertEquals(listOf(2), committedCounts)
    }

    @Test
    fun `a failed insert emits no local change signal`() = runTest {
        val failure = IllegalStateException("write failed")
        val dao = object : BookAnnotationDao by db.annotationDao() {
            override suspend fun insertMissing(annotations: List<BookAnnotation>): List<Long> =
                throw failure
        }

        val result = runCatching { repository(dao).importFrom(source(mark("one"))) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertTrue(requested.isEmpty())
        assertTrue(db.annotationDao().all().isEmpty())
    }

    @Test
    fun `an empty import requests nothing`() = runTest {
        assertEquals(BackupResult.Imported(0, 0), repository().importFrom(source()))
        assertTrue(requested.isEmpty())
    }
}
