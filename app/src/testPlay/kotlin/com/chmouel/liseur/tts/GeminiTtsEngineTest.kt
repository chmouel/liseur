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
class GeminiTtsEngineTest {
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
    private val output = object : PcmOutput {
        @Volatile var halts = 0
        override suspend fun play(pcm: ByteArray) {
            val done = CompletableDeferred<Unit>()
            plays.put(done)
            done.await()
        }

        override fun halt() {
            halts++
        }

        override fun release() {}
    }

    private val events = LinkedBlockingQueue<String>()
    private val engine = GeminiTtsEngine(
        scope = scope,
        cache = cache,
        output = output,
        voices = emptySet(),
        publicationLanguage = null,
        initialPreferences = GeminiTtsPreferences(),
    ).apply {
        setListener(object : TtsEngine.Listener<GeminiTtsEngine.Error> {
            fun record(event: String) = events.put("$event@${Thread.currentThread().name.substringBefore(" @")}")
            override fun onStart(requestId: TtsEngine.RequestId) = record("start ${requestId.value}")
            override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) = record("range")
            override fun onInterrupted(requestId: TtsEngine.RequestId) = record("interrupted ${requestId.value}")
            override fun onFlushed(requestId: TtsEngine.RequestId) = record("flushed ${requestId.value}")
            override fun onDone(requestId: TtsEngine.RequestId) = record("done ${requestId.value}")
            override fun onError(requestId: TtsEngine.RequestId, error: GeminiTtsEngine.Error) =
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
    fun stopWhileFetchingInterruptsOnceAndCancelsTheRequest() {
        speak("1", "Hello.")
        val (_, reply) = nextRequest()
        onMain { engine.stop() }
        assertEquals("interrupted 1@main", nextEvent())
        assertEquals("Hello.", cancelledRequests.poll(2, TimeUnit.SECONDS))
        reply.complete(SpeechAudio(ByteArray(8)))
        noMoreEvents()
        assertTrue(plays.isEmpty())
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
}
