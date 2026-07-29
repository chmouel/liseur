package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restoring marks from a saved file.
 *
 * The claim being checked is that restoring is safe to do twice, and
 * safe to do from a file older than what is on the device: nothing is
 * overwritten, so a note rewritten since the backup was made survives
 * being restored over.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class AnnotationRestoreTest {

    private lateinit var db: LiseurDatabase
    private lateinit var dao: BookAnnotationDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        dao = db.annotationDao()
    }

    @After
    fun close() = db.close()

    private fun mark(id: String, note: String? = null, book: String = "book") = BookAnnotation(
        id = id,
        bookId = book,
        kind = "HIGHLIGHT",
        locatorJson = "{}",
        note = note,
        createdAt = 1,
    )

    @Test
    fun `restoring brings in what is missing`() = runTest {
        val added = dao.insertMissing(listOf(mark("a"), mark("b")))

        assertEquals(2, added.count { it != -1L })
        assertEquals(2, dao.count("book"))
    }

    @Test
    fun `restoring the same file twice adds nothing the second time`() = runTest {
        dao.insertMissing(listOf(mark("a"), mark("b")))

        val again = dao.insertMissing(listOf(mark("a"), mark("b")))

        assertEquals(0, again.count { it != -1L })
        assertEquals(2, dao.count("book"))
    }

    @Test
    fun `a note rewritten since the backup is not undone by restoring it`() = runTest {
        dao.upsert(mark("a", note = "what I think now"))

        dao.insertMissing(listOf(mark("a", note = "what I thought then")))

        assertEquals("what I think now", dao.all().single().note)
    }

    @Test
    fun `a partly familiar file brings in only the new`() = runTest {
        dao.insertMissing(listOf(mark("a")))

        val added = dao.insertMissing(listOf(mark("a"), mark("b"), mark("c")))

        assertEquals(2, added.count { it != -1L })
        assertEquals(3, dao.count("book"))
    }

    @Test
    fun `marks land on the book they were restored against`() = runTest {
        dao.insertMissing(listOf(mark("a", book = "one"), mark("b", book = "two")))

        assertEquals(1, dao.count("one"))
        assertEquals(1, dao.count("two"))
    }
}
