package com.chmouel.liseur.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.ReadingStatus
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

    private companion object {
        const val BOOK = "book"
    }
}
