package com.chmouel.liseur.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.domain.BackedUpReadingPosition
import com.chmouel.liseur.domain.decodeReadingPositionBackup
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    private fun repository() = ReadingPositionBackupRepository(db.readingProgressDao(), db.bookDao())

    @Test
    fun `staged positions round trip while status-only rows are omitted and bytes are bounded`() = runTest {
        db.readingProgressDao().upsert(ReadingProgress("a", locator, 0.2, updatedAt = 20, readAt = 10))
        db.readingProgressDao().upsert(ReadingProgress("b", "{}", null, updatedAt = 30))
        val file = folder.newFile()
        repository().writeContents(file, 4096)
        assertEquals(listOf(BackedUpReadingPosition("a", null, null, locator, 0.2, 10)), decodeReadingPositionBackup(file.readText()))
        assertTrue(runCatching { repository().writeContents(file, 100) }.exceptionOrNull() is ReadingPositionBackupTooLarge)
        assertTrue(file.length() <= 100)
    }

    @Test
    fun `export carries every position across several pages`() = runTest {
        val expected = List(75) { index ->
            BackedUpReadingPosition("book-${index.toString().padStart(3, '0')}", null, null, locator, 0.2, 10)
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
}
