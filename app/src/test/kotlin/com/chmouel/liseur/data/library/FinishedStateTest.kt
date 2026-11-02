package com.chmouel.liseur.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.bookorbit.BookOrbitLocalPositionWriter
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.LibraryFilterOption
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.shelfStateFor
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `only a book the reader finished is reported, not one put back or adopted`() =
        runTest {
            db.bookDao().upsert(
                Book(url = BOOK, title = "Book", author = null, coverPath = null, source = null, addedAt = 0, lastOpenedAt = null),
            )
            val state = state()

            state.setFinished(BOOK, false)
            val unread = db.readingProgressDao().get(BOOK)!!
            assertEquals(0.0, unread.totalProgression!!, 0.0)
            assertEquals("{}", unread.locatorJson)
            assertEquals(ReadingStatus.READY_TO_READ.wireName, unread.status)
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
    fun `marking unread resets the current place but preserves reading history and sync baseline`() =
        runTest {
            for (progression in listOf(0.42, 1.0)) {
                db.bookDao().upsert(
                    Book(
                        url = BOOK,
                        title = "Book",
                        author = null,
                        coverPath = null,
                        source = null,
                        addedAt = 0,
                        lastOpenedAt = null,
                        finishedAt = 5,
                    ),
                )
                db.readingProgressDao().recordLocal(
                    bookUrl = BOOK,
                    locatorJson = """{"href":"chapter.xhtml"}""",
                    progression = progression,
                    readingSecondsPerPosition = null,
                    readingPaceSamples = null,
                    readingPaceElapsedMs = null,
                    readingPaceEvidence = null,
                    status = ReadingStatus.forProgression(progression).wireName,
                    updatedAt = 10L,
                )
                val recorded = db.readingProgressDao().get(BOOK)!!
                val before =
                    recorded.copy(
                        agreedProgression = progression,
                        agreedStatus = ReadingStatus.forProgression(progression).wireName,
                        ackedRevision = recorded.localRevision,
                    )
                db.readingProgressDao().upsert(before)
                val state = state()

                state.setFinished(BOOK, false)

                val projected =
                    db
                        .readingProgressDao()
                        .observeProgressions()
                        .first()
                        .single()
                assertEquals(BOOK, projected.bookUrl)
                assertEquals(0.0, projected.totalProgression!!, 0.0)
                assertEquals(FinishedOverride.UNREAD, projected.override)
                val after = db.readingProgressDao().get(BOOK)!!
                assertEquals("{}", after.locatorJson)
                assertEquals(ReadingStatus.READY_TO_READ.wireName, after.status)
                assertEquals(before.positionRevision + 1, after.positionRevision)
                assertEquals(before.statusRevision + 1, after.statusRevision)
                assertEquals(before.localRevision + 1, after.localRevision)
                assertEquals(before.ackedRevision, after.ackedRevision)
                assertEquals(before.agreedProgression, after.agreedProgression)
                assertEquals(before.agreedStatus, after.agreedStatus)
                assertEquals(before.readAt, after.readAt)
                assertEquals(before.peakProgression, after.peakProgression)
                assertEquals(before.peakLocator, after.peakLocator)
                assertEquals(before.peakRevision, after.peakRevision)
                assertTrue(after.isDirty)
                assertNull(db.bookDao().getByUrl(BOOK)?.finishedAt)
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `unread waits for earlier queued saves retries and completion but permits later reading`() =
        runTest {
            db.bookDao().upsert(
                Book(url = BOOK, title = "Book", author = null, coverPath = null,
                    source = null, addedAt = 0, lastOpenedAt = null),
            )
            val release = CompletableDeferred<Unit>()
            lateinit var publisher: ReadingPositionPublisher
            val state =
                FinishedState(
                    db.bookDao(),
                    db.readingProgressDao(),
                    beforeUnread = { publisher.flush(it) },
                )
            var attempts = 0
            publisher =
                ReadingPositionPublisher(
                    scope = backgroundScope,
                    overrideFor = { db.readingProgressDao().get(it)?.override ?: FinishedOverride.NONE },
                    persist = { update, status ->
                        if (attempts++ == 0) {
                            release.await()
                            error("temporary write failure")
                        }
                        BookOrbitLocalPositionWriter(db).save(update, status)
                    },
                    refreshFinished = state::refreshFromProgress,
                    markFinished = { state.setFinished(it, true) },
                    latestSync = LatestPositionSync(backgroundScope, { SyncOutcome.Success }, {}, { _, _ -> }),
                    scheduleClose = {},
                    onError = { _, _ -> },
                )
            val update = PositionUpdate(
                BOOK, """{"href":"old.xhtml"}""", 0.42, null, null, null, null, 10,
                resumesReading = true,
            )
            assertTrue(publisher.publish(update))
            assertTrue(publisher.completeBook(BOOK))
            runCurrent()

            val marked = async { state.setFinished(BOOK, false) }
            runCurrent()
            assertTrue(marked.isActive)
            release.complete(Unit)
            marked.await()

            val reset = db.readingProgressDao().get(BOOK)!!
            assertEquals("{}", reset.locatorJson)
            assertEquals(0.0, reset.totalProgression!!, 0.0)
            assertEquals(FinishedOverride.UNREAD, reset.override)
            assertTrue(publisher.publish(update.copy(resumesReading = false)))
            assertTrue(publisher.flush(BOOK))
            assertEquals(FinishedOverride.UNREAD, db.readingProgressDao().get(BOOK)!!.override)
            assertEquals(reset.statusRevision, db.readingProgressDao().get(BOOK)!!.statusRevision)
            assertTrue(publisher.publish(update.copy(locatorJson = """{"href":"new.xhtml"}""", progression = 0.1)))
            assertTrue(publisher.flush(BOOK))
            val resumed = db.readingProgressDao().get(BOOK)!!
            assertEquals(0.1, resumed.totalProgression!!, 0.0)
            assertEquals(FinishedOverride.NONE, resumed.override)
            assertEquals(reset.statusRevision + 1, resumed.statusRevision)
            assertEquals(ReadingStatus.READING.wireName, resumed.status)
            assertEquals(
                LibraryFilterOption.IN_PROGRESS,
                shelfStateFor(db.bookDao().getByUrl(BOOK)!!, resumed.totalProgression, resumed.override),
            )
            assertTrue(publisher.publish(update.copy(progression = 0.2)))
            assertTrue(publisher.flush(BOOK))
            assertEquals(resumed.statusRevision, db.readingProgressDao().get(BOOK)!!.statusRevision)

            state.setFinished(BOOK, true)
            assertTrue(publisher.publish(update.copy(progression = 0.99)))
            assertTrue(publisher.flush(BOOK))
            val finished = db.readingProgressDao().get(BOOK)!!
            assertEquals(FinishedOverride.FINISHED, finished.override)
            assertEquals(ReadingStatus.FINISHED.wireName, finished.status)
        }

    @Test
    fun `marking read keeps the current passage`() =
        runTest {
            val before =
                ReadingProgress(
                    BOOK,
                    """{"href":"chapter.xhtml"}""",
                    0.42,
                    updatedAt = 10,
                    positionRevision = 3,
                )
            db.readingProgressDao().upsert(before)

            state().setFinished(BOOK, true)

            val after = db.readingProgressDao().get(BOOK)!!
            assertEquals(before.locatorJson, after.locatorJson)
            assertEquals(before.totalProgression, after.totalProgression)
            assertEquals(before.positionRevision, after.positionRevision)
            assertEquals(ReadingStatus.FINISHED.wireName, after.status)
        }

    private companion object {
        const val BOOK = "book"
    }
}
