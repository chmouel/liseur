package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.domain.FINISHED_PROGRESSION
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ContinuationBookTest {
    private lateinit var db: LiseurDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun book(url: String, opened: Long? = 100) = Book(
        url = url, title = url, author = null, coverPath = null, source = null,
        addedAt = 0, lastOpenedAt = opened,
    )

    private suspend fun selected() = db.bookDao().continuationBook(FINISHED_PROGRESSION)?.url

    @Test
    fun `skips newer ineligible books without changing ordinary most recent`() = runTest {
        db.bookDao().upsert(book("older", 50))
        for (recent in listOf(
            book("finished").copy(finishedAt = 1),
            book("archived").copy(archivedAt = 1),
            book("hidden").copy(hiddenAt = 1),
            book("remote").copy(downloadState = DownloadState.REMOTE),
            book("queued").copy(downloadState = DownloadState.QUEUED),
            book("failed").copy(downloadState = DownloadState.FAILED),
        )) {
            db.bookDao().upsert(recent)
        }
        assertEquals("older", selected())
        assertEquals(100L, db.bookDao().mostRecentlyOpened()?.lastOpenedAt)
    }

    @Test
    fun `completion boundary is exact and unknown progress is eligible`() = runTest {
        db.bookDao().upsert(book("recent"))
        db.bookDao().upsert(book("older", 50))
        db.readingProgressDao().recordLocal("recent", """{"href":"chapter"}""", 0.97, null, null, 1)
        assertEquals("older", selected())
        db.readingProgressDao().recordLocal("recent", """{"href":"chapter"}""", 0.969, null, null, 2)
        assertEquals("recent", selected())
        db.readingProgressDao().recordLocal("recent", """{"href":"chapter"}""", null, null, null, 3)
        assertEquals("recent", selected())
    }

    @Test
    fun `synced locator alone counts but an empty placeholder does not`() = runTest {
        db.bookDao().upsert(book("placeholder", null))
        db.readingProgressDao().recordLocal("placeholder", "{}", null, null, null, 900)
        assertNull(selected())
        db.bookDao().upsert(book("synced", null))
        db.readingProgressDao().recordLocal("synced", """{"href":"chapter"}""", null, null, null, 100)
        assertEquals("synced", selected())
    }

    @Test
    fun `recent reading anywhere wins over local opening and import time`() = runTest {
        db.bookDao().upsert(book("local", 200))
        db.bookDao().upsert(book("synced", null))
        db.readingProgressDao().restoreBackupPosition(
            "synced", """{"href":"chapter"}""", 0.4, readAt = 300, now = 1000,
        )
        assertEquals("synced", selected())
        db.readingProgressDao().restoreBackupPosition(
            "synced", """{"href":"chapter"}""", 0.4, readAt = 100, now = 2000,
        )
        assertEquals("local", selected())
    }

    @Test
    fun `a restored document URI remains available even for a remote row`() = runTest {
        db.bookDao().upsert(book("restored").copy(
            downloadState = DownloadState.REMOTE, localUri = "content://restored/document",
        ))
        assertEquals("restored", selected())
    }

    @Test
    fun `no reading evidence or no eligible books yields no candidate`() = runTest {
        assertNull(selected())
        db.bookDao().upsert(book("never opened", null))
        db.bookDao().upsert(book("finished").copy(finishedAt = 1))
        assertNull(selected())
    }
}
