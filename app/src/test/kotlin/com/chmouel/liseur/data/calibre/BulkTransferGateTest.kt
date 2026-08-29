package com.chmouel.liseur.data.calibre

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How many bulk-download transfers a [BulkTransferGate] lets run at
 * once.
 *
 * Reported against #89: a "download everything" run opened as many
 * connections to a self-hosted calibre-web instance as WorkManager was
 * willing to start workers, and a modest server answered a handful of
 * large requests in parallel by dropping some of them ("broken pipe" in
 * its own logs). The gate is the fix -- whatever WorkManager starts,
 * only a small, fixed number of transfers are ever pulling bytes at the
 * same time.
 */
class BulkTransferGateTest {

    @Test
    fun `never lets more than the limit run at once`() = runTest {
        val gate = BulkTransferGate(maxConcurrent = 2)
        val concurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        // Five callers all ask for a slot at once; only two should be
        // inside the block together, the rest waiting on the semaphore.
        val jobs = (1..5).map {
            async {
                gate.withSlot {
                    val now = concurrent.incrementAndGet()
                    peak.updateAndGet { prev -> maxOf(prev, now) }
                    release.await()
                    concurrent.decrementAndGet()
                }
            }
        }
        // Let every job reach the semaphore before checking the peak.
        repeat(5) { yield() }
        assertTrue("no more than 2 should run at once, was ${peak.get()}", peak.get() <= 2)

        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(0, concurrent.get())
    }

    @Test
    fun `lets every caller through eventually`() = runTest {
        val gate = BulkTransferGate(maxConcurrent = 1)
        val completed = AtomicInteger(0)

        val jobs = (1..4).map {
            async { gate.withSlot { completed.incrementAndGet() } }
        }
        jobs.forEach { it.await() }

        assertEquals(4, completed.get())
    }
}
