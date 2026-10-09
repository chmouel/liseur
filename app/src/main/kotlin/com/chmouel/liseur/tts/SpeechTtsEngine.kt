package com.chmouel.liseur.tts

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

@OptIn(ExperimentalReadiumApi::class)
data class SpeechTtsPreferences(
    override val language: Language? = null,
) : TtsEngine.Preferences<SpeechTtsPreferences> {
    override fun plus(other: SpeechTtsPreferences) = SpeechTtsPreferences(language = other.language ?: language)
}

@OptIn(ExperimentalReadiumApi::class)
data class SpeechTtsSettings(
    override val language: Language?,
    override val overrideContentLanguage: Boolean,
) : TtsEngine.Settings

@OptIn(ExperimentalReadiumApi::class)
class SpeechTtsPreferencesEditor(initial: SpeechTtsPreferences) : PreferencesEditor<SpeechTtsPreferences> {
    override var preferences: SpeechTtsPreferences = initial
        private set

    override fun clear() {
        preferences = SpeechTtsPreferences()
    }
}

/**
 * Told what the engine is asked to say and which requests failed, on the
 * main thread. Readium's player moves on to the next sentence after an
 * error, so the session records each failure here, against the sentence
 * that failed, rather than reading it from the player's state.
 */
interface SpeechObserver {
    /** False interrupts this request before any audio is fetched or played. */
    fun onSpeak(requestId: TtsEngine.RequestId, text: String): Boolean = true

    /**
     * Counts the listener's skips. A stopped sentence plays on from where it
     * stopped only if none came since: a skip may land on an identical
     * sentence, which starts from its beginning.
     */
    val skips: Int get() = 0

    fun onFailure(requestId: TtsEngine.RequestId, error: SpeechTtsEngine.Error) {}

    /** True while the request waits for its audio, false once it arrives, fails, or is dropped. */
    fun onWaiting(requestId: TtsEngine.RequestId, waiting: Boolean) {}

    companion object None : SpeechObserver
}

/**
 * A Readium [TtsEngine] whose voice comes from a [SpeechService].
 *
 * The audio for each sentence comes from [cache], which the session's
 * prefetcher keeps a few sentences ahead; a sentence it did not see coming
 * is simply requested then. Every [TtsEngine.Listener] call happens on the
 * main thread, which [scope] must run on, and each request gets exactly one
 * terminal call: Readium's facade replaces its pending task from these
 * callbacks without any locking.
 *
 * Readium pauses by stopping the engine and plays on by speaking the same
 * sentence again, so a stop keeps the sentence's audio, or its request
 * still on its way, and how much of it was heard. Speaking that sentence
 * next plays on from [REWIND_FRAMES] before where it stopped, with nothing
 * fetched again; any other sentence drops it.
 */
@OptIn(ExperimentalReadiumApi::class)
class SpeechTtsEngine(
    private val scope: CoroutineScope,
    private val cache: SpeechCache,
    private val output: PcmOutput,
    override val voices: Set<Voice>,
    private val publicationLanguage: Language?,
    initialPreferences: SpeechTtsPreferences,
    private val observer: SpeechObserver = SpeechObserver,
) : TtsEngine<SpeechTtsSettings, SpeechTtsPreferences, SpeechTtsEngine.Error, SpeechTtsEngine.Voice> {

    data class Voice(val name: String, override val language: Language) : TtsEngine.Voice

    sealed class Error(override val message: String, val speech: SpeechError? = null) : TtsEngine.Error {
        override val cause: org.readium.r2.shared.util.Error? = null

        class InvalidKey(speech: SpeechError) : Error("The speech service rejected the API key", speech)
        class InvalidVoice(speech: SpeechError) : Error("The speech service has no such voice", speech)
        class TermsRequired(speech: SpeechError) : Error("The model's terms are not accepted", speech)
        class RateLimited(speech: SpeechError) : Error("Speech quota exhausted", speech)
        class Network(speech: SpeechError) : Error("Speech service unreachable", speech)
        class Service(speech: SpeechError) : Error("Speech service error", speech)
        class InvalidResponse(speech: SpeechError) : Error("The speech service returned no usable audio", speech)
        class Output(message: String) : Error(message)

        /** Worth a retry later; anything else needs the user to act. */
        val recoverable: Boolean get() = this !is InvalidKey && this !is InvalidVoice && this !is TermsRequired

        companion object {
            fun of(error: SpeechError): Error = when (error) {
                is SpeechError.InvalidKey -> InvalidKey(error)
                is SpeechError.InvalidVoice -> InvalidVoice(error)
                is SpeechError.TermsRequired -> TermsRequired(error)
                is SpeechError.RateLimited -> RateLimited(error)
                is SpeechError.Network -> Network(error)
                is SpeechError.LocalNetworkBlocked -> Network(error)
                is SpeechError.Service -> Service(error)
                is SpeechError.InvalidResponse -> InvalidResponse(error)
            }
        }
    }

    /** [skips] is the observer's count when the request was made: a skip after it makes its audio stale. */
    private class Request(val id: TtsEngine.RequestId, val text: String, val from: Int, val skips: Int) {
        var job: Job? = null
        var audio: Deferred<SpeechAudio>? = null
        var playing = false
        var finished = false
    }

    /** A stopped sentence: its audio, possibly still on its way, the frame to play on from, and its request's skips. */
    private class Held(val text: String, val audio: Deferred<SpeechAudio>, val from: Int, val skips: Int)

    private var listener: TtsEngine.Listener<Error>? = null
    private var current: Request? = null
    private var held: Held? = null

    private val mutableSettings = MutableStateFlow(settingsFor(initialPreferences))
    override val settings: StateFlow<SpeechTtsSettings> = mutableSettings.asStateFlow()

    override fun submitPreferences(preferences: SpeechTtsPreferences) {
        mutableSettings.value = settingsFor(preferences)
    }

    private fun settingsFor(preferences: SpeechTtsPreferences) = SpeechTtsSettings(
        language = preferences.language ?: publicationLanguage,
        overrideContentLanguage = preferences.language != null,
    )

    override fun setListener(listener: TtsEngine.Listener<Error>?) {
        this.listener = listener
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        current?.let { interrupt(it) }
        val resumed = held?.takeIf { it.text == text && it.skips == observer.skips }
        held?.takeIf { it !== resumed }?.audio?.cancel()
        held = null
        val request = Request(requestId, text, resumed?.from ?: 0, observer.skips)
        // Owned at once, so a stop or close before the job runs keeps or cancels it.
        request.audio = resumed?.audio
        current = request
        request.job = scope.launch {
            // Readium maps the new utterance to its public location asynchronously.
            yield()
            if (!observer.onSpeak(requestId, text)) {
                request.audio?.cancel()
                finish(request) { onInterrupted(requestId) }
                return@launch
            }
            val audio = request.audio ?: cache.claim(text).also { request.audio = it }
            observer.onWaiting(requestId, true)
            val speech = try {
                audio.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechError) {
                fail(request, Error.of(e))
                return@launch
            } finally {
                observer.onWaiting(requestId, false)
            }
            if (request.finished) return@launch
            listener?.onStart(requestId)
            request.playing = true
            try {
                output.play(speech.pcm, request.from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // AudioTrack throws its own exceptions besides ours.
                fail(request, Error.Output(e.message ?: "PCM output failed"))
                return@launch
            }
            finish(request) { onDone(requestId) }
        }
    }

    override fun stop() {
        val heard = output.halt()
        val request = current ?: return
        val audio = request.audio
        if (audio != null) {
            val from = if (request.playing && heard != null) maxOf(request.from, heard - REWIND_FRAMES) else request.from
            held = Held(request.text, audio, from, request.skips)
        }
        interrupt(request, keepAudio = audio != null)
    }

    override fun close() {
        listener = null
        current?.job?.cancel()
        current?.audio?.cancel()
        current = null
        held?.audio?.cancel()
        held = null
        output.release()
    }

    private fun interrupt(request: Request, keepAudio: Boolean = false) {
        request.job?.cancel()
        if (!keepAudio) request.audio?.cancel()
        output.halt()
        finish(request) { onInterrupted(request.id) }
    }

    private fun fail(request: Request, error: Error) {
        if (request.finished) return
        observer.onFailure(request.id, error)
        finish(request) { onError(request.id, error) }
    }

    private inline fun finish(request: Request, report: TtsEngine.Listener<Error>.() -> Unit) {
        if (request.finished) return
        request.finished = true
        if (current === request) current = null
        listener?.report()
    }

    companion object {
        /** How far before the place it stopped a sentence plays on: a second, to pick up the thread. */
        const val REWIND_FRAMES = SpeechAudio.SAMPLE_RATE
    }
}

/**
 * Hands Readium's navigator a [SpeechTtsEngine] wired to the session's
 * shared cache, so the navigator's voice and the prefetcher read from the
 * same place.
 */
@OptIn(ExperimentalReadiumApi::class)
class SpeechTtsEngineProvider(
    private val scope: CoroutineScope,
    private val cache: SpeechCache,
    private val output: () -> PcmOutput,
    private val voices: Set<SpeechTtsEngine.Voice>,
    private val observer: SpeechObserver = SpeechObserver,
) : TtsEngineProvider<
    SpeechTtsSettings,
    SpeechTtsPreferences,
    SpeechTtsPreferencesEditor,
    SpeechTtsEngine.Error,
    SpeechTtsEngine.Voice,
    > {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: SpeechTtsPreferences,
    ): Try<SpeechTtsEngine, org.readium.r2.shared.util.Error> = Try.success(
        SpeechTtsEngine(
            scope = scope,
            cache = cache,
            output = output(),
            voices = voices,
            publicationLanguage = publication.metadata.language,
            initialPreferences = initialPreferences,
            observer = observer,
        ),
    )

    override fun createPreferencesEditor(publication: Publication, initialPreferences: SpeechTtsPreferences) =
        SpeechTtsPreferencesEditor(initialPreferences)

    override fun createEmptyPreferences() = SpeechTtsPreferences()

    // Speed is the app's own setting, applied by the PCM output; Readium's stays at its default.
    override fun getPlaybackParameters(settings: SpeechTtsSettings) = PlaybackParameters.DEFAULT

    override fun updatePlaybackParameters(
        previousPreferences: SpeechTtsPreferences,
        playbackParameters: PlaybackParameters,
    ) = previousPreferences

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun mapEngineError(error: SpeechTtsEngine.Error): PlaybackException = PlaybackException(
        "Speech TTS: ${error::class.simpleName}",
        null,
        when (error) {
            is SpeechTtsEngine.Error.Network -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            is SpeechTtsEngine.Error.InvalidKey,
            is SpeechTtsEngine.Error.InvalidVoice,
            is SpeechTtsEngine.Error.TermsRequired,
            is SpeechTtsEngine.Error.RateLimited,
            is SpeechTtsEngine.Error.Service,
            -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            else -> PlaybackException.ERROR_CODE_UNSPECIFIED
        },
    )
}
