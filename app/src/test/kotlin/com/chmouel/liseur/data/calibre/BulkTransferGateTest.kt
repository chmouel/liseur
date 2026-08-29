package com.chmouel.liseur.data.calibre

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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
        val entered = Channel<Unit>(capacity = Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()

        // Five callers all ask for a slot at once; only two should ever
        // be inside the block together, the rest waiting on the
        // semaphore.
        val jobs = (1..5).map {
            async {
                gate.withSlot {
                    val now = concurrent.incrementAndGet()
                    peak.updateAndGet { prev -> maxOf(prev, now) }
                    entered.trySend(Unit)
                    release.await()
                    concurrent.decrementAndGet()
                }
            }
        }

        // Wait, through a real suspension rather than a busy loop, for
        // exactly as many callers as the gate should ever admit. A busy
        // `while (condition) yield()` spins the test's virtual clock in
        // place: if the gate were ever broken and never let two through,
        // that loop would never idle long enough for `withTimeout`'s
        // virtual-time deadline to fire, hanging the test instead of
        // failing it. Receiving from a channel suspends for real, so a
        // broken gate times out here instead.
        withTimeout(5_000) {
            entered.receive()
            entered.receive()
        }
        assertEquals(2, concurrent.get())

        // Give the remaining three every chance to sneak past the cap
        // while the first two are still held open.
        yield()
        assertEquals("a third caller got in while two were still running", 2, concurrent.get())
        assertEquals(2, peak.get())

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

    @Test
    fun `refuses a gate that could never let anything through`() {
        assertThrows(IllegalArgumentException::class.java) { BulkTransferGate(maxConcurrent = 0) }
        assertThrows(IllegalArgumentException::class.java) { BulkTransferGate(maxConcurrent = -1) }
    }

    private fun assertThrows(expected: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (expected.isInstance(e)) return
            throw e
        }
        throw AssertionError("Expected ${expected.simpleName} but nothing was thrown")
    }
}


