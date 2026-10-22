package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.TranslationContextSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The book's sentences just before one, in the same resource. */
fun interface PrecedingText {
    /**
     * Up to [count] sentences just before [sentence] in its resource, in
     * reading order; empty at the start of the resource, null when the
     * sentence cannot be found there.
     */
    suspend fun before(sentence: PageSentence, count: Int): List<PageSentence>?
}

/** The earlier text that goes with a sentence, cut to a [TranslationContextSize]. */
internal object TranslationContext {
    /**
     * [earlier], nearest last, as the text a model reads before the
     * sentence: the nearest sentences that fit [size], whole, with a line
     * break where the element changes. A nearest sentence longer than the
     * whole budget goes as its end. Null when there is nothing to send.
     */
    fun window(earlier: List<PageSentence>, size: TranslationContextSize): String? {
        if (size.sentences <= 0 || size.characters <= 0) return null
        var text = ""
        var next: PageSentence? = null
        for (sentence in earlier.takeLast(size.sentences).asReversed()) {
            val words = sentence.text.trim()
            if (words.isEmpty()) continue
            val joined = if (next == null) words else words + separator(sentence, next) + text
            if (joined.length > size.characters) {
                if (next == null) text = tail(words, size.characters)
                break
            }
            text = joined
            next = sentence
        }
        return text.ifEmpty { null }
    }

    private fun separator(sentence: PageSentence, next: PageSentence): String =
        if (sentence.href == next.href && sentence.selector == next.selector) " " else "\n"

    // The end of [text] in at most [max] characters, never half of a surrogate pair.
    private fun tail(text: String, max: Int): String {
        val cut = text.takeLast(max)
        return if (cut.isNotEmpty() && cut.first().isLowSurrogate()) cut.drop(1) else cut
    }
}

/**
 * The sentences of each resource as read from its start, walked once and
 * kept, so finding what comes before a sentence costs a walk only as far
 * as the furthest sentence asked about. [open] gives a resource's
 * sentences from its start, ending at the end of the book; the walk stops
 * where they leave the resource. The last [resources] walked are kept.
 *
 * A sentence is found by its element, its words and the words before it.
 * When the same sentence appears twice that way, the first place is taken:
 * a later repeat then gets less context, but never itself or what follows.
 * A passage of several sentences, as read aloud may ask about, is found
 * at its first sentence: the same element and words before, its words
 * starting the passage.
 */
class PrecedingSentences(
    private val open: suspend (href: String) -> PageSentences?,
    private val resources: Int = RESOURCES,
    private val longest: Int = LONGEST,
    private val walking: CoroutineDispatcher = Dispatchers.Default,
) : PrecedingText {
    private class Walk(val sentences: PageSentences?) {
        val walked = ArrayList<PageSentence>()
        val places = HashMap<String, Int>()
        val starts = HashMap<String, Int>()
        var ended = false
    }

    private val lock = Mutex()
    private val walks = LinkedHashMap<String, Walk>()

    override suspend fun before(sentence: PageSentence, count: Int): List<PageSentence>? = withContext(walking) {
        lock.withLock {
            val walk = walks.remove(sentence.href) ?: Walk(opened(sentence.href))
            walks[sentence.href] = walk
            while (walks.size > resources) walks.remove(walks.keys.first())
            val at = find(walk, sentence) ?: return@withLock null
            walk.walked.subList(maxOf(0, at - count), at).toList()
        }
    }

    private suspend fun opened(href: String): PageSentences? = try {
        open(href)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun find(walk: Walk, sentence: PageSentence): Int? {
        val key = key(sentence)
        walk.places[key]?.let { return it }
        val start = start(sentence)
        val words = squash(sentence.text)
        fun opens(at: Int) = squash(walk.walked[at].text).let { it.isNotEmpty() && words.startsWith(it) }
        walk.starts[start]?.let { if (opens(it)) return it }
        while (!walk.ended) {
            currentCoroutineContext().ensureActive()
            // A resource that cannot be read further only means less context, never a failed translation.
            val next = try {
                walk.sentences?.next()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (next == null || next.href != sentence.href || walk.walked.size >= longest) {
                walk.ended = true
                break
            }
            val kept = next.copy(before = next.before?.let(::beforeTail))
            walk.walked += kept
            val walkedKey = key(kept)
            val walkedStart = start(kept)
            walk.places.putIfAbsent(walkedKey, walk.walked.lastIndex)
            walk.starts.putIfAbsent(walkedStart, walk.walked.lastIndex)
            if (walkedKey == key || (walkedStart == start && opens(walk.walked.lastIndex))) return walk.walked.lastIndex
        }
        return null
    }

    private companion object {
        const val RESOURCES = 2

        // A resource longer than this many sentences is not walked further: what lies beyond gets no context.
        const val LONGEST = 50_000

        // Enough of the text before a sentence to match it, so a long resource is not held twice over.
        const val BEFORE_KEPT = 64

        fun beforeTail(text: String) = text.takeLast(BEFORE_KEPT)

        fun squash(text: String) = text.filterNot(Char::isWhitespace)

        fun key(sentence: PageSentence) =
            "${sentence.selector}\u0000${squash(sentence.text)}\u0000${squash(beforeTail(sentence.before.orEmpty()))}"

        fun start(sentence: PageSentence) =
            "${sentence.selector}\u0000${squash(beforeTail(sentence.before.orEmpty()))}"
    }
}
