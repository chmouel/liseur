package com.chmouel.liseur.tts

import android.app.Application
import android.content.Intent
import com.chmouel.liseur.readaloud.ListeningCheckpoints
import com.chmouel.liseur.readaloud.ReadAloudNotice
import com.chmouel.liseur.readaloud.ReadAloudUi
import com.chmouel.liseur.reader.OpenBookHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

/** What a session speaks with: the voice's name, how many sentences to fetch at once, and the service. */
internal class SessionVoice(val name: String, val maxConcurrent: Int, val synthesizer: SpeechSynthesizer)

/**
 * One book being read aloud: the voice, the sentences fetched ahead of
 * it, and the hold on the open book that keeps the book open after the
 * reader has gone.
 *
 * While it plays it owns the saved place ([OpenBookHandle.claimForListening]):
 * it writes a local checkpoint every [checkpointMillis] and, when it
 * pauses or ends, the place heard with a sync, then hands the place back
 * to the reader. Everything runs on the main thread.
 */
@OptIn(ExperimentalReadiumApi::class, ExperimentalCoroutinesApi::class)
internal class ReadAloudSession(
    private val application: Application,
    val handle: OpenBookHandle,
    /** Brings the reader back to this book, for the notification. */
    val reader: Intent,
    voice: SessionVoice,
    /** The normalized tag of the language the book is read in, such as `fr-FR`. */
    language: String,
    /** How fast it plays, read as it plays. */
    private val speed: () -> Float,
    private val checkpoints: ListeningCheckpoints,
    private val onNotice: (ReadAloudNotice) -> Unit,
    private val onEnded: (ReadAloudSession) -> Unit,
    private val onChapterEnded: () -> Unit,
    private val checkpointMillis: Long = CHECKPOINT_MILLIS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val publication = handle.publication
    private val cache = SpeechCache(scope, voice.synthesizer::synthesize, voice.maxConcurrent)
    private val mutableChoice = MutableStateFlow(VoicePick(voice.name, language))

    /** The voice reading and the language it reads in, which may differ from what the settings hold. */
    val choice: StateFlow<VoicePick> = mutableChoice.asStateFlow()

    /** Chosen once for the session: the whole book is cut into sentences and spoken in it. */
    private val spoken: Language get() = Language(mutableChoice.value.language)

    private val prefetcher = UtterancePrefetcher<Locator>(scope, cache, { locator ->
        val settings = playback.navigator.value?.settings?.value
        PublicationUtteranceCursor(
            publication,
            locator,
            BoundedSentenceTokenizer.factory,
            settings?.language ?: spoken,
            settings?.overrideContentLanguage ?: true,
        )
    })

    val playback: ReadAloudPlayback = ReadAloudPlayback(
        scope = scope,
        opener = { initial, observer, listener ->
            val language = spoken
            val voices = setOf(SpeechTtsEngine.Voice(mutableChoice.value.voice, language))
            val provider = SpeechTtsEngineProvider(scope, cache, { AudioTrackPcmOutput(speed) }, voices, observer)
            TtsNavigatorFactory(application, publication, provider, BoundedSentenceTokenizer.factory)
                ?.createNavigator(listener, initial, SpeechTtsPreferences(language))
                ?.getOrNull()
        },
        prefetcher = prefetcher,
        onStopRequested = { stop() },
        onChapterEnded = {
            settle()
            onChapterEnded()
        },
    )

    private var generation: Long? = null
    private var spokenAt = System.currentTimeMillis()
    private var checkpointJob: Job? = null
    private var ended = false

    private val location = playback.navigator.flatMapLatest { it?.location ?: emptyFlow() }
    private val playWhenReady = playback.navigator
        .flatMapLatest { it?.playback?.map { p -> p.playWhenReady } ?: flowOf(false) }
        .distinctUntilChanged()

    val ui: StateFlow<ReadAloudUi> = combine(
        playWhenReady,
        location.map { it.utteranceLocator }.distinctUntilChanged(),
    ) { playing, utterance -> ReadAloudUi(handle.bookId, playing, utterance) }
        .stateIn(scope, SharingStarted.Eagerly, ReadAloudUi(handle.bookId, playing = false, utterance = null))

    private val mutableStarting = MutableStateFlow(true)

    /** True until the first landing settles, so the reader can show it is on its way. */
    val starting: StateFlow<Boolean> = mutableStarting.asStateFlow()

    init {
        scope.launch {
            location.collect { spokenAt = System.currentTimeMillis() }
        }
        scope.launch {
            playWhenReady.collect { playing ->
                if (playing) {
                    // Playing on within the same sentence moves no location.
                    spokenAt = System.currentTimeMillis()
                    claim()
                } else {
                    settle()
                }
            }
        }
        scope.launch {
            playback.navigator.flatMapLatest { it?.playback ?: emptyFlow() }
                .collect { if (it.state is TtsNavigator.State.Ended) stop() }
        }
        scope.launch {
            playback.failure.filterNotNull().collect { failure ->
                onNotice(failure.error.notice())
                if (!failure.error.recoverable) stop()
            }
        }
    }

    /** Plays from the sentence [selection] starts in. */
    fun start(selection: Locator) {
        claim()
        scope.launch {
            val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, spoken)
            val target = SelectionTarget.of(selection, sentences)
            val landing = playback.start(selection, target?.let { it::matchesClosely }, looser = target?.let { it::matches })
            mutableStarting.value = false
            when (landing) {
                ReadAloudPlayback.Landing.Sentence -> Unit
                ReadAloudPlayback.Landing.ElementStart -> onNotice(ReadAloudNotice.SelectionNotFound)
                ReadAloudPlayback.Landing.Failed -> {
                    onNotice(ReadAloudNotice.Unavailable)
                    stop()
                }
            }
        }
    }

    /** Pauses; the place heard is queued and handed back before this returns. */
    fun pause() {
        playback.pause()
        settle()
    }

    fun resume() {
        if (ended) return
        claim()
        scope.launch {
            if (playback.resume() == ReadAloudPlayback.Landing.Failed) {
                onNotice(ReadAloudNotice.Unavailable)
                settle()
            }
        }
    }

    /**
     * Reads on in [voice], in [language], from the start of the current
     * sentence, dropping what the old voice fetched. Playing or paused,
     * the speed and the sleep timer stay as they were.
     */
    fun switchVoice(voice: SessionVoice, language: String = choice.value.language) {
        if (ended) return
        val recut = language != choice.value.language
        mutableChoice.value = VoicePick(voice.name, language)
        cache.swap(voice.synthesizer::synthesize)
        // Still starting: the first sentence is fetched with the new voice.
        if (playback.navigator.value == null) return
        scope.launch {
            if (playback.replay(recut) == ReadAloudPlayback.Landing.Failed) onNotice(ReadAloudNotice.Unavailable)
        }
    }

    fun skipForward() = playback.skipToNext()

    fun skipBackward() = playback.skipToPrevious()

    /** Ends the session, saving the place heard if it still owns it. */
    fun stop() {
        if (ended) return
        ended = true
        settle()
        checkpointJob?.cancel()
        playback.close()
        scope.cancel()
        handle.release()
        onEnded(this)
    }

    private fun claim() {
        if (ended || generation?.let(handle::listeningHolds) == true) return
        generation = handle.claimForListening()
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            while (true) {
                delay(checkpointMillis)
                save(share = false)
            }
        }
    }

    /** Saves the place heard, with a sync, and gives the place back. */
    private fun settle() {
        val held = generation ?: return
        checkpointJob?.cancel()
        checkpointJob = null
        if (handle.listeningHolds(held)) save(share = true)
        handle.handBack(held)
        generation = null
    }

    private fun save(share: Boolean) {
        val held = generation ?: return
        // The failed sentence was never heard, but it is where resuming starts.
        val heard = playback.failure.value?.anchor?.locator
            ?: playback.navigator.value?.location?.value?.utteranceLocator
            ?: return
        checkpoints.save(handle, held, heard, spokenAt, share)
    }

    private fun SpeechTtsEngine.Error.notice(): ReadAloudNotice = when (this) {
        is SpeechTtsEngine.Error.InvalidKey -> ReadAloudNotice.InvalidKey
        is SpeechTtsEngine.Error.InvalidVoice -> ReadAloudNotice.InvalidVoice
        is SpeechTtsEngine.Error.TermsRequired -> ReadAloudNotice.TermsRequired
        is SpeechTtsEngine.Error.RateLimited -> ReadAloudNotice.RateLimited
        is SpeechTtsEngine.Error.Network -> ReadAloudNotice.Network
        is SpeechTtsEngine.Error.Service, is SpeechTtsEngine.Error.InvalidResponse -> ReadAloudNotice.Service
        is SpeechTtsEngine.Error.Output -> ReadAloudNotice.Output
    }

    companion object {
        const val CHECKPOINT_MILLIS = 30_000L
    }
}
