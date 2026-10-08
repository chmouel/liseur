package com.chmouel.liseur.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

@OptIn(ExperimentalReadiumApi::class)
typealias SpeechNavigator =
    TtsNavigator<SpeechTtsSettings, SpeechTtsPreferences, SpeechTtsEngine.Error, SpeechTtsEngine.Voice>

/** Opens a paused navigator at [initial], its engine reporting to [observer]. Null when the book cannot be read aloud. */
@OptIn(ExperimentalReadiumApi::class)
fun interface NavigatorOpener {
    suspend fun open(initial: Locator?, observer: SpeechObserver, listener: TtsNavigator.Listener): SpeechNavigator?
}

/**
 * The voice's place in the book and the moves that keep it from skipping
 * a sentence: playing from a given sentence, and picking up again at the
 * sentence that failed.
 *
 * Readium's player moves on to the next sentence after an engine error and
 * seeks only to whole elements, so landing on a sentence is done by opening
 * a fresh, paused navigator at the element and stepping through it
 * ([alignUtterance]). Runs on the main thread, like the navigator. One
 * landing runs at a time: another would replace its navigator under it.
 */
@OptIn(ExperimentalReadiumApi::class, ExperimentalCoroutinesApi::class)
class ReadAloudPlayback(
    private val scope: CoroutineScope,
    private val opener: NavigatorOpener,
    private val prefetcher: UtterancePrefetcher<Locator>? = null,
    private val onStopRequested: () -> Unit = {},
    private val onChapterEnded: () -> Unit = {},
    private val stepTimeoutMillis: Long = 2_000,
) : SpeechObserver {

    /** A sentence that could not be spoken; [anchor] is where resuming starts. */
    data class Failure(val error: SpeechTtsEngine.Error, val anchor: UtteranceAnchor?)

    enum class Landing {
        /** On the sentence asked for. */
        Sentence,

        /** It was not found; playing from the start of its element. */
        ElementStart,

        /** No navigator could be opened, or the failed sentence could not be reached. */
        Failed,
    }

    private val mutableNavigator = MutableStateFlow<SpeechNavigator?>(null)

    /** The live navigator; it is replaced when the voice is re-placed, so follow this rather than keep one. */
    val navigator: StateFlow<SpeechNavigator?> = mutableNavigator.asStateFlow()

    private val mutableFailure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = mutableFailure.asStateFlow()

    private val spoken = LinkedHashMap<TtsEngine.RequestId, String>()
    private var closed = false
    private var playRequested = false
    private var chapterEndArmed = false
    private var chapterHref: Url? = null
    private val landing = Mutex()

    private val listener = object : TtsNavigator.Listener {
        override fun onStopRequested() = this@ReadAloudPlayback.onStopRequested()
    }

    init {
        prefetcher?.let { prefetcher ->
            scope.launch {
                mutableNavigator.flatMapLatest { it?.playback?.map { p -> p.playWhenReady } ?: emptyFlow() }
                    .distinctUntilChanged()
                    .collectLatest { playing ->
                        if (playing) {
                            prefetcher.resume()
                            mutableNavigator.value?.location?.value?.let(::prefetchFrom)
                        } else {
                            prefetcher.suspend()
                        }
                    }
            }
            scope.launch {
                mutableNavigator.flatMapLatest { it?.location ?: emptyFlow() }
                    .collect { location ->
                        if (mutableNavigator.value?.playback?.value?.playWhenReady == true) prefetchFrom(location)
                    }
            }
        }
    }

    private fun prefetchFrom(location: TtsNavigator.Location) {
        prefetcher?.onUtterance(location.utterance, location.utteranceLocator.text.before, location.utteranceLocator)
    }

    /**
     * Plays from [locator]: from the sentence [target] picks out in its
     * element, else the one [looser] picks out, or from the element start
     * when there is no target or neither is found within [maxSteps]
     * sentences. The element is the first one on screen, so the search may
     * cross a whole page of short sentences.
     */
    suspend fun start(
        locator: Locator,
        target: ((TtsNavigator.Location) -> Boolean)? = null,
        maxSteps: Int = START_STEPS,
        looser: ((TtsNavigator.Location) -> Boolean)? = null,
    ): Landing {
        playRequested = true
        mutableFailure.value = null
        return landing.withLock { land(locator.copy(text = Locator.Text()), target, maxSteps, looser = looser) }
    }

    fun pause() {
        playRequested = false
        mutableNavigator.value?.pause()
    }

    /** Pauses before leaving the current EPUB reading section, including with the screen off. */
    fun stopAtChapterEnd(enabled: Boolean) {
        chapterEndArmed = enabled
        chapterHref = if (enabled) mutableNavigator.value?.location?.value?.href else null
    }

    /**
     * Plays on; after a failure, from the sentence that failed. The wish to
     * play is recorded before waiting for a landing under way, so a pause
     * that comes in the meantime still wins.
     */
    suspend fun resume(): Landing {
        playRequested = true
        return landing.withLock { resumeLanded() }
    }

    private suspend fun resumeLanded(): Landing {
        val navigator = mutableNavigator.value ?: return Landing.Failed
        val failed = mutableFailure.value ?: run {
            if (playRequested) navigator.play()
            return Landing.Sentence
        }
        val anchor = failed.anchor ?: return Landing.Failed
        // The player has moved past the failed sentence, possibly not yet
        // visibly; a fresh navigator at its element is the only sure way back.
        val landed = land(anchor.elementLocator, anchor::isAt, Int.MAX_VALUE, fallbackToElement = false)
        if (landed == Landing.Sentence && playRequested) mutableFailure.value = null
        return landed
    }

    /**
     * Speaks the current sentence again from its start, or the one that
     * failed, on a fresh navigator, so a new voice is heard at once.
     * Playing or paused stays as it was.
     */
    suspend fun replay(): Landing = landing.withLock {
        val anchor = mutableFailure.value?.anchor
            ?: mutableNavigator.value?.location?.value?.let(UtteranceAnchor::of)
            ?: return@withLock Landing.Failed
        val landed = land(anchor.elementLocator, anchor::isAt, Int.MAX_VALUE, fallbackToElement = false)
        if (landed == Landing.Sentence) mutableFailure.value = null
        landed
    }

    /** The reader's own skips: playing on starts from where they lead, not from a failed sentence. */
    fun skipToNext() {
        mutableFailure.value = null
        mutableNavigator.value?.skipToNextUtterance()
    }

    fun skipToPrevious() {
        mutableFailure.value = null
        mutableNavigator.value?.skipToPreviousUtterance()
    }

    fun close() {
        closed = true
        playRequested = false
        prefetcher?.close()
        mutableNavigator.value?.close()
        mutableNavigator.value = null
        spoken.clear()
    }

    private suspend fun land(
        element: Locator,
        target: ((TtsNavigator.Location) -> Boolean)?,
        maxSteps: Int,
        fallbackToElement: Boolean = true,
        looser: ((TtsNavigator.Location) -> Boolean)? = null,
    ): Landing {
        var fresh = replace(element) ?: return Landing.Failed
        suspend fun align(on: SpeechNavigator, matches: (TtsNavigator.Location) -> Boolean) = alignUtterance(
            location = on.location,
            hasNext = on::hasNextUtterance,
            skipToNext = on::skipToNextUtterance,
            matches = matches,
            inScope = {
                it.href == element.href &&
                    (fallbackToElement || it.utteranceLocator.locations == element.locations)
            },
            stepTimeoutMillis = stepTimeoutMillis,
            maxSteps = maxSteps,
        )
        var found = target == null || align(fresh, target)
        if (!found && looser != null && !closed && mutableNavigator.value === fresh) {
            fresh = replace(element) ?: return Landing.Failed
            found = align(fresh, looser)
        }
        if (closed || mutableNavigator.value !== fresh) return Landing.Failed
        if (!found && !fallbackToElement) return Landing.Failed
        val playing = if (found) fresh else replace(element) ?: return Landing.Failed
        if (playRequested) playing.play()
        return if (found) Landing.Sentence else Landing.ElementStart
    }

    private suspend fun replace(initial: Locator): SpeechNavigator? {
        if (closed) return null
        prefetcher?.suspend()
        val fresh = opener.open(initial, this, listener) ?: return null
        if (closed) {
            fresh.close()
            return null
        }
        val previous = mutableNavigator.value
        spoken.clear()
        mutableNavigator.value = fresh
        previous?.close()
        return fresh
    }

    override fun onSpeak(requestId: TtsEngine.RequestId, text: String): Boolean {
        if (chapterEndArmed) {
            val href = mutableNavigator.value?.location?.value?.href
            if (chapterHref == null) {
                chapterHref = href
            } else if (href != chapterHref) {
                stopAtChapterEnd(false)
                pause()
                onChapterEnded()
                return false
            }
        }
        spoken[requestId] = text
        if (spoken.size > SPOKEN_KEPT) spoken.remove(spoken.keys.first())
        return true
    }

    override fun onFailure(requestId: TtsEngine.RequestId, error: SpeechTtsEngine.Error) {
        // Only this navigator's requests; a closed one's are long gone.
        if (requestId !in spoken) return
        val navigator = mutableNavigator.value ?: return
        // Readium publishes the new sentence before the engine is asked to
        // speak it, and the failure arrives after, so the place shown now is
        // the failed sentence. Should it still lag, the sentence shown is the
        // one before: replaying it repeats a sentence rather than skipping one.
        mutableFailure.value = Failure(error, UtteranceAnchor.of(navigator.location.value))
        pause()
    }

    companion object {
        const val START_STEPS = 80
        private const val SPOKEN_KEPT = 8
    }
}
