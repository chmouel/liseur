package com.chmouel.liseur.translate

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WholeBookPreparationTest {
    private fun preview(retry: Boolean = false) = WholeBookPreview(
        "book", "Book", "en", "fr", "identity", 400, 0, 0, 2_000, null, null, "fingerprint",
        retryFailures = retry,
    )

    @Test
    fun `leaving and reopening observes one continuing check and reuses its result`() = runTest {
        val finish = CompletableDeferred<Unit>()
        var scans = 0
        val ready = preview()
        val progress = PreparationProgress(PreparationStage.SCANNING, 2, 5, 128)
        val preparation = WholeBookPreparation(backgroundScope, { _, report ->
            scans++
            report(progress)
            finish.await()
            ready
        }, { true })
        val observer = backgroundScope.launch { preparation.state.collect() }
        preparation.prepare()
        runCurrent()
        observer.cancelAndJoin()
        preparation.prepare()
        assertEquals(PreparationState.Checking(progress), preparation.state.value)
        assertEquals(1, scans)
        finish.complete(Unit)
        runCurrent()
        preparation.validate()
        runCurrent()
        assertEquals(1, scans)
        assertSame(ready, (preparation.state.value as PreparationState.Ready).preview)
    }

    @Test
    fun `explicit cancel permits a fresh check and late old work cannot overwrite it`() = runTest {
        val releaseOld = CompletableDeferred<Unit>()
        var scans = 0
        val preparation = WholeBookPreparation(backgroundScope, { retry, _ ->
            scans++
            if (!retry) withContext(NonCancellable) { releaseOld.await() }
            preview(retry)
        }, { true })
        preparation.prepare()
        runCurrent()
        preparation.cancel()
        assertEquals(PreparationState.Idle, preparation.state.value)
        preparation.prepare(retryFailures = true)
        runCurrent()
        val ready = preparation.state.value
        assertTrue((ready as PreparationState.Ready).preview.retryFailures)
        releaseOld.complete(Unit)
        runCurrent()
        assertEquals(2, scans)
        assertEquals(ready, preparation.state.value)
    }

    @Test
    fun `invalid preview is discarded without an automatic scan`() = runTest {
        var scans = 0
        val preparation = WholeBookPreparation(this, { _, _ -> scans++; preview() }, { false })
        preparation.prepare()
        advanceUntilIdle()
        preparation.validate()
        advanceUntilIdle()
        assertEquals(PreparationState.Idle, preparation.state.value)
        assertEquals(1, scans)
    }

    @Test
    fun `failure remains observable until explicitly retried`() = runTest {
        val failure = IOException("Cannot read book")
        var scans = 0
        val preparation = WholeBookPreparation(this, { _, _ ->
            if (++scans == 1) throw failure
            preview()
        }, { true })
        preparation.prepare()
        advanceUntilIdle()
        preparation.validate()
        assertSame(failure, (preparation.state.value as PreparationState.Failed).error)
        preparation.prepare()
        advanceUntilIdle()
        assertTrue(preparation.state.value is PreparationState.Ready)
        assertEquals(2, scans)
    }
}
