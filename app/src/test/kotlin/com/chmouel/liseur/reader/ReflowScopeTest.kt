package com.chmouel.liseur.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowScopeTest {

    @Test
    fun `the scope is open only for the duration of the block`() = runTest {
        val scope = ReflowScope()
        assertFalse(scope.active)
        val seen = scope.within { scope.active }
        assertTrue(seen)
        assertFalse(scope.active)
    }

    @Test
    fun `a throwing block still closes the scope`() = runTest {
        val scope = ReflowScope()
        var thrown = false
        try {
            scope.within { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
        assertFalse(scope.active)
    }

    @Test
    fun `cancelling the caller closes the scope`() = runTest {
        val scope = ReflowScope()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            scope.within {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        assertTrue(scope.active)
        job.cancelAndJoin()
        assertFalse(scope.active)
    }

    @Test
    fun `two reflows never overlap`() = runTest {
        val scope = ReflowScope()
        var inFlight = 0
        var peak = 0
        val work: suspend () -> Unit = {
            scope.within {
                inFlight++
                peak = maxOf(peak, inFlight)
                repeat(4) { yield() }
                inFlight--
            }
        }
        val a = async { work() }
        val b = async { work() }
        a.await()
        b.await()
        assertEquals(1, peak)
        assertFalse(scope.active)
    }
}
