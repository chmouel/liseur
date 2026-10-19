package com.chmouel.liseur.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One translator's answers for one book, shared by everything reading
 * that book translated: the page and read aloud. The same sentence, in
 * the same place, is asked once and shown and spoken in the same words.
 *
 * A sentence's translation is kept by where it is ([PageSentence]) once
 * accepted, so the page shows what the voice said and the voice says
 * what the page shows, whoever asked first. A sentence asked twice at
 * once, with the same sentence before it, waits for the first request.
 * Answers are filed under what answered, so a change of service, model
 * or languages is never answered from the old one. A failure the page
 * leaves in the original is shared with the voice until that sentence is
 * retried or the page walk ends.
 *
 * The text that goes with a sentence is the book's own before it, found
 * by [preceding] from where the sentence is and cut to what the
 * answering service takes ([SentenceTranslator.contextSize]), so the
 * page and the voice send the same whoever asks first. Without
 * [preceding], or where it cannot find the sentence, the caller's
 * sentence before goes instead. What is known of the book where the
 * sentence is comes from [book], by the sentence's resource.
 *
 * Held by [acquire] and [release]: it starts held once, by whoever
 * opened it, and closes the translator when the last holder lets go.
 * Main thread only, like the page and the voice.
 */
class SentenceTranslations(
    val translator: SentenceTranslator,
    private val cache: PageTranslationCache,
    private val preceding: PrecedingText? = null,
    private val book: (suspend (href: String) -> BookFacts?)? = null,
) {
    private val requests = SentenceRequests(translator, preceding, book)
    private data class PageFailure(
        val scope: PageFailureScope,
        val identity: String,
        val before: String?,
        val error: TranslationError,
    )
    private class BoundFailure(val identity: String, val error: TranslationError) : Exception(error)

    private var holders = 1
    private val inflight = HashMap<Triple<String, String?, String?>, CompletableDeferred<Translated>>()
    private var pageScope: PageFailureScope? = null

    // Accepted translations by resource, element and sentence; the text before tells repeated ones apart.
    private val accepted = HashMap<Triple<String, String?, String>, MutableList<Pair<String?, Translated>>>()

    // Failures the active page walk left in the original, shared with read aloud until it retries or ends.
    private val pageFailures = HashMap<Triple<String, String?, String>, MutableList<PageFailure>>()

    /** Whether the translator is still open. */
    val open: Boolean get() = holders > 0

    /** Holds the translator open for another user; false when it has already closed. */
    fun acquire(): Boolean {
        if (holders <= 0) return false
        holders++
        return true
    }

    /** Lets go of one hold; the last one closes the translator. */
    fun release() {
        if (holders <= 0) return
        if (--holders == 0) {
            inflight.values.forEach { it.cancel() }
            inflight.clear()
            pageScope = null
            pageFailures.clear()
            translator.close()
        }
    }

    /** Starts a page walk whose untranslated failures may be shared with read aloud. */
    internal fun beginPageWalk(): PageFailureScope {
        val scope = PageFailureScope()
        pageScope = scope
        pageFailures.clear()
        return scope
    }

    /** Ends [scope] without clearing failures from a newer page walk. */
    internal fun endPageWalk(scope: PageFailureScope) {
        if (pageScope !== scope) return
        pageScope = null
        pageFailures.clear()
    }

    /**
     * [sentence] translated, with [context] as the sentence before it:
     * the translation already accepted for it under the service answering
     * now, else one asked. Throws [TranslationError].
     */
    suspend fun resolve(sentence: PageSentence, context: String?): Translated {
        val identity = translator.answering()
        return try {
            resolve(sentence, context, identity)
        } catch (e: BoundFailure) {
            throw e.error
        }
    }

    /** The active page walk's request for [sentence], recording failures under the identity that actually failed. */
    internal suspend fun resolveForPage(
        scope: PageFailureScope,
        sentence: PageSentence,
        context: String?,
    ): Translated {
        if (pageScope !== scope) throw CancellationException()
        clearPageFailure(scope, sentence)
        val identity = translator.answering()
        return try {
            resolve(sentence, context, identity)
        } catch (e: BoundFailure) {
            acceptedFor(sentence, e.identity)?.let {
                clearPageFailure(scope, sentence, e.identity)
                return it
            }
            recordPageFailure(scope, sentence, e.error, e.identity)
            throw e.error
        }
    }

    private suspend fun resolve(sentence: PageSentence, context: String?, identity: String): Translated {
        pageFailureFor(sentence)?.let { throw it }
        acceptedFor(sentence, identity)?.let { return it }
        // What is sent, so the page and the voice asking for one place share the request.
        val prepared = requests.prepare(sentence, context, identity)
        val key = Triple(sentence.text, prepared.context, prepared.book)
        while (true) {
            val pending = inflight[key]
            if (pending != null) {
                try {
                    return settled(sentence, pending.await())
                } catch (e: CancellationException) {
                    // The one who asked went away: this caller asks, unless it was cancelled too.
                    currentCoroutineContext().ensureActive()
                    if (inflight[key] === pending) inflight.remove(key)
                    continue
                }
            }
            val mine = CompletableDeferred<Translated>()
            inflight[key] = mine
            try {
                val answer = translated(sentence, context)
                mine.complete(answer)
                return settled(sentence, answer)
            } catch (e: CancellationException) {
                mine.cancel(e)
                throw e
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                if (inflight[key] === mine) inflight.remove(key)
            }
        }
    }

    /** The translation accepted for [sentence] under [identity], if any. */
    fun acceptedFor(sentence: PageSentence, identity: String): Translated? =
        accepted[Triple(sentence.href, sentence.selector, sentence.text)]
            ?.firstOrNull { (before, translated) -> translated.by == identity && sameBefore(before, sentence.before) }
            ?.second

    /** The failure the active page walk left for [sentence] under [identity], if any. */
    private fun pageFailureFor(sentence: PageSentence): TranslationError? {
        val scope = pageScope ?: return null
        return pageFailures[Triple(sentence.href, sentence.selector, sentence.text)]
            ?.firstOrNull {
                it.scope === scope && sameBefore(it.before, sentence.before)
            }
            ?.error
    }

    private fun recordPageFailure(
        scope: PageFailureScope,
        sentence: PageSentence,
        error: TranslationError,
        identity: String,
    ) {
        if (pageScope !== scope) return
        val list = pageFailures.getOrPut(Triple(sentence.href, sentence.selector, sentence.text)) { mutableListOf() }
        if (list.none { it.scope === scope && it.identity == identity && sameBefore(it.before, sentence.before) }) {
            list += PageFailure(scope, identity, sentence.before, error)
        }
    }

    private fun clearPageFailure(scope: PageFailureScope, sentence: PageSentence, identity: String? = null) {
        val key = Triple(sentence.href, sentence.selector, sentence.text)
        val list = pageFailures[key] ?: return
        list.removeAll {
            it.scope === scope && (identity == null || it.identity == identity) && sameBefore(it.before, sentence.before)
        }
        if (list.isEmpty()) pageFailures.remove(key)
    }

    // Another context may have been accepted for this place meanwhile: the first one stands, shown and spoken alike.
    private fun settled(sentence: PageSentence, translated: Translated): Translated {
        pageFailureFor(sentence)?.let { throw it }
        accept(sentence, translated)
        return acceptedFor(sentence, translated.by) ?: translated
    }

    private fun accept(sentence: PageSentence, translated: Translated) {
        val list = accepted.getOrPut(Triple(sentence.href, sentence.selector, sentence.text)) { mutableListOf() }
        if (list.none { (before, kept) -> kept.by == translated.by && sameBefore(before, sentence.before) }) {
            list += sentence.before to translated
        }
    }

    private suspend fun translated(sentence: PageSentence, fallback: String?): Translated {
        while (true) {
            val identity = translator.answering()
            val prepared = requests.prepare(sentence, fallback, identity)
            val cached = cache.get(prepared.key)
            if (translator.answering() != identity) continue
            cached.translation?.let { return Translated(it, identity) }
            if (cached.refused) throw BoundFailure(identity, TranslationError.Refused())
            val answer = cache.request(identity) { translatedAdmitted(sentence, fallback, identity) }
            if (answer != null) return answer
        }
    }

    private suspend fun translatedAdmitted(sentence: PageSentence, fallback: String?, identity: String): Translated? {
        val text = sentence.text
        if (translator.answering() != identity) return null
        val prepared = requests.prepare(sentence, fallback, identity)
        // Kept from the lookup: rebuilt later, it could name settings changed meanwhile.
        val lookup = cache.get(prepared.key)
        var answer: Translated? = null
        while (answer == null) {
            // Settings changed during the lookup: what was found answers for the service before.
            val current = translator.answering()
            if (current != identity) return null
            lookup.translation?.let { return Translated(it, identity) }
            if (lookup.refused) throw BoundFailure(identity, TranslationError.Refused())
            // Null when they changed after all, before it was sent: the new service may have it saved.
            answer = try {
                translator.translate(text, prepared.context, identity, prepared.facts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationError) {
                throw BoundFailure(identity, e)
            }
            if (answer == null) return null
        }
        // Far longer than the sentence is not its translation, and it would be held for the whole session.
        try {
            checkTranslationLength(text, answer.text)
        } catch (e: TranslationError) {
            throw BoundFailure(answer.by, e)
        }
        // Filed under what answered, and only while that is still what the settings choose.
        if (answer.by == identity && translator.answering() == identity) cache.put(prepared.key, answer.text, lookup.stamp)
        return answer
    }

    companion object {
        // Enough of the text before a sentence to tell where it is: one side may hold less of it than the other.
        private const val BEFORE_MATCHED = 24

        private fun squash(text: String?) = text.orEmpty().filterNot(Char::isWhitespace)

        /** Whether two accounts of the text before a sentence agree, one possibly cut shorter than the other. */
        internal fun sameBefore(a: String?, b: String?): Boolean {
            val x = squash(a)
            val y = squash(b)
            if (x.isEmpty() || y.isEmpty()) return x.isEmpty() && y.isEmpty()
            val shorter = minOf(x.length, y.length, BEFORE_MATCHED)
            return x.takeLast(shorter) == y.takeLast(shorter)
        }
    }

    internal class PageFailureScope
}
