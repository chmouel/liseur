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
        assertEquals(audio().pcm.size, cache.take("B").pcm.size)
        synth.reply("C").complete(audio())
        advanceUntilIdle()
        assertEquals(listOf("B", "C", "D", "E"), synth.calls)
        assertEquals(1, prefetcher.restarts)
        assertEquals(2, synth.maxActive)
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
    fun takingAPrefetchedSentenceDoesNotAskAgain() = runTest {
        val synth = FakeSynth()
        val (cache, prefetcher) = setUp(synth, mapOf("p" to { cursor("A", "B") }))
        prefetcher.onUtterance("A", null, "p")
        advanceUntilIdle()
        synth.reply("B").complete(audio())
        cache.take("B")
        assertEquals(listOf("B"), synth.calls)
        assertEquals(1, cache.requestsMade)
    }

    @Test
    fun abandoningATakeCancelsItsRequest() = runTest {
        val synth = FakeSynth()
        val (cache, _) = setUp(synth, emptyMap())
        val waiting = launch { cache.take("Z") }
        advanceUntilIdle()
        waiting.cancel()
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
}
