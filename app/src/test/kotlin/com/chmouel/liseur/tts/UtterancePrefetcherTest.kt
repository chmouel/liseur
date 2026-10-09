package com.chmouel.liseur.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UtterancePrefetcherTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private class FakeSynth {
        val calls = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val replies = mutableMapOf<String, CompletableDeferred<SpeechAudio>>()
        var active = 0
        var maxActive = 0

        fun reply(text: String) = replies.getOrPut(text) { CompletableDeferred() }

        suspend fun synthesize(text: String): SpeechAudio {
            calls += text
            active++
            maxActive = maxOf(maxActive, active)
            try {
                return reply(text).await()
            } catch (e: CancellationException) {
                cancelled += text
                throw e
            } finally {
                active--
            }
        }
    }

    private fun audio() = SpeechAudio(ByteArray(4))

    private fun cursor(vararg items: Pair<String, String?>) = object : UtteranceCursor {
        val rest = ArrayDeque(items.map { UtteranceText(it.first, it.second) })
        override suspend fun next() = rest.removeFirstOrNull()
    }

    private fun cursor(vararg texts: String) = cursor(*texts.map { it to null }.toTypedArray())

    private fun TestScope.setUp(
        synth: FakeSynth,
        cursors: Map<String, () -> UtteranceCursor>,
    ): Pair<SpeechCache, UtterancePrefetcher<String>> {
        // Not backgroundScope: advanceUntilIdle() does not wait for it.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        val cache = SpeechCache(scope, synth::synthesize)
        return cache to UtterancePrefetcher(scope, cache, { cursors.getValue(it)() })
    }

    @Test
    fun readsAheadTwoAtATimeAndSlidesOn() = runTest {
        val synth = FakeSynth()
        val (cache, prefetcher) = setUp(synth, mapOf("p1" to { cursor("A", "B", "C", "D", "E", "F") }))

        prefetcher.onUtterance("A", null, "p1")
        advanceUntilIdle()
        assertEquals(listOf("B", "C"), synth.calls)

        synth.reply("B").complete(audio())
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D"), synth.calls)

        prefetcher.onUtterance("B", null, "p1")
        assertEquals(audio().pcm.size, cache.claim("B").await().pcm.size)
        synth.reply("C").complete(audio())
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D", "E"), synth.calls)
        assertEquals(1, prefetcher.restarts)
        assertEquals(2, synth.maxActive)
    }

    @Test
    fun aNewVoiceDropsWhatTheOldOneFetched() = runTest {
        val old = FakeSynth()
        val new = FakeSynth()
        val (cache, prefetcher) = setUp(old, mapOf("p1" to { cursor("A", "B", "C") }))
        prefetcher.onUtterance("A", null, "p1")
        advanceUntilIdle()
        old.reply("B").complete(audio())
        advanceUntilIdle()

        cache.swap(new::synthesize)
        val taken = CoroutineScope(StandardTestDispatcher(testScheduler)).launch { cache.claim("B").await() }
        advanceUntilIdle()
        assertEquals(listOf("C"), old.cancelled)
        assertEquals(listOf("B"), new.calls)
        new.reply("B").complete(audio())
        advanceUntilIdle()
        assertTrue(taken.isCompleted)
    }

    @Test
    fun aJumpDropsTheSpeculativeWork() = runTest {
        val synth = FakeSynth()
        val (_, prefetcher) = setUp(
            synth,
            mapOf("p1" to { cursor("A", "B", "C", "D") }, "p9" to { cursor("X", "Y", "Z") }),
        )
        prefetcher.onUtterance("A", null, "p1")
        advanceUntilIdle()

        prefetcher.onUtterance("X", null, "p9")
        advanceUntilIdle()
        assertEquals(listOf("B", "C"), synth.cancelled)
        assertEquals(listOf("B", "C", "Y", "Z"), synth.calls)
        assertEquals(2, prefetcher.restarts)
    }

    @Test
    fun aRepeatedSentenceIsFoundByTheTextBeforeIt() = runTest {
        val synth = FakeSynth()
        val (_, prefetcher) = setUp(
            synth,
            mapOf("p" to { cursor("Again." to "x", "First." to "x Again.", "Again." to "y", "Second." to "y Again.") }),
        )
        prefetcher.onUtterance("Again.", "y", "p")
        advanceUntilIdle()
        assertEquals(listOf("Second."), synth.calls)
    }

    @Test
    fun skippingBackToAnEarlierRepeatedSentenceStartsAgainFromThere() = runTest {
        val synth = FakeSynth()
        val (_, prefetcher) = setUp(
            synth,
            mapOf("p" to { cursor("Again." to "a", "B." to "a Again.", "Again." to "b", "C." to "b Again.") }),
        )
        prefetcher.onUtterance("Again.", "a", "p")
        advanceUntilIdle()
        prefetcher.onUtterance("B.", "a Again.", "p")
        advanceUntilIdle()
        assertEquals(1, prefetcher.restarts)

        prefetcher.onUtterance("Again.", "a", "p")
        advanceUntilIdle()
        assertEquals(2, prefetcher.restarts)
    }

    @Test
    fun audioThatComesBackAtOnceIsKept() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        val cache = SpeechCache(scope, { audio() })
        cache.prefetch("A")
        advanceUntilIdle()
        assertEquals(4L, cache.cachedBytes)
        cache.claim("A").await()
        assertEquals(1, cache.requestsMade)
    }

    @Test
    fun takingAPrefetchedSentenceDoesNotAskAgain() = runTest {
        val synth = FakeSynth()
        val (cache, prefetcher) = setUp(synth, mapOf("p" to { cursor("A", "B") }))
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        synth.reply("B").complete(audio())
        cache.claim("B").await()
        assertEquals(listOf("B"), synth.calls)
        assertEquals(1, cache.requestsMade)
    }

    @Test
    fun audioArrivingOverTheBudgetIsDroppedAndAskedForAgain() = runTest {
        val synth = FakeSynth()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        val cache = SpeechCache(scope, synth::synthesize, maxBytes = 6)
        cache.prefetch("A")
        cache.prefetch("B")
        synth.reply("A").complete(audio())
        synth.reply("B").complete(audio())
        advanceUntilIdle()
        assertEquals(4L, cache.cachedBytes)

        cache.claim("B").await()
        assertEquals(listOf("A", "B", "B"), synth.calls)
    }

    @Test
    fun aClaimIsTheCallersToCancel() = runTest {
        val synth = FakeSynth()
        val (cache, _) = setUp(synth, emptyMap())
        val claimed = cache.claim("Z")
        advanceUntilIdle()
        cache.restart()
        advanceUntilIdle()
        assertTrue(synth.cancelled.isEmpty())
        claimed.cancel()
        advanceUntilIdle()
        assertEquals(listOf("Z"), synth.cancelled)
    }

    @Test
    fun aQuotaErrorStopsReadingAheadUntilTheNextJump() = runTest {
        val synth = FakeSynth()
        val (cache, prefetcher) = setUp(
            synth,
            mapOf("p" to { cursor("A", "B", "C", "D", "E") }, "q" to { cursor("Q", "R") }),
        )
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        synth.reply("B").completeExceptionally(SpeechError.RateLimited(429))
        advanceUntilIdle()
        assertTrue(cache.halted)
        // D was waiting for a permit and still went; nothing new goes out.
        prefetcher.onUtterance("B", null, "p")
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D"), synth.calls)

        prefetcher.onUtterance("Q", null, "q")
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D", "R"), synth.calls)
    }

    @Test
    fun unacceptedTermsStopReadingAhead() = runTest {
        val synth = FakeSynth()
        val (cache, prefetcher) = setUp(synth, mapOf("p" to { cursor("A", "B", "C", "D", "E") }))
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        synth.reply("B").completeExceptionally(SpeechError.TermsRequired(400))
        advanceUntilIdle()
        assertTrue(cache.halted)
        prefetcher.onUtterance("B", null, "p")
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D"), synth.calls)
    }

    @Test
    fun suspendingCancelsAndStaysQuiet() = runTest {
        val synth = FakeSynth()
        val (_, prefetcher) = setUp(synth, mapOf("p" to { cursor("A", "B", "C") }))
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        prefetcher.suspend()
        prefetcher.onUtterance("B", null, "p")
        advanceUntilIdle()
        assertEquals(listOf("B", "C"), synth.cancelled)
        assertEquals(listOf("B", "C"), synth.calls)
    }

    @Test
    fun pausingKeepsWhatWasFetchedAndPlayingOnReadsAheadFromThere() = runTest {
        val synth = FakeSynth()
        val (cache, prefetcher) = setUp(synth, mapOf("p" to { cursor("A", "B", "C", "D", "E") }))
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        synth.reply("B").complete(audio())
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D"), synth.calls)

        prefetcher.pause()
        prefetcher.onUtterance("B", null, "p")
        advanceUntilIdle()
        prefetcher.resume()
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        assertTrue(synth.cancelled.isEmpty())
        assertEquals(listOf("B", "C", "D"), synth.calls)
        assertEquals(1, prefetcher.restarts)

        prefetcher.onUtterance("B", null, "p")
        assertEquals(audio().pcm.size, cache.claim("B").await().pcm.size)
        synth.reply("C").complete(audio())
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D", "E"), synth.calls)
        assertEquals(1, prefetcher.restarts)
    }

    @Test
    fun theSameSentenceInAnotherElementIsAJumpNotPlayingOn() = runTest {
        val synth = FakeSynth()
        val (_, prefetcher) = setUp(
            synth,
            mapOf("p" to { cursor("Chapter.", "B") }, "q" to { cursor("Chapter.", "Y") }),
        )
        prefetcher.onUtterance("Chapter.", null, "p")
        advanceUntilIdle()
        prefetcher.onUtterance("Chapter.", null, "q")
        advanceUntilIdle()
        assertEquals(2, prefetcher.restarts)
        assertEquals("Y", synth.calls.last())
    }
}
