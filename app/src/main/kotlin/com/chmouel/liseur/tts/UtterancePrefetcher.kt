package com.chmouel.liseur.tts

import android.util.Log
import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.readium.r2.shared.publication.Locator

/**
 * Speech for the sentences about to be read, fetched ahead of the voice.
 *
 * Readium hands the engine one sentence at a time and says nothing about
 * what comes next, so [UtterancePrefetcher] walks the book alongside it and
 * fills this cache; the engine only ever [claim]s from it. A miss is just a
 * request made then. Used from the main thread; only the requests
 * themselves run elsewhere.
 */
class SpeechCache(
    private val scope: CoroutineScope,
    private var synthesize: suspend (String, String?) -> SpeechAudio,
    maxConcurrent: Int = MAX_CONCURRENT,
    private val maxBytes: Long = MAX_BYTES,
    /**
     * What a request says, in pieces each within a request's bounds, given
     * the source text before it when known; null to say the key's text.
     * Read translated, this is where the sentence is translated, before
     * its audio is asked for: a translation that fails fails the request.
     */
    private val prepare: (suspend (SpeechKey, String?) -> List<String>)? = null,
    private val nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    private var configurationIdentity: String? = null,
    /** Non-spoken reference context resolved for a request before synthesis. */
    private val context: (suspend (SpeechKey, String?) -> String?)? = null,
) {
    constructor(
        scope: CoroutineScope,
        synthesize: suspend (String) -> SpeechAudio,
        maxConcurrent: Int = MAX_CONCURRENT,
        maxBytes: Long = MAX_BYTES,
        prepare: (suspend (SpeechKey, String?) -> List<String>)? = null,
        nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
        configurationIdentity: String? = null,
        context: (suspend (SpeechKey, String?) -> String?)? = null,
    ) : this(
        scope = scope,
        synthesize = { text, _ -> synthesize(text) },
        maxConcurrent = maxConcurrent,
        maxBytes = maxBytes,
        prepare = prepare,
        nowMillis = nowMillis,
        configurationIdentity = configurationIdentity,
        context = context,
    )

    private class Entry(val audio: Deferred<SpeechAudio>, var bytes: Long = 0)

    private val requests = Semaphore(maxConcurrent)
    private val entries = LinkedHashMap<Pair<String?, SpeechKey>, Entry>()
    private var currentTranslationIdentity: String? = null
    private var hasTranslationIdentity = false
    private var currentContextIdentity: String? = null
    private var hasContextIdentity = false

    /** Bumped on every restart; speculative work from an older generation is dropped. */
    var generation = 0L
        private set

    /** Set by an invalid key or voice, or a quota error: speculative requests stop until the next restart. */
    var halted = false
        private set

    /** Requests made, for the tests. */
    var requestsMade = 0
        private set

    val cachedBytes: Long get() = entries.values.sumOf { it.bytes }

    val hasRoom: Boolean get() = !halted && cachedBytes < maxBytes

    private fun selectIdentity(key: SpeechKey) {
        if ((hasTranslationIdentity && currentTranslationIdentity != key.translationIdentity) ||
            (hasContextIdentity && currentContextIdentity != key.contextIdentity)
        ) {
            restart()
        }
        currentTranslationIdentity = key.translationIdentity
        hasTranslationIdentity = true
        currentContextIdentity = key.contextIdentity
        hasContextIdentity = true
    }

    /** Starts fetching [text] unless it is already here or on its way. */
    fun prefetch(text: String) = prefetch(SpeechKey(text))

    /** Starts fetching [key], with [previous] the source text before it, unless it is already here or on its way. */
    fun prefetch(key: SpeechKey, previous: String? = null) {
        selectIdentity(key)
        val cacheKey = configurationIdentity to key
        if (!hasRoom || entries.containsKey(cacheKey)) return
        val generation = generation
        lateinit var entry: Entry
        // Started only once stored: audio that comes back at once is looked up by its entry.
        entry = Entry(
            request(key, previous, CoroutineStart.LAZY) { audio ->
                if (generation == this.generation && entries[cacheKey] === entry) {
                    val bytes = audio.pcm.size.toLong()
                    // Requests in flight weigh nothing until they arrive, so
                    // the budget is held here; [claim] asks again if needed.
                    if (cachedBytes + bytes > maxBytes) entries.remove(cacheKey) else entry.bytes = bytes
                }
            },
        )
        entries[cacheKey] = entry
        entry.audio.start()
    }

    /**
     * The audio for [text]: a request already on its way, or one made now.
     * A claimed entry leaves the cache: a sentence is spoken once, and a
     * restart cannot cancel what is being waited for. The caller owns the
     * request and cancels it once it no longer wants it.
     */
    fun claim(text: String): Deferred<SpeechAudio> = claim(SpeechKey(text))

    /** As [claim] for [key]; [previous] is the source text before it, when known, for a request made now. */
    fun claim(key: SpeechKey, previous: String? = null): Deferred<SpeechAudio> {
        selectIdentity(key)
        return entries.remove(configurationIdentity to key)?.audio?.takeUnless { it.isCancelled }
            ?: request(key, previous, CoroutineStart.UNDISPATCHED) {}
    }

    /** Forgets everything speculative: the reading jumped somewhere else. */
    fun restart() {
        generation++
        halted = false
        val stale = entries.values.toList()
        entries.clear()
        stale.forEach { it.audio.cancel() }
    }

    /** Speaks with [synthesize] from now on, forgetting what the old voice fetched. */
    fun swap(synthesize: suspend (String) -> SpeechAudio) = swap(synthesize, null)

    fun swap(synthesize: suspend (String) -> SpeechAudio, configurationIdentity: String?) =
        swap({ text, _ -> synthesize(text) }, configurationIdentity)

    fun swap(synthesize: suspend (String, String?) -> SpeechAudio, configurationIdentity: String? = null) {
        this.synthesize = synthesize
        this.configurationIdentity = configurationIdentity
        restart()
    }

    private fun request(
        key: SpeechKey,
        previous: String?,
        start: CoroutineStart,
        onArrived: (SpeechAudio) -> Unit,
    ): Deferred<SpeechAudio> {
        requestsMade++
        val synthesizer = synthesize
        val generation = generation
        return scope.async(start = start) {
            try {
                // Translated and placed outside the permits: those bound the speech service alone.
                val pieces = prepare?.invoke(key, previous) ?: listOf(key.text)
                val instructions = context?.invoke(key, previous)
                val audio = ArrayList<SpeechAudio>(pieces.size)
                var totalBytes = 0L
                for (piece in pieces) {
                    val next = synthesizeWithRetry(piece, instructions, synthesizer)
                    val bytes = next.pcm.size.toLong()
                    if (bytes > MAX_BYTES - totalBytes) {
                        throw SpeechError.InvalidResponse("utterance audio too long")
                    }
                    totalBytes += bytes
                    audio += next
                }
                (audio.singleOrNull() ?: joined(audio)).also(onArrived)
            } catch (e: SpeechError.RateLimited) {
                if (generation == this@SpeechCache.generation) halted = true
                throw e
            } catch (e: SpeechError.InvalidKey) {
                if (generation == this@SpeechCache.generation) halted = true
                throw e
            } catch (e: SpeechError.InvalidVoice) {
                if (generation == this@SpeechCache.generation) halted = true
                throw e
            } catch (e: SpeechError.TermsRequired) {
                if (generation == this@SpeechCache.generation) halted = true
                throw e
            } catch (e: SpeechError.NoSpeech) {
                if (generation == this@SpeechCache.generation) halted = true
                throw e
            }
        }
    }

    private suspend fun synthesizeWithRetry(
        piece: String,
        instructions: String?,
        synthesizer: suspend (String, String?) -> SpeechAudio,
    ): SpeechAudio {
        var firstAttempt: Long? = null
        var failure: SpeechError? = null
        for (attempt in 0..2) {
            if (attempt > 0) delay(if (attempt == 1) 1_000L else 3_000L)
            try {
                return requests.withPermit {
                    val now = nowMillis()
                    if (firstAttempt?.let { now - it >= 30_000L } == true) throw failure!!
                    if (firstAttempt == null) firstAttempt = now
                    synthesizer(piece, instructions)
                }
            } catch (error: SpeechError) {
                val retryable = error is SpeechError.Network ||
                    (error is SpeechError.Service && error.code in setOf(408, 500, 502, 503, 504))
                if (!retryable || attempt == 2 || nowMillis() - firstAttempt!! >= 30_000L) throw error
                failure = error
                if (BuildConfig.DEBUG) Log.d("SpeechCache", "Retrying synthesis, attempt ${attempt + 2}")
            }
        }
        throw checkNotNull(failure)
    }

    /** Pieces of one request played as one: one place in the book, one stretch of audio. */
    private fun joined(pieces: List<SpeechAudio>): SpeechAudio {
        val pcm = ByteArray(pieces.sumOf { it.pcm.size })
        var at = 0
        for (piece in pieces) {
            piece.pcm.copyInto(pcm, at)
            at += piece.pcm.size
        }
        return SpeechAudio(pcm)
    }

    companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}

/**
 * A sentence as Readium will speak it, with the text leading up to it in
 * its element, and the [locator] Readium gave it when the walk knows it.
 */
data class UtteranceText(val text: String, val before: String?, val locator: Locator? = null) {
    /** Whether this is [text] with [before] leading up to it; a missing context matches any. */
    fun isSentence(text: String, before: String?): Boolean =
        this.text == text && (before == null || this.before == null || this.before == before)
}

/** Reads the book's sentences forward from a place, the way Readium's own iterator does. */
fun interface UtteranceCursor {
    /** The next sentence, or null at the end of the book. */
    suspend fun next(): UtteranceText?
}

/**
 * Keeps the next few sentences fetched.
 *
 * Told by the session where the voice is ([onUtterance]): a sentence in the
 * window slides it on, anything else restarts it from the new place and
 * drops the speculative work for the old one.
 */
class UtterancePrefetcher<L>(
    private val scope: CoroutineScope,
    private val cache: SpeechCache,
    private val cursorAt: suspend (L) -> UtteranceCursor,
    private val ahead: Int = AHEAD,
    /** What the cache files a sentence under; its text alone unless read translated. */
    private val keyOf: suspend (UtteranceText) -> SpeechKey = { SpeechKey(it.text) },
) {
    private var cursor: UtteranceCursor? = null

    // The last sentence the walk read, the one before the next it fetches.
    private var lastRead: UtteranceText? = null
    private val window = ArrayDeque<UtteranceText>()
    private var current: Pair<UtteranceText, L>? = null
    private var suspended = false
    private var job: Job? = null

    /** Times the window was rebuilt from a new place rather than slid on. */
    var restarts = 0
        private set

    /** The voice moved on to [text], found in the element at [locator] with [before] leading up to it. */
    fun onUtterance(text: String, before: String?, locator: L) {
        if (suspended) return
        // Playing on after a pause: the window still runs from here. Its
        // element tells it from the same sentence somewhere else.
        val resuming = current?.let { (sentence, at) -> sentence.isSentence(text, before) && at == locator } == true
        if (resuming && (cursor != null || job?.isActive == true)) {
            refill()
            return
        }
        // The text before a sentence tells a repeated one apart, so a skip
        // back to an earlier occurrence is not taken for reading on.
        val index = window.indexOfFirst { it.isSentence(text, before) }
        if (index >= 0 && cursor != null) {
            repeat(index + 1) { window.removeFirst() }
            refill()
        } else {
            restartFrom(text, before, locator)
        }
        current = UtteranceText(text, before) to locator
    }

    /** Stops reading ahead while the voice is paused, keeping what was fetched for when it plays on. */
    fun pause() {
        suspended = true
    }

    /** Stops reading ahead while the session settles a jump, dropping what was fetched. */
    fun suspend() {
        suspended = true
        reset()
    }

    fun resume() {
        suspended = false
    }

    fun close() = suspend()

    private fun reset() {
        job?.cancel()
        job = null
        cursor = null
        current = null
        lastRead = null
        window.clear()
        cache.restart()
    }

    private fun restartFrom(text: String, before: String?, locator: L) {
        restarts++
        reset()
        job = scope.launch {
            val cursor = try {
                cursorAt(locator)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            // The cursor starts at the element; find this sentence in it,
            // using the text before it to tell a repeated sentence apart.
            repeat(MAX_SEEK) {
                val candidate = try {
                    cursor.next()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                } ?: return@launch
                if (candidate.isSentence(text, before)) {
                    this@UtterancePrefetcher.cursor = cursor
                    lastRead = candidate
                    fill(cursor)
                    return@launch
                }
                lastRead = candidate
            }
        }
    }

    private fun refill() {
        val cursor = cursor ?: return
        if (job?.isActive == true) return
        job = scope.launch { fill(cursor) }
    }

    private suspend fun fill(cursor: UtteranceCursor) {
        while (!suspended && window.size < ahead && cache.hasRoom) {
            val next = try {
                cursor.next()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return
            val key = try {
                keyOf(next)
            } catch (_: TranslationError) {
                return
            }
            window.addLast(next)
            cache.prefetch(key, lastRead?.text)
            lastRead = next
        }
    }

    companion object {
        const val AHEAD = 3
        private const val MAX_SEEK = 200
    }
}
