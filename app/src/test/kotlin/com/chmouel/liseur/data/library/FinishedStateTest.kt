package com.chmouel.liseur.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus
import kotlinx.coroutines.flow.first
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
class FinishedStateTest {
    private lateinit var db: LiseurDatabase
    private val finished = mutableListOf<String>()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LiseurDatabase::class.java).build()
    }

    @After fun close() = db.close()

    private fun state() = FinishedState(db.bookDao(), db.readingProgressDao(), onFinished = { finished += it })

    @Test
    fun `only a book the reader finished is reported, not one put back or adopted`() = runTest {
        db.bookDao().upsert(
            Book(url = BOOK, title = "Book", author = null, coverPath = null, source = null, addedAt = 0, lastOpenedAt = null),
        )
        val state = state()

        state.setFinished(BOOK, false)
        state.adoptRemoteStatus(
            BOOK,
            expectedStatusRevision = db.readingProgressDao().get(BOOK)!!.statusRevision,
            account = "",
            override = FinishedOverride.FINISHED,
            status = ReadingStatus.FINISHED,
        )
        assertEquals(emptyList<String>(), finished)

        state.setFinished(BOOK, true)
        assertEquals(listOf(BOOK), finished)
    }

    @Test
    fun `marking an in progress book unread keeps progression and projects unread override`() = runTest {
        db.bookDao().upsert(
            Book(url = BOOK, title = "Book", author = null, coverPath = null, source = null, addedAt = 0, lastOpenedAt = null),
        )
        db.readingProgressDao().recordLocal(
            bookUrl = BOOK,
            locatorJson = """{"href":"chapter.xhtml"}""",
            progression = 0.42,
            readingSecondsPerPosition = null,
            readingPaceSamples = null,
            readingPaceElapsedMs = null,
            readingPaceEvidence = null,
            status = ReadingStatus.READING.wireName,
            updatedAt = 10L,
        )
        val state = state()

        state.setFinished(BOOK, false)

        val projected = db.readingProgressDao().observeProgressions().first().single()
        assertEquals(BOOK, projected.bookUrl)
        assertEquals(0.42, projected.totalProgression ?: 0.0, 0.0001)
        assertEquals(FinishedOverride.UNREAD, projected.override)
        assertEquals(ReadingStatus.READING.wireName, db.readingProgressDao().get(BOOK)?.status)
    }

    private companion object {
        const val BOOK = "book"
    }
}
