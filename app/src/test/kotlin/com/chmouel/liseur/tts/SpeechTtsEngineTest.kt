package com.chmouel.liseur.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The engine's promises to Readium's facade: every listener call on the
 * main thread, exactly one terminal call per request, whatever thread the
 * network and the audio finish on.
 */
@OptIn(ExperimentalReadiumApi::class)
class SpeechTtsEngineTest {
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "main") }
    private val main = mainExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    private val requests = LinkedBlockingQueue<Pair<String, CompletableDeferred<SpeechAudio>>>()
    private val cancelledRequests = LinkedBlockingQueue<String>()
    private val cache = SpeechCache(scope, { text ->
        val reply = CompletableDeferred<SpeechAudio>()
        requests.put(text to reply)
        try {
            reply.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledRequests.put(text)
            throw e
        }
    })

    private val plays = LinkedBlockingQueue<CompletableDeferred<Unit>>()
    private val playedFrom = LinkedBlockingQueue<Int>()
    private val output = object : PcmOutput {
        @Volatile var halts = 0

        /** What a halt reports heard of the clip playing. */
        @Volatile var heard: Int? = null

        override suspend fun play(pcm: ByteArray, fromFrame: Int) {
            playedFrom.put(fromFrame)
            val done = CompletableDeferred<Unit>()
            plays.put(done)
            done.await()
        }

        override fun halt(): Int? {
            halts++
            return heard.also { heard = null }
        }

        override fun release() {}
    }

    private val events = LinkedBlockingQueue<String>()
    private val waits = LinkedBlockingQueue<String>()

    @Volatile private var skipCount = 0
    private val engine = SpeechTtsEngine(
        scope = scope,
        cache = cache,
        output = output,
        voices = emptySet(),
        publicationLanguage = null,
        initialPreferences = SpeechTtsPreferences(),
        observer = object : SpeechObserver {
            override fun onWaiting(requestId: TtsEngine.RequestId, waiting: Boolean) =
                waits.put("${if (waiting) "waiting" else "done waiting"} ${requestId.value}")

            override val skips get() = skipCount
        },
    ).apply {
        setListener(object : TtsEngine.Listener<SpeechTtsEngine.Error> {
            fun record(event: String) = events.put("$event@${Thread.currentThread().name.substringBefore(" @")}")
            override fun onStart(requestId: TtsEngine.RequestId) = record("start ${requestId.value}")
            override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) = record("range")
            override fun onInterrupted(requestId: TtsEngine.RequestId) = record("interrupted ${requestId.value}")
            override fun onFlushed(requestId: TtsEngine.RequestId) = record("flushed ${requestId.value}")
            override fun onDone(requestId: TtsEngine.RequestId) = record("done ${requestId.value}")
            override fun onError(requestId: TtsEngine.RequestId, error: SpeechTtsEngine.Error) =
                record("error ${requestId.value} ${error::class.simpleName}")
        })
    }

    @After
    fun tearDown() {
        scope.cancel()
        mainExecutor.shutdownNow()
    }

    private fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }

    private fun speak(id: String, text: String) = onMain { engine.speak(TtsEngine.RequestId(id), text, null) }

    private fun nextEvent(): String? = events.poll(2, TimeUnit.SECONDS)

    private fun noMoreEvents() = assertNull(events.poll(200, TimeUnit.MILLISECONDS))

    private fun nextRequest() = requireNotNull(requests.poll(2, TimeUnit.SECONDS)) { "no request" }

    private fun nextPlay() = requireNotNull(plays.poll(2, TimeUnit.SECONDS)) { "no playback" }

    @Test
    fun speaksThenReportsDoneOnMain() {
        speak("1", "Hello there.")
        val (text, reply) = nextRequest()
        assertEquals("Hello there.", text)
        reply.complete(SpeechAudio(ByteArray(8)))
        assertEquals("start 1@main", nextEvent())
        nextPlay().complete(Unit)
        assertEquals("done 1@main", nextEvent())
        noMoreEvents()
    }

    @Test
    fun stopWhileFetchingInterruptsOnceAndKeepsTheRequestUntilClosed() {
        speak("1", "Hello.")
        nextRequest()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        assertNull(cancelledRequests.poll(200, TimeUnit.MILLISECONDS))
        onMain { engine.close() }
        assertEquals("Hello.", cancelledRequests.poll(2, TimeUnit.SECONDS))
        noMoreEvents()
        assertTrue(plays.isEmpty())
    }

    @Test
    fun speakingAStoppedSentenceAgainPlaysOnASecondBackWithoutFetchingIt() {
        speak("1", "Hello.")
        nextRequest().second.complete(SpeechAudio(ByteArray(SECONDS_3)))
        assertEquals("start 1@main", nextEvent())
        nextPlay()
        assertEquals(0, playedFrom.poll(2, TimeUnit.SECONDS))
        output.heard = 2 * SpeechAudio.SAMPLE_RATE
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())

        speak("2", "Hello.")
        assertEquals("start 2@main", nextEvent())
        nextPlay()
        assertEquals(SpeechAudio.SAMPLE_RATE, playedFrom.poll(2, TimeUnit.SECONDS))
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))

        // Stopped again at once: it does not keep walking back.
        output.heard = SpeechAudio.SAMPLE_RATE + 100
        onMain { engine.stop() }
        assertEquals("interrupted 2@main", nextEvent())
        speak("3", "Hello.")
        assertEquals("start 3@main", nextEvent())
        nextPlay().complete(Unit)
        assertEquals(SpeechAudio.SAMPLE_RATE, playedFrom.poll(2, TimeUnit.SECONDS))
        assertEquals("done 3@main", nextEvent())
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun speakingASentenceStoppedWhileFetchingWaitsForTheSameRequest() {
        speak("1", "Hello.")
        val (_, reply) = nextRequest()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        speak("2", "Hello.")
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
        reply.complete(SpeechAudio(ByteArray(8)))
        assertEquals("start 2@main", nextEvent())
        assertEquals(0, playedFrom.poll(2, TimeUnit.SECONDS))
        nextPlay().complete(Unit)
        assertEquals("done 2@main", nextEvent())
        assertTrue(cancelledRequests.isEmpty())
    }

    @Test
    fun aStopBeforeTheResumedSentenceGetsGoingStillKeepsItsRequest() {
        speak("1", "Hello.")
        val (_, reply) = nextRequest()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        onMain {
            engine.speak(TtsEngine.RequestId("2"), "Hello.", null)
            engine.stop()
        }
        assertEquals("interrupted 2@main", nextEvent())
        speak("3", "Hello.")
        assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
        reply.complete(SpeechAudio(ByteArray(8)))
        assertEquals("start 3@main", nextEvent())
        assertTrue(cancelledRequests.isEmpty())
    }

    @Test
    fun closingBeforeTheResumedSentenceGetsGoingCancelsItsRequest() {
        speak("1", "Hello.")
        nextRequest()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        onMain {
            engine.speak(TtsEngine.RequestId("2"), "Hello.", null)
            engine.close()
        }
        assertEquals("Hello.", cancelledRequests.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun anotherSentenceDropsTheStoppedOne() {
        speak("1", "Hello.")
        nextRequest()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        speak("2", "Other.")
        assertEquals("Hello.", cancelledRequests.poll(2, TimeUnit.SECONDS))
        assertEquals("Other.", nextRequest().first)
    }

    @Test
    fun theSameTextAfterASkipStartsOverAndIsFetchedAgain() {
        speak("1", "Yes.")
        nextRequest().second.complete(SpeechAudio(ByteArray(SECONDS_3)))
        assertEquals("start 1@main", nextEvent())
        nextPlay()
        assertEquals(0, playedFrom.poll(2, TimeUnit.SECONDS))
        output.heard = 2 * SpeechAudio.SAMPLE_RATE
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())

        skipCount++
        speak("2", "Yes.")
        nextRequest().second.complete(SpeechAudio(ByteArray(SECONDS_3)))
        assertEquals("start 2@main", nextEvent())
        assertEquals(0, playedFrom.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun aSkipWhilePlayingStartsTheSameTextOver() {
        speak("1", "Yes.")
        nextRequest().second.complete(SpeechAudio(ByteArray(SECONDS_3)))
        assertEquals("start 1@main", nextEvent())
        nextPlay()
        assertEquals(0, playedFrom.poll(2, TimeUnit.SECONDS))
        output.heard = 2 * SpeechAudio.SAMPLE_RATE
        // The skip is counted before Readium stops the engine for it.
        skipCount++
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())

        speak("2", "Yes.")
        nextRequest().second.complete(SpeechAudio(ByteArray(SECONDS_3)))
        assertEquals("start 2@main", nextEvent())
        assertEquals(0, playedFrom.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun stopWhilePlayingHaltsAndNeverReportsDone() {
        speak("1", "Hello.")
        nextRequest().second.complete(SpeechAudio(ByteArray(8)))
        assertEquals("start 1@main", nextEvent())
        val playing = nextPlay()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        playing.complete(Unit)
        noMoreEvents()
        assertTrue(output.halts > 0)
    }

    @Test
    fun aFailureIsTheOnlyTerminalCall() {
        speak("1", "Hello.")
        nextRequest().second.completeExceptionally(SpeechError.Service(500))
        assertEquals("error 1 Service@main", nextEvent())
        onMain { engine.stop() }
        noMoreEvents()
    }

    @Test
    fun anAudioTrackExceptionIsAnOutputFailure() {
        speak("1", "Hello.")
        nextRequest().second.complete(SpeechAudio(ByteArray(8)))
        assertEquals("start 1@main", nextEvent())
        nextPlay().completeExceptionally(IllegalStateException("play() called on uninitialized AudioTrack"))
        assertEquals("error 1 Output@main", nextEvent())
        noMoreEvents()
    }

    @Test
    fun aNewSpeakInterruptsThePreviousOne() {
        speak("1", "One.")
        nextRequest()
        speak("2", "Two.")
        assertEquals("interrupted 1@main", nextEvent())
        nextRequest().second.complete(SpeechAudio(ByteArray(8)))
        assertEquals("start 2@main", nextEvent())
        nextPlay().complete(Unit)
        assertEquals("done 2@main", nextEvent())
        noMoreEvents()
    }

    @Test
    fun stopIsIdempotent() {
        onMain { engine.stop() }
        speak("1", "One.")
        nextRequest()
        onMain {
            engine.stop()
            engine.stop()
        }
        assertEquals("interrupted 1@main", nextEvent())
        noMoreEvents()
    }

    private fun nextWait(): String? = waits.poll(2, TimeUnit.SECONDS)

    @Test
    fun waitsOnlyUntilTheAudioArrives() {
        speak("1", "Hello.")
        val reply = nextRequest().second
        assertEquals("waiting 1", nextWait())
        assertTrue(waits.isEmpty())
        reply.complete(SpeechAudio(ByteArray(8)))
        assertEquals("done waiting 1", nextWait())
        assertEquals("start 1@main", nextEvent())
        nextPlay().complete(Unit)
        assertEquals("done 1@main", nextEvent())
        assertNull(waits.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun stoppingOrFailingEndsTheWait() {
        speak("1", "One.")
        nextRequest()
        assertEquals("waiting 1", nextWait())
        onMain { engine.stop() }
        assertEquals("done waiting 1", nextWait())

        speak("2", "Two.")
        nextRequest().second.completeExceptionally(SpeechError.Service(500))
        assertEquals("waiting 2", nextWait())
        assertEquals("done waiting 2", nextWait())
    }

    private companion object {
        const val SECONDS_3 = 3 * SpeechAudio.SAMPLE_RATE * 2
    }
}
