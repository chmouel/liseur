package com.chmouel.liseur.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Speech for the sentences about to be read, fetched ahead of the voice.
 *
 * Readium hands the engine one sentence at a time and says nothing about
 * what comes next, so [UtterancePrefetcher] walks the book alongside it and
 * fills this cache; the engine only ever [take]s from it. A miss is just a
 * request made then. Used from the main thread; only the requests
 * themselves run elsewhere.
 */
class SpeechCache(
    private val scope: CoroutineScope,
    private var synthesize: suspend (String) -> SpeechAudio,
    maxConcurrent: Int = MAX_CONCURRENT,
    private val maxBytes: Long = MAX_BYTES,
) {
    private class Entry(val audio: Deferred<SpeechAudio>, var bytes: Long = 0)

    private val requests = Semaphore(maxConcurrent)
    private val entries = LinkedHashMap<String, Entry>()

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

    /** Starts fetching [text] unless it is already here or on its way. */
    fun prefetch(text: String) {
        if (!hasRoom || entries.containsKey(text)) return
        val generation = generation
        lateinit var entry: Entry
        // Started only once stored: audio that comes back at once is looked up by its entry.
        entry = Entry(
            request(text, CoroutineStart.LAZY) { audio ->
                if (generation == this.generation && entries[text] === entry) {
                    val bytes = audio.pcm.size.toLong()
                    // Requests in flight weigh nothing until they arrive, so
                    // the budget is held here; [take] asks again if needed.
                    if (cachedBytes + bytes > maxBytes) entries.remove(text) else entry.bytes = bytes
                }
            },
        )
        entries[text] = entry
        entry.audio.start()
    }

    /**
     * The audio for [text], waiting for a request already on its way or
     * making one. A taken entry leaves the cache: a sentence is spoken
     * once, and a restart cannot cancel what is being waited for.
     */
    suspend fun take(text: String): SpeechAudio {
        val audio = entries.remove(text)?.audio?.takeUnless { it.isCancelled } ?: request(text, CoroutineStart.UNDISPATCHED) {}
        try {
            return audio.await()
        } catch (e: CancellationException) {
            // Nobody else can be waiting for a taken entry.
            audio.cancel()
            throw e
        }
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
    fun swap(synthesize: suspend (String) -> SpeechAudio) {
        this.synthesize = synthesize
        restart()
    }

    private fun request(
        text: String,
        start: CoroutineStart,
        onArrived: (SpeechAudio) -> Unit,
    ): Deferred<SpeechAudio> {
        requestsMade++
        return scope.async(start = start) {
            try {
                requests.withPermit { synthesize(text) }.also(onArrived)
            } catch (e: SpeechError.RateLimited) {
                halted = true
                throw e
            } catch (e: SpeechError.InvalidKey) {
                halted = true
                throw e
            } catch (e: SpeechError.InvalidVoice) {
                halted = true
                throw e
            }
        }
    }

    companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}

/** A sentence as Readium will speak it, with the text leading up to it in its element. */
data class UtteranceText(val text: String, val before: String?) {
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
) {
    private var cursor: UtteranceCursor? = null
    private val window = ArrayDeque<UtteranceText>()
    private var suspended = false
    private var job: Job? = null

    /** Times the window was rebuilt from a new place rather than slid on. */
    var restarts = 0
        private set

    /** The voice moved on to [text], found in the element at [locator] with [before] leading up to it. */
    fun onUtterance(text: String, before: String?, locator: L) {
        if (suspended) return
        // The text before a sentence tells a repeated one apart, so a skip
        // back to an earlier occurrence is not taken for reading on.
        val index = window.indexOfFirst { it.isSentence(text, before) }
        if (index >= 0 && cursor != null) {
            repeat(index + 1) { window.removeFirst() }
            refill()
            return
        }
        restartFrom(text, before, locator)
    }

    /** Stops reading ahead while the session settles a jump. */
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
                val candidate = cursor.next() ?: return@launch
                if (candidate.isSentence(text, before)) {
                    this@UtterancePrefetcher.cursor = cursor
                    fill(cursor)
                    return@launch
                }
            }
        }
    }

    private fun refill() {
        val cursor = cursor ?: return
        if (job?.isActive == true) return
        job = scope.launch { fill(cursor) }
    }

    private suspend fun fill(cursor: UtteranceCursor) {
        while (window.size < ahead && cache.hasRoom) {
            val next = try {
                cursor.next()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return
            window.addLast(next)
            cache.prefetch(next.text)
        }
    }

    companion object {
        const val AHEAD = 3
        private const val MAX_SEEK = 200
    }
}
