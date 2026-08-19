package com.chmouel.liseur.data.calibre

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The record that says which bulk download is current.
 *
 * It exists because neither of the other two places the answer could
 * live can be trusted for it: WorkManager prunes finished work, taking
 * the closing counts with it, and a worker that stops a batch cancels
 * itself in the act.
 *
 * The order the record is written in is load-bearing, and got it wrong
 * once: it used to be opened *after* its work was enqueued, and every
 * worker that got off the mark in that window found no record for the
 * batch it named and stood down. Two thirds of a run were lost that way
 * on a fast connection. The batch is now opened first, with the
 * selection as a provisional total, and narrowed by [setTotal] once
 * membership is known.
 */
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BulkDownloadStoreTest {

    private lateinit var store: BulkDownloadStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        store = BulkDownloadStore(context)
    }

    @Test
    fun `a batch is readable the moment it is opened`() = runTest {
        store.start("batch-1", total = 32)

        val batch = store.current()
        assertEquals("batch-1", batch?.id)
        assertEquals(32, batch?.total)
        assertFalse(batch!!.settled)
        assertNull(batch.stopReason)
    }

    @Test
    fun `the total narrows to what was accepted`() = runTest {
        store.start("batch-1", total = 32)

        store.setTotal("batch-1", 30)

        assertEquals(30, store.current()?.total)
    }

    @Test
    fun `a stale total does not reach the batch that replaced it`() = runTest {
        store.start("batch-1", total = 32)
        store.start("batch-2", total = 8)

        store.setTotal("batch-1", 30)

        assertEquals(8, store.current()?.total)
    }

    @Test
    fun `a settled batch keeps the total it settled with`() = runTest {
        store.start("batch-1", total = 32)
        store.settle("batch-1", done = 20, failed = 12)

        store.setTotal("batch-1", 30)

        assertEquals(32, store.current()?.total)
    }

    @Test
    fun `the first reason a batch stops is the one that is kept`() = runTest {
        store.start("batch-1", total = 32)

        assertTrue(store.recordStopReason("batch-1", BulkStopReason.OUT_OF_SPACE))
        assertFalse(store.recordStopReason("batch-1", BulkStopReason.CANCELLED))

        assertEquals(BulkStopReason.OUT_OF_SPACE, store.current()?.stopReason)
    }

    @Test
    fun `a reason for a batch that is not current is ignored`() = runTest {
        store.start("batch-1", total = 32)

        assertFalse(store.recordStopReason("batch-0", BulkStopReason.CANCELLED))

        assertNull(store.current()?.stopReason)
    }

    @Test
    fun `counts outlive the work they were counted from`() = runTest {
        store.start("batch-1", total = 32)
        store.settle("batch-1", done = 20, failed = 12)

        val batch = store.current()
        assertTrue(batch!!.settled)
        assertEquals(20, batch.done)
        assertEquals(12, batch.failed)
    }

    @Test
    fun `a new batch does not inherit the last one's counts`() = runTest {
        store.start("batch-1", total = 32)
        store.recordStopReason("batch-1", BulkStopReason.CANCELLED)
        store.settle("batch-1", done = 20, failed = 12)

        store.start("batch-2", total = 4)

        val batch = store.current()
        assertEquals("batch-2", batch?.id)
        assertEquals(4, batch?.total)
        assertEquals(0, batch?.done)
        assertEquals(0, batch?.failed)
        assertFalse(batch!!.settled)
        assertNull(batch.stopReason)
    }

    @Test
    fun `settling a batch that is no longer current changes nothing`() = runTest {
        store.start("batch-1", total = 32)
        store.start("batch-2", total = 4)

        store.settle("batch-1", done = 20, failed = 12)

        val batch = store.current()
        assertFalse(batch!!.settled)
        assertEquals(0, batch.done)
    }

    @Test
    fun `a dismissed batch leaves nothing behind`() = runTest {
        store.start("batch-1", total = 32)
        store.settle("batch-1", done = 32, failed = 0)

        store.clear()

        assertNull(store.current())
    }
}
