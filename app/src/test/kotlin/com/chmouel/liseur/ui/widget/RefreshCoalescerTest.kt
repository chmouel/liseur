package com.chmouel.liseur.ui.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoalescerTest {

    private fun TestScope.coalescer(redraw: suspend () -> Unit): RefreshCoalescer =
        RefreshCoalescer(
            scope = backgroundScope,
            quietMs = 3_000,
            maxWaitMs = 15_000,
            now = { testScheduler.currentTime },
            redraw = redraw,
        )

    @Test
    fun `a burst gives one redraw once it goes quiet`() = runTest {
        var redraws = 0
        val coalescer = coalescer { redraws++ }
        runCurrent()
        repeat(10) {
            coalescer.request()
            advanceTimeBy(100)
        }
        assertEquals(0, redraws)
        advanceTimeBy(3_001)
        assertEquals(1, redraws)
        advanceTimeBy(60_000)
        assertEquals(1, redraws)
    }

    @Test
    fun `steady requests still redraw at the ceiling`() = runTest {
        var redraws = 0
        val coalescer = coalescer { redraws++ }
        runCurrent()
        // A page turn every two seconds never leaves three quiet seconds.
        repeat(30) {
            coalescer.request()
            advanceTimeBy(2_000)
        }
        // Sixty seconds of reading: redraws at 15, 31 and 47 seconds.
        assertEquals(3, redraws)
        // Then the last page turn's, once reading stops.
        advanceTimeBy(1_001)
        assertEquals(4, redraws)
    }

    @Test
    fun `requests during a redraw give exactly one more`() = runTest {
        var redraws = 0
        val release = CompletableDeferred<Unit>()
        val coalescer = coalescer {
            redraws++
            if (redraws == 1) release.await()
        }
        runCurrent()
        coalescer.request()
        advanceTimeBy(3_001)
        assertEquals(1, redraws)
        repeat(5) { coalescer.request() }
        release.complete(Unit)
        advanceTimeBy(60_000)
        assertEquals(2, redraws)
    }

    @Test
    fun `a failed redraw does not stop later ones`() = runTest {
        var redraws = 0
        val coalescer = coalescer {
            redraws++
            if (redraws == 1) error("boom")
        }
        runCurrent()
        coalescer.request()
        advanceTimeBy(3_001)
        coalescer.request()
        advanceTimeBy(3_001)
        assertEquals(2, redraws)
    }
}
