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
    fun `a segment remembers where in the book it happened`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += minute
        recorder.onPageTurned(0.20)
        now += minute
        recorder.onPageTurned(0.35)
        recorder.paused()

        val session = dao.observeAll().first().single()
        // The first page seen is where the stretch began; the last is
        // where it ended. Anything else would credit the reading to the
        // wrong part of the book.
        assertEquals(0.20, session.startProgression!!, 0.0001)
        assertEquals(0.35, session.endProgression!!, 0.0001)
    }

    @Test
    fun `closing at a page nobody turned still knows where it stopped`() = runTest {
        // The periodic checkpoint and the pause are not page turns and
        // are handed no locator, so the last one seen has to do.
        val recorder = recorder()
        recorder.resumed()
        recorder.onPageTurned(0.5)
        now += minute
        recorder.paused()

        val session = dao.observeAll().first().single()
        assertEquals(0.5, session.endProgression!!, 0.0001)
    }

    @Test
    fun `a segment in which no page ever turned says nothing about where`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        now += minute
        recorder.paused()

        val session = dao.observeAll().first().single()
        // Null rather than zero: a book opened and put down again did
        // not happen at the start of the book, it happened nowhere
        // anybody can name, and it is never uploaded.
        assertEquals(null, session.startProgression)
        assertTrue(dao.awaitingUpload(10).isEmpty())
    }

    @Test
    fun `a finished segment is offered once and then not again`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        recorder.onPageTurned(0.1)
        now += minute
        recorder.paused()

        val waiting = dao.awaitingUpload(10)
        assertEquals(1, waiting.size)
        dao.markUploaded(waiting.map { it.id }, now)

        assertTrue(dao.awaitingUpload(10).isEmpty())
    }

    @Test
    fun `an open segment is not offered for upload`() = runTest {
        val recorder = recorder()
        recorder.resumed()
        recorder.onPageTurned(0.1)
        recorder.awaitIdle()

        // Still being read. Its end is not known yet, and a session sent
        // now would have to be corrected, which the server will not
        // allow.
        assertTrue(dao.awaitingUpload(10).isEmpty())
    }

    @Test
    fun `a checkpoint cannot drag the last page backwards`() = runTest {
        val id = dao.insert(
            ReadingSession(bookUrl = book, startedAt = 0, lastCheckpointAt = 10 * minute),
        )
        // A clock that went backwards between two page turns. The time
        // stands still rather than the record of it going into reverse.
        dao.checkpoint(id, totalMs = 0, atMillis = minute, progression = null)
        assertEquals(10 * minute, dao.get(id)!!.lastCheckpointAt)
    }

    @Test
    fun `retrying an absolute checkpoint cannot count it twice`() = runTest {
        val id = dao.insert(ReadingSession(bookUrl = book, startedAt = 0, lastCheckpointAt = 0))

        dao.checkpoint(id, totalMs = minute, atMillis = minute, progression = null)
        dao.checkpoint(id, totalMs = minute, atMillis = minute, progression = null)

        assertEquals(minute, dao.get(id)!!.durationMs)
    }

    @Test
    fun `a segment already closed is not closed again`() = runTest {
        val id = dao.insert(
            ReadingSession(bookUrl = book, startedAt = 0, lastCheckpointAt = 0),
        )
        dao.finish(id, totalMs = minute, atMillis = minute, progression = null)
        dao.finish(id, totalMs = 5 * minute, atMillis = 10 * minute, progression = null)

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

        dao.finish(id, totalMs = minute, atMillis = 5 * minute, progression = null)

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
