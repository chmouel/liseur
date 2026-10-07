package com.chmouel.liseur.tts

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    fun onSpeak(requestId: TtsEngine.RequestId, text: String) {}

    fun onFailure(requestId: TtsEngine.RequestId, error: SpeechTtsEngine.Error) {}

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
        class RateLimited(speech: SpeechError) : Error("Speech quota exhausted", speech)
        class Network(speech: SpeechError) : Error("Speech service unreachable", speech)
        class Service(speech: SpeechError) : Error("Speech service error", speech)
        class InvalidResponse(speech: SpeechError) : Error("The speech service returned no usable audio", speech)
        class Output(message: String) : Error(message)

        /** Worth a retry later; anything else needs the user to act. */
        val recoverable: Boolean get() = this !is InvalidKey && this !is InvalidVoice

        companion object {
            fun of(error: SpeechError): Error = when (error) {
                is SpeechError.InvalidKey -> InvalidKey(error)
                is SpeechError.InvalidVoice -> InvalidVoice(error)
                is SpeechError.RateLimited -> RateLimited(error)
                is SpeechError.Network -> Network(error)
                is SpeechError.Service -> Service(error)
                is SpeechError.InvalidResponse -> InvalidResponse(error)
            }
        }
    }

    private class Request(val id: TtsEngine.RequestId) {
        var job: Job? = null
        var finished = false
    }

    private var listener: TtsEngine.Listener<Error>? = null
    private var current: Request? = null

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
        val request = Request(requestId)
        current = request
        observer.onSpeak(requestId, text)
        request.job = scope.launch {
            val audio = try {
                cache.take(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechError) {
                fail(request, Error.of(e))
                return@launch
            }
            if (request.finished) return@launch
            listener?.onStart(requestId)
            try {
                output.play(audio.pcm)
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
        output.halt()
        current?.let { interrupt(it) }
    }

    override fun close() {
        listener = null
        current?.job?.cancel()
        current = null
        output.release()
    }

    private fun interrupt(request: Request) {
        request.job?.cancel()
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
            is SpeechTtsEngine.Error.RateLimited,
            is SpeechTtsEngine.Error.Service,
            -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            else -> PlaybackException.ERROR_CODE_UNSPECIFIED
        },
    )
}
