package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.library.ReadingSessionRecorder
import com.chmouel.liseur.data.library.ReadingSessionManager
import com.chmouel.liseur.domain.ReadingSessionClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading sessions against the real SQL.
 *
 * The recorder's arithmetic is checked without a database elsewhere;
 * what is checked here is that it survives the round trip — that a
 * killed process leaves something recoverable, and that the sums the
 * dashboard reads back are the ones that went in.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionDaoTest {

    private lateinit var db: LiseurDatabase
    private lateinit var dao: ReadingSessionDao

    private val book = "calibre:uuid-1"
    private val other = "calibre:uuid-2"
    private val minute = 60_000L

    /** A clock the test moves by hand, so nothing depends on real time. */
    private var now = 0L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.readingSessionDao()
        now = 0
    }

    @After
    fun close() = db.close()

    private fun TestScope.recorder(bookUrl: String = book) = ReadingSessionRecorder(
        dao = dao,
        bookUrl = bookUrl,
        scope = backgroundScope,
        clock = ReadingSessionClock(),
        wallNow = { now },
        elapsedNow = { now },
    )

    private suspend fun totalFor(bookUrl: String): Long = dao.observeAll().first()
        .filter { it.bookUrl == bookUrl }
        .sumOf { it.durationMs }

    private suspend fun totalAll(): Long = dao.observeAll().first().sumOf { it.durationMs }

    /** What a successfully opened reader does when it reaches the foreground. */
    private suspend fun ReadingSessionRecorder.resumed() {
        onReaderReady()
        onResumed()
        awaitIdle()
    }

    private suspend fun ReadingSessionRecorder.paused() {
        onPaused()
        awaitIdle()
    }

    @Test
    fun `a segment is written down as it happens, not when it ends`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += 5 * minute
        recorder.onPageTurned()
        recorder.awaitIdle()

        // Nothing has been paused. If the process died here, this is
        // what would be left, and it has to be the five minutes read.
        val open = dao.openSessions().single()
        assertEquals(5 * minute, open.durationMs)
        assertTrue(open.isOpen)
    }

    @Test
    fun `pausing closes the segment and settles the time`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += 3 * minute
        recorder.onPageTurned()
        now += 2 * minute
        recorder.paused()

        assertTrue(dao.openSessions().isEmpty())
        assertEquals(5 * minute, totalFor(book))
    }

    @Test
    fun `coming back is a new segment`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += minute
        recorder.paused()
        now += 60 * minute
        recorder.resumed()
        now += minute
        recorder.paused()

        val rows = dao.observeAll().first()
        assertEquals(2, rows.size)
        // The hour away is not reading.
        assertEquals(2 * minute, rows.sumOf { it.durationMs })
    }

    @Test
    fun `an interrupted segment is closed at its last checkpoint, not at now`() = runTest {
        recorder().let { killed ->
            killed.resumed()
            now += 4 * minute
            killed.onPageTurned()
            killed.awaitIdle()
        }
        val interruptedAt = now

        // The phone was off overnight. A new reader opens the book.
        now += 12 * 60 * minute
        val fresh = recorder()
        fresh.awaitIdle()

        assertTrue("nothing may be left open", dao.openSessions().isEmpty())
        val recovered = dao.observeAll().first().single()
        assertEquals(interruptedAt, recovered.endedAt)
        assertEquals(4 * minute, recovered.durationMs)
        assertEquals(4 * minute, totalFor(book))
    }

    @Test
    fun `recovery does not touch the segment going on now`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += minute
        recorder.onPageTurned()
        recorder.awaitIdle()

        assertEquals(1, dao.openSessions().size)
        now += minute
        recorder.paused()
        assertEquals(2 * minute, totalFor(book))
    }

    @Test
    fun `a resume cannot erase a pause waiting for the database`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += minute
        // Submit both transitions without waiting between them. They
        // still have to be persisted in lifecycle order.
        recorder.onPaused()
        now += 60 * minute
        recorder.onResumed()
        now += minute
        recorder.onPaused()
        recorder.awaitIdle()

        val rows = dao.observeAll().first()
        assertEquals(2, rows.size)
        assertEquals(2 * minute, totalFor(book))
    }

    @Test
    fun `a pause with no segment open writes nothing`() = runTest {
        recorder().paused()
        assertEquals(0, totalAll())
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun `foreground loading time is not a reading session`() = runTest {
        val recorder = recorder()
        recorder.onResumed()
        now += 5 * minute
        recorder.awaitIdle()
        assertTrue(dao.observeAll().first().isEmpty())

        recorder.onReaderReady()
        now += minute
        recorder.onPaused()
        recorder.awaitIdle()
        assertEquals(minute, totalFor(book))
    }

    @Test
    fun `books are kept apart`() = runTest {
        recorder(book).let {
            it.resumed()
            now += 3 * minute
            it.paused()
        }
        recorder(other).let {
            it.resumed()
            now += minute
            it.paused()
        }

        assertEquals(3 * minute, totalFor(book))
        assertEquals(minute, totalFor(other))
        assertEquals(4 * minute, totalAll())
    }

    @Test
    fun `deleting a book takes its segments with it and leaves the rest`() = runTest {
        recorder(book).let {
            it.resumed()
            now += 3 * minute
            it.paused()
        }
        recorder(other).let {
            it.resumed()
            now += minute
            it.paused()
        }

        dao.deleteForBook(book)

        assertEquals(0, totalFor(book))
        assertEquals(minute, totalAll())
    }

    @Test
    fun `a checkpoint cannot drag the last page backwards`() = runTest {
        val id = dao.insert(
            ReadingSession(bookUrl = book, startedAt = 0, lastCheckpointAt = 10 * minute),
        )
        // A clock that went backwards between two page turns. The time
        // stands still rather than the record of it going into reverse.
        dao.checkpoint(id, totalMs = 0, atMillis = minute)
        assertEquals(10 * minute, dao.get(id)!!.lastCheckpointAt)
    }

    @Test
    fun `retrying an absolute checkpoint cannot count it twice`() = runTest {
        val id = dao.insert(ReadingSession(bookUrl = book, startedAt = 0, lastCheckpointAt = 0))

        dao.checkpoint(id, totalMs = minute, atMillis = minute)
        dao.checkpoint(id, totalMs = minute, atMillis = minute)

        assertEquals(minute, dao.get(id)!!.durationMs)
    }

    @Test
    fun `a segment already closed is not closed again`() = runTest {
        val id = dao.insert(
            ReadingSession(bookUrl = book, startedAt = 0, lastCheckpointAt = 0),
        )
        dao.finish(id, totalMs = minute, atMillis = minute)
        dao.finish(id, totalMs = 5 * minute, atMillis = 10 * minute)

        val session = dao.get(id)!!
        assertEquals(minute, session.durationMs)
        assertEquals(minute, session.endedAt)
    }

    @Test
    fun `a backward wall clock cannot end a segment before it started`() = runTest {
        val id = dao.insert(
            ReadingSession(
                bookUrl = book,
                startedAt = 10 * minute,
                lastCheckpointAt = 10 * minute,
            ),
        )

        dao.finish(id, totalMs = minute, atMillis = 5 * minute)

        val session = dao.get(id)!!
        assertEquals(10 * minute, session.lastCheckpointAt)
        assertEquals(10 * minute, session.endedAt)
        assertEquals(minute, session.durationMs)
    }

    @Test
    fun `a periodic checkpoint protects a long page`() = runTest {
        val recorder = recorder()
        recorder.resumed()

        now += minute
        advanceTimeBy(minute)
        runCurrent()

        assertEquals(minute, dao.openSessions().single().durationMs)
    }

    @Test
    fun `closing survives the owner and settles the final minute`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += minute

        recorder.close().await()

        assertTrue(dao.openSessions().isEmpty())
        assertEquals(minute, totalFor(book))
    }

    @Test
    fun `manager recovers once and does not close a new recorder`() = runTest {
        dao.insert(
            ReadingSession(
                bookUrl = "interrupted",
                startedAt = 0,
                lastCheckpointAt = minute,
                durationMs = minute,
            ),
        )
        val manager = ReadingSessionManager(
            dao = dao,
            scope = backgroundScope,
            wallNow = { now },
            elapsedNow = { now },
            checkpointIntervalMs = minute,
        )
        val current = manager.recorder(book)
        current.resumed()

        val second = manager.recorder(other)
        second.awaitIdle()

        assertEquals(listOf(book), dao.openSessions().map { it.bookUrl })
    }
}
