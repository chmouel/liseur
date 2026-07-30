package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reading-position state machine, exercised against the real SQL.
 *
 * Every transition here is one a page turn can land in the middle of,
 * and every mistake loses a position silently: the row still looks
 * settled, it is just settled on the wrong place. That cannot be checked
 * by reading the code, so it is checked here.
 */
// A plain Application, because the real one schedules work and syncs on
// start, none of which this is about. The SDK is pinned because
// Robolectric has no image of the one the app targets, and nothing here
// depends on the difference: the SQL under test is Room's own.
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadingProgressDaoTest {

    private lateinit var db: LiseurDatabase
    private lateinit var dao: ReadingProgressDao

    private val account = "https://books.example|ada|7"
    private val other = "https://books.example|grace|9"
    private val book = "calibre:uuid-1"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiseurDatabase::class.java,
        ).build()
        dao = db.readingProgressDao()
    }

    @After
    fun close() = db.close()

    private suspend fun read(at: Double, updatedAt: Long = 1_000) = dao.recordLocal(
        bookUrl = book,
        locatorJson = """{"at":$at}""",
        progression = at,
        readingSpeed = null,
        status = "Reading",
        updatedAt = updatedAt,
    )

    private suspend fun row() = requireNotNull(dao.get(book))

    // -- Revisions --------------------------------------------------------

    @Test
    fun `reading makes a row dirty`() = runTest {
        read(0.1)
        val row = row()
        assertEquals(1L, row.localRevision)
        assertEquals(0L, row.ackedRevision)
        assertTrue(row.isDirty)
    }

    @Test
    fun `every page turn moves the revision on`() = runTest {
        read(0.1)
        read(0.2)
        read(0.3)
        assertEquals(3L, row().localRevision)
    }

    @Test
    fun `dirtiness survives a clock that goes backwards`() = runTest {
        // Dirtiness is a counter, not a comparison of timestamps, exactly
        // so that a phone correcting its clock cannot make unsent reading
        // look as though it had already been sent.
        read(0.1)
        dao.ackPush(book, 1, 0.1, "Reading", account, now = 5_000)
        assertFalse(row().isDirty)

        read(0.2, updatedAt = 1)
        assertTrue(row().isDirty)
    }

    @Test
    fun `an acknowledgement for a revision that was never sent is refused`() = runTest {
        read(0.1)
        read(0.2)
        // The server was told about revision 1; two page turns happened.
        // Acknowledging 1 must not settle the row at 2.
        dao.ackPush(book, 1, 0.1, "Reading", account, now = 5_000)
        val row = row()
        assertEquals(2L, row.localRevision)
        assertEquals(0L, row.ackedRevision)
        assertTrue(row.isDirty)
    }

    // -- Atomic transitions ----------------------------------------------

    @Test
    fun `a push records exactly what was sent as the baseline`() = runTest {
        read(0.4)
        dao.ackPush(book, 1, 0.4, "Reading", account, now = 5_000)
        val row = row()
        assertEquals(0.4, row.agreedProgression!!, 1e-9)
        assertEquals(account, row.agreedAccount)
        assertEquals(1L, row.ackedRevision)
        assertFalse(row.isDirty)
    }

    @Test
    fun `a page turn during a push leaves the row dirty but the baseline right`() = runTest {
        read(0.4)
        // The request is in flight at revision 1; the reader turns a page.
        read(0.5)
        dao.ackPush(book, 1, 0.4, "Reading", account, now = 5_000)

        val row = row()
        // The server does hold 0.4, so that is the baseline...
        assertEquals(0.4, row.agreedProgression!!, 1e-9)
        // ...but it has never heard about 0.5, so the row still owes it.
        assertEquals(0.5, row.totalProgression!!, 1e-9)
        assertTrue(row.isDirty)
    }

    @Test
    fun `a pull is abandoned if a page was turned while it was decided`() = runTest {
        read(0.2)
        dao.persistPending(book, 0.9, "Reading", 4_000, account, now = 4_000)
        // Reconciliation looked at revision 1; the reader turns a page.
        read(0.25)

        val applied = dao.applyPull(
            bookUrl = book,
            expectedRevision = 1,
            progression = 0.9,
            status = "Reading",
            account = account,
            remoteUpdatedAt = 4_000,
            now = 5_000,
        )

        assertFalse(applied)
        val row = row()
        // The page turn survives untouched...
        assertEquals(0.25, row.totalProgression!!, 1e-9)
        // ...and the server's position is kept, so it becomes something
        // to ask about rather than something silently thrown away.
        assertTrue(row.hasPending)
        assertEquals(0.9, row.pendingProgression!!, 1e-9)
    }

    @Test
    fun `a pull that is not raced applies and clears the pending state`() = runTest {
        read(0.2)
        dao.persistPending(book, 0.9, "Reading", 4_000, account, now = 4_000)

        val applied = dao.applyPull(book, 1, 0.9, "Reading", account, 4_000, now = 5_000)

        assertTrue(applied)
        val row = row()
        assertEquals(0.9, row.totalProgression!!, 1e-9)
        assertEquals(0.9, row.agreedProgression!!, 1e-9)
        assertFalse(row.hasPending)
        assertFalse(row.isDirty)
    }

    @Test
    fun `a pull carrying an exact place reopens the book there`() = runTest {
        read(0.2)
        dao.persistPending(book, 0.9, "Reading", 4_000, account, now = 4_000)

        val applied = dao.applyPull(
            bookUrl = book,
            expectedRevision = 1,
            progression = 0.9,
            status = "Reading",
            account = account,
            remoteUpdatedAt = 4_000,
            now = 5_000,
            locatorJson = """{"href":"OEBPS/ch7.xhtml"}""",
        )

        assertTrue(applied)
        // A percentage alone can only reopen a book roughly where the
        // other device was; the locator is what puts it on the right word.
        assertEquals("""{"href":"OEBPS/ch7.xhtml"}""", row().locatorJson)
    }

    @Test
    fun `a pull with no place to offer leaves the one this device had`() = runTest {
        read(0.2)
        dao.persistPending(book, 0.9, "Reading", 4_000, account, now = 4_000)

        assertTrue(dao.applyPull(book, 1, 0.9, "Reading", account, 4_000, now = 5_000))

        // Servers that only carry a percentage pass nothing, and
        // overwriting a real locator with an empty one would lose the
        // only precise thing on the row.
        assertEquals("""{"at":0.2}""", row().locatorJson)
    }

    // -- Durability -------------------------------------------------------

    @Test
    fun `a state the server reported is on disk before anything is decided`() = runTest {
        // The whole reason persistPending exists: the sync token moves
        // past a change once, so if the process dies between reading the
        // feed and acting on it, this row is the only copy.
        dao.persistPending(book, 0.7, "Reading", 4_000, account, now = 4_000)

        val row = row()
        assertEquals(0.7, row.pendingProgression!!, 1e-9)
        assertEquals(account, row.pendingAccount)
        assertTrue(row.hasPending)
    }

    @Test
    fun `a landed state can drive a pull with no feed at all`() = runTest {
        // Crash recovery. The token already moved, so the next feed is
        // empty; the pending row alone has to be enough.
        dao.persistPending(book, 0.7, "Reading", 4_000, account, now = 4_000)
        val recovered = requireNotNull(dao.pendingFor(account).singleOrNull())
        assertEquals(book, recovered.bookUrl)

        assertTrue(dao.applyPull(book, 0, 0.7, "Reading", account, 4_000, now = 5_000))
        assertTrue(dao.pendingFor(account).isEmpty())
    }

    @Test
    fun `a conflict is still there after the process dies`() = runTest {
        read(0.3)
        dao.persistPending(book, 0.8, "Reading", 4_000, account, now = 4_000)
        // Nothing resolves it; the row is simply left as it is.
        val survivors = dao.pendingFor(account)
        assertEquals(1, survivors.size)
        assertEquals(0.3, survivors.single().totalProgression!!, 1e-9)
        assertEquals(0.8, survivors.single().pendingProgression!!, 1e-9)
    }

    @Test
    fun `a pending state belonging to another account is not this one's business`() = runTest {
        dao.persistPending(book, 0.7, "Reading", 4_000, other, now = 4_000)
        assertTrue(dao.pendingFor(account).isEmpty())
        assertEquals(1, dao.pendingFor(other).size)
    }

    // -- Account switching ------------------------------------------------

    @Test
    fun `switching account leaves the reading but drops the baseline`() = runTest {
        read(0.6)
        dao.ackPush(book, 1, 0.6, "Reading", account, now = 5_000)
        dao.persistPending(book, 0.9, "Reading", 6_000, account, now = 6_000)

        dao.retireAccountState()

        val row = row()
        assertEquals(0.6, row.totalProgression!!, 1e-9)
        // A stranger's baseline is worse than none: it would make this
        // device diff its reading against someone else's.
        assertNull(row.agreedAccount)
        assertNull(row.agreedProgression)
        // And their pending state must never be applied under the new one.
        assertFalse(row.hasPending)
    }

    @Test
    fun `switching account uploads nothing without being asked`() = runTest {
        read(0.6)
        // Deliberately dirty: never acknowledged by anyone.
        assertTrue(row().isDirty)

        dao.retireAccountState()

        // Still dirty would mean the previous reader's position landing
        // in the new account on the very next sync.
        assertFalse(row().isDirty)
    }

    @Test
    fun `who did the reading survives the switch`() = runTest {
        read(0.6)
        dao.ackPush(book, 1, 0.6, "Reading", account, now = 5_000)
        dao.retireAccountState()

        assertEquals(account, row().ownerAccount)
        assertEquals(1, dao.ownedByOther(other).size)
        assertTrue(dao.ownedByOther(account).isEmpty())
    }

    @Test
    fun `a book with no reading is not counted as owned by anyone`() = runTest {
        dao.persistPending(book, 0.7, "Reading", 4_000, account, now = 4_000)
        assertNull(row().totalProgression)
        assertTrue(dao.ownedByOther(other).isEmpty())
    }

    @Test
    fun `consent marks exactly the books that were chosen`() = runTest {
        val second = "calibre:uuid-2"
        read(0.6)
        dao.recordLocal(second, "{}", 0.2, null, "Reading", 1_000)
        dao.retireAccountState()
        assertFalse(row().isDirty)

        dao.markDirtyFor(listOf(book), other)

        assertTrue(row().isDirty)
        assertEquals(other, row().ownerAccount)
        assertFalse(requireNotNull(dao.get(second)).isDirty)
    }

    @Test
    fun `consent does not invent reading for a book that has none`() = runTest {
        dao.persistPending(book, 0.7, "Reading", 4_000, account, now = 4_000)
        assertEquals(0L, row().localRevision)

        dao.markDirtyFor(listOf(book), other)

        // acked_revision of local_revision - 1 would be -1 here, which
        // reads as dirty for ever over a book nobody has opened.
        assertEquals(0L, row().ackedRevision)
        assertFalse(row().isDirty)
    }

    // -- Settling without a push or a pull --------------------------------

    @Test
    fun `agreeing settles at whatever revision the row is on`() = runTest {
        read(0.5)
        read(0.5)
        dao.settleAgreed(book, 0.5, "Reading", account, now = 5_000)

        val row = row()
        assertEquals(2L, row.ackedRevision)
        assertFalse(row.isDirty)
        assertEquals(account, row.agreedAccount)
        assertNotNull(row.syncedAt)
    }
}
