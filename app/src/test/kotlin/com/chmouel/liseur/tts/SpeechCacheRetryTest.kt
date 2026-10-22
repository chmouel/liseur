package com.chmouel.liseur.tts

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechCacheRetryTest {
    @Test fun temporaryFailuresRetrySynthesisOnly() = runTest {
        var prepared = 0
        var attempts = 0
        val cache = SpeechCache(backgroundScope, {
            if (++attempts < 3) throw SpeechError.Network(IOException())
            SpeechAudio(byteArrayOf(1, 2))
        }, prepare = { _, _ -> prepared++; listOf("translated") }, nowMillis = { testScheduler.currentTime })
        val request = cache.claim("original")
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, attempts)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, request.await().pcm.size)
        assertEquals(1, prepared)
        assertEquals(3, attempts)
    }

    @Test fun retriesReleasePermitAndKeepCapturedSynthesizer() = runTest {
        var oldCalls = 0
        var newCalls = 0
        val cache = SpeechCache(backgroundScope, {
            if (++oldCalls == 1) throw SpeechError.Service(503)
            SpeechAudio(byteArrayOf(1, 2))
        }, maxConcurrent = 1, nowMillis = { testScheduler.currentTime })
        val claimed = cache.claim("first")
        runCurrent()
        cache.swap { newCalls++; SpeechAudio(byteArrayOf(3, 4)) }
        val next = cache.claim("second")
        runCurrent()
        assertTrue(next.isCompleted)
        assertEquals(1, newCalls)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, oldCalls)
        assertEquals(1, claimed.await().pcm[0].toInt())
    }

    @Test fun directionChangesInvalidateAudioWithTheSameVoice() = runTest {
        var calls = 0
        val synthesize: suspend (String) -> SpeechAudio = { calls++; SpeechAudio(byteArrayOf(1, 2)) }
        val cache = SpeechCache(backgroundScope, synthesize, configurationIdentity = "voice\u0000default")
        cache.prefetch("same sentence")
        runCurrent()
        cache.swap(synthesize, "voice\u0000quiet")
        cache.claim("same sentence").await()
        assertEquals(2, calls)
    }

    @Test fun speculativeAndClaimedCancellationStopRetryWaits() = runTest {
        var calls = 0
        val cache = SpeechCache(backgroundScope, { calls++; throw SpeechError.Service(503) }, nowMillis = { testScheduler.currentTime })
        cache.prefetch("speculative")
        val claimed = cache.claim("claimed")
        runCurrent()
        cache.restart()
        claimed.cancel()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun budgetIsCheckedAfterWaitingForPermit() = runTest {
        var attempts = 0
        val blocking = CompletableDeferred<Unit>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cache = SpeechCache(scope, { text ->
            if (text == "blocked") blocking.await()
            else { attempts++; throw SpeechError.Service(503) }
            SpeechAudio(byteArrayOf(1, 2))
        }, maxConcurrent = 1, nowMillis = { testScheduler.currentTime })
        val retried = cache.claim("retry")
        runCurrent()
        cache.claim("blocked")
        advanceTimeBy(31_000); runCurrent()
        blocking.complete(Unit)
        runCurrent()
        assertTrue(retried.isCompleted)
        try { retried.await(); fail("Expected exhausted budget") } catch (_: SpeechError.Service) { }
        assertEquals(1, attempts)
        scope.cancel()
    }

    @Test fun permanentErrorsAndExhaustionKeepFailurePath() = runTest {
        val errors = listOf(SpeechError.Service(400), SpeechError.Service(501), SpeechError.RateLimited(429),
            SpeechError.InvalidKey(), SpeechError.InvalidVoice(), SpeechError.InvalidResponse("bad"),
            SpeechError.LocalNetworkBlocked(), SpeechError.TermsRequired(403), SpeechError.NoSpeech(404), SpeechError.Service(503))
        for (error in errors) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var attempts = 0
            val cache = SpeechCache(scope, { attempts++; throw error }, nowMillis = { testScheduler.currentTime })
            val result = cache.claim("sentence")
            advanceTimeBy(4_001); runCurrent()
            try { result.await(); fail("Expected failure") } catch (caught: SpeechError) { assertSame(error, caught) }
            assertEquals(if (error is SpeechError.Service && error.code == 503) 3 else 1, attempts)
            scope.cancel()
        }
    }
}
