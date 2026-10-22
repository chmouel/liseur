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
internal class SessionVoice(val name: String, val maxConcurrent: Int, val configurationIdentity: String = name, val synthesizer: SpeechSynthesizer)

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
    /** How many sentences each request carries, fixed for the session. */
    sentencesPerRequest: Int,
    /** How fast it plays, read as it plays. */
    private val speed: () -> Float,
    private val checkpoints: ListeningCheckpoints,
    private val onNotice: (ReadAloudNotice) -> Unit,
    private val onEnded: (ReadAloudSession) -> Unit,
    private val onChapterEnded: () -> Unit,
    private val checkpointMillis: Long = CHECKPOINT_MILLIS,
    /**
     * Read in translation: each sentence is translated, the way the page
     * translates it, before its audio is asked for, and the voice reads in
     * the translation's language. The session holds it until it ends.
     */
    val translation: SpeechTranslation? = null,
    private val contextConfiguration: () -> SpeechContext.Configuration = { SpeechContext.Configuration.OFF },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val publication = handle.publication
    private val mutableChoice = MutableStateFlow(VoicePick(voice.name, language))

    /** The voice reading and the language it reads in, which may differ from what the settings hold. */
    val choice: StateFlow<VoicePick> = mutableChoice.asStateFlow()

    /** Chosen once for the session: the whole book is cut into sentences and spoken in it. */
    private val spoken: Language get() = Language(mutableChoice.value.language)

    /**
     * What the book's text is cut into sentences in. Read in its own words,
     * the voice's language; read translated, the language translated from,
     * as the page cuts it, whatever voice reads the translation. Null leaves
     * it to the book.
     */
    private val textLanguage: Language?
        get() = if (translation != null) translation.source?.let(::Language) else spoken

    private val translatedSpeech = translation?.let { t ->
        TranslatedSpeech(
            translations = t.translations,
            sentences = if (sentencesPerRequest <= 1) {
                { listOf(it) }
            } else {
                { TranslatedSpeech.sentencesOf(it, textLanguage ?: publication.metadata.language) }
            },
            pieces = { TranslatedSpeech.piecesOf(it, Language(t.target)) },
        )
    }

    private val speechContext = SpeechContext(
        preceding = handle.preceding((textLanguage ?: publication.metadata.language)?.locale?.toLanguageTag()),
        bookFacts = handle.bookFacts,
        sourceLanguage = if (translation != null) {
            translation.source ?: publication.metadata.language?.locale?.toLanguageTag()
        } else {
            (textLanguage ?: publication.metadata.language)?.locale?.toLanguageTag()
        },
        spokenLanguage = { spoken.locale.toLanguageTag() },
        configuration = contextConfiguration,
    )

    private val cache = SpeechCache(
        scope = scope,
        synthesize = { text, instructions -> voice.synthesizer.synthesize(text, instructions) },
        maxConcurrent = voice.maxConcurrent,
        prepare = translatedSpeech?.let { it::prepare },
        configurationIdentity = voice.configurationIdentity,
        context = speechContext::resolve,
    )

    private val tokenizerFactory = BoundedSentenceTokenizer.factory(sentencesPerRequest)

    private val prefetcher = UtterancePrefetcher<Locator>(
        scope,
        cache,
        { locator ->
            val settings = playback.navigator.value?.settings?.value
            val cutIn = textLanguage
            PublicationUtteranceCursor(
                publication,
                locator,
                tokenizerFactory,
                settings?.language ?: cutIn ?: publication.metadata.language,
                settings?.overrideContentLanguage ?: (cutIn != null),
            )
        },
        keyOf = { u ->
            val tId = translation?.identity()
            val cId = speechContext.identity()
            if ((translation != null || cId != null) && u.locator != null) {
                SpeechKey.at(u.text, u.locator, tId, cId)
            } else {
                SpeechKey(u.text, translationIdentity = tId, contextIdentity = cId)
            }
        },
    )

    val playback: ReadAloudPlayback = ReadAloudPlayback(
        scope = scope,
        opener = { initial, observer, listener ->
            val voices = setOf(SpeechTtsEngine.Voice(mutableChoice.value.voice, spoken))
            val provider = SpeechTtsEngineProvider(scope, cache, { AudioTrackPcmOutput(speed) }, voices, observer)
            TtsNavigatorFactory(application, publication, provider, tokenizerFactory)
                ?.createNavigator(listener, initial, SpeechTtsPreferences(textLanguage))
                ?.getOrNull()
        },
        prefetcher = prefetcher,
        anchored = translation != null,
        translationIdentity = { translation?.identity() },
        contextIdentity = speechContext::identity,
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

    private val mutableStarting = MutableStateFlow(true)

    /** True until the first landing settles, so the reader can show it is on its way. */
    val starting: StateFlow<Boolean> = mutableStarting.asStateFlow()

    val ui: StateFlow<ReadAloudUi> = combine(
        playWhenReady,
        location.map { it.utteranceLocator }.distinctUntilChanged(),
        starting,
        playback.waiting,
    ) { playing, utterance, starting, waiting ->
        ReadAloudUi(
            handle.bookId,
            playing,
            utterance,
            preparing = starting || (playing && waiting),
            translated = translation != null,
        )
    }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            ReadAloudUi(
                handle.bookId,
                playing = false,
                utterance = null,
                preparing = true,
                translated = translation != null,
            ),
        )

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
            val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, textLanguage ?: publication.metadata.language)
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
        // Read translated, the book is cut in the language translated from, whatever the voice.
        val recut = language != choice.value.language && translation == null
        mutableChoice.value = VoicePick(voice.name, language)
        cache.swap({ text, instructions -> voice.synthesizer.synthesize(text, instructions) }, voice.configurationIdentity)
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
        translation?.translations?.release()
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
        // Read translated, a sentence can wait long on its translation: the
        // place saved is the last one heard, never one only asked for.
        val heard = playback.failure.value?.anchor?.locator
            ?: playback.heard.value?.takeIf { translation != null }
            ?: playback.navigator.value?.location?.value?.utteranceLocator
            ?: return
        checkpoints.save(handle, held, heard, spokenAt, share)
    }

    private fun SpeechTtsEngine.Error.notice(): ReadAloudNotice = when (this) {
        is SpeechTtsEngine.Error.InvalidKey -> ReadAloudNotice.InvalidKey
        is SpeechTtsEngine.Error.InvalidVoice -> ReadAloudNotice.InvalidVoice
        is SpeechTtsEngine.Error.TermsRequired -> ReadAloudNotice.TermsRequired
        is SpeechTtsEngine.Error.NoSpeech -> ReadAloudNotice.NoSpeech
        is SpeechTtsEngine.Error.RateLimited -> ReadAloudNotice.RateLimited
        is SpeechTtsEngine.Error.Network -> ReadAloudNotice.Network
        is SpeechTtsEngine.Error.Service, is SpeechTtsEngine.Error.InvalidResponse -> ReadAloudNotice.Service
        is SpeechTtsEngine.Error.Output -> ReadAloudNotice.Output
        is SpeechTtsEngine.Error.Translation -> ReadAloudNotice.TranslationFailed
        is SpeechTtsEngine.Error.Unplaced -> ReadAloudNotice.Unavailable
    }

    companion object {
        const val CHECKPOINT_MILLIS = 30_000L
    }
}
