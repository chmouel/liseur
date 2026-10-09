package com.chmouel.liseur.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A sentence of the book as the page shows it: its resource, the element
 * it sits in, and the text before it in that element, which together
 * find it again in the page.
 */
data class PageSentence(val text: String, val href: String, val selector: String?, val before: String?)

/** The book's sentences from a place onwards, the way read aloud walks them. */
fun interface PageSentences {
    /** The next sentence, or null at the end of the book. */
    suspend fun next(): PageSentence?
}

/** A sentence's translation, to put in the page where the sentence was. [id] counts them in the run. */
data class PageSwap(val id: Int, val sentence: PageSentence, val translation: String)

/**
 * One service translating sentences between two languages for as long as
 * a page is translated, from [TranslateFeature.openPage].
 */
interface SentenceTranslator {
    /** Null when the service works the language out. */
    val source: String?
    val target: String

    /**
     * Names the service, model and languages that answer now, following
     * the settings there first, so a remembered translation is never another's.
     */
    suspend fun answering(): String

    /** Where the text goes now, for "Translated by %s."; null for the device. */
    val destination: String?

    /** [sentence] translated, with [context] as the sentence before it. Throws [TranslationError]. */
    suspend fun translate(sentence: String, context: String?): String

    /** Lets go of what the service held for the run, such as the device's translator. */
    fun close()
}

/** Where a run stands, for the bar under the page. */
sealed interface PageTranslationState {
    /** A sentence is on its way. */
    data object Translating : PageTranslationState

    /** Far enough ahead of the reader for now. */
    data object Ahead : PageTranslationState

    /** Nothing left to translate in the book. */
    data object Ended : PageTranslationState

    /** Stopped on something only the reader can fix, such as a refused key, until [PageTranslation.retry]. */
    data class Halted(val error: TranslationError) : PageTranslationState
}

/** Where the reader is, as the page says after it moved. */
sealed interface PageReader<out P> {
    /** The last of the walk's sentences on screen, or the last one the reader has gone past with more of the walk to come. */
    data class Within(val sentence: PageSentence) : PageReader<Nothing>

    /** The reader has gone past all of the walk: what lies between is passed over without being asked. */
    data object Beyond : PageReader<Nothing>

    /**
     * None of the walk is on screen: the walk starts again from [place],
     * which [key] names. [before] says the page found the walk's sentences
     * still to come, so the reader went back, even within the element
     * [key] names.
     */
    data class Elsewhere<P>(val place: P, val key: String, val before: Boolean = false) : PageReader<P>
}

/**
 * Translations of sentences already asked, by service, model, languages,
 * sentence and the sentence before it, so walking back over a page or
 * opening the book again costs nothing. One book's, for one opening of it.
 */
interface PageTranslationCache {
    /** What [key] was translated into, if kept, and the [Lookup.stamp] to keep a new answer under. */
    suspend fun get(key: String): Lookup

    /** Keeps [value] for [key], unless what [stamp] was read under has been cleared since. */
    suspend fun put(key: String, value: String, stamp: Long)

    data class Lookup(val translation: String?, val stamp: Long)
}

/** Kept only while the book is open: the reader's when nothing is saved. */
class MemoryPageTranslationCache(private val capacity: Int = CAPACITY) : PageTranslationCache {
    private val entries = object : LinkedHashMap<String, String>(CAPACITY_HINT, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    override suspend fun get(key: String) = PageTranslationCache.Lookup(entries[key], 0L)

    override suspend fun put(key: String, value: String, stamp: Long) {
        entries[key] = value
    }

    private companion object {
        const val CAPACITY = 2_000
        const val CAPACITY_HINT = 64
        const val LOAD = 0.75f
    }
}

/**
 * Translates the book a sentence at a time from where the reader asked,
 * a little ahead of them and no further: each sentence is asked once the
 * one before it has come back, with that one as context, and stops while
 * [ahead] sentences past the last one on screen are done. A reader who
 * turns to a place the walk has not reached starts it again from there;
 * one who has gone past it in the same chapter is caught up with, the
 * sentences [behind] the screen walked over without being asked.
 *
 * Everything here runs on [scope]'s thread, so the page's reports and the
 * walk never interleave mid-step.
 */
internal class PageTranslation<P>(
    private val scope: CoroutineScope,
    val translator: SentenceTranslator,
    private val cache: PageTranslationCache,
    private val sentencesFrom: (P) -> PageSentences,
    private val ahead: Int = AHEAD,
    private val behind: suspend (PageSentence) -> Boolean = { false },
) {
    private val mutableState = MutableStateFlow<PageTranslationState>(PageTranslationState.Translating)
    val state: StateFlow<PageTranslationState> = mutableState.asStateFlow()

    private val mutableSwaps = MutableStateFlow<Map<String, List<PageSwap>>>(emptyMap())

    /** Every translation the run has put in the page, by resource, in the order they came. */
    val swaps: StateFlow<Map<String, List<PageSwap>>> = mutableSwaps.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val walked = mutableListOf<PageSentence>()
    private val placed = mutableSetOf<PageSentence>()

    // The sentence the walk read before each one it translated, kept when a jump starts the walk over.
    private val predecessors = mutableMapOf<PageSentence, PageSentence>()
    private var seen = -1
    private var restart: PageReader.Elsewhere<P>? = null
    private var catchingUp = false
    private var startedAt: String? = null
    private var halted: TranslationError? = null
    private var nextId = 0
    private var job: Job? = null
    private var asking: Job? = null

    fun start(place: P, key: String) {
        check(job == null) { "Already started" }
        startedAt = key
        job = scope.launch { walk(sentencesFrom(place)) }
    }

    /** The latest sentences of the walk in [href], in reading order, for asking the page where the reader is. */
    fun walkedIn(href: String): List<PageSentence> {
        // The walk goes in reading order, so a resource's sentences are together, near the end.
        val found = ArrayDeque<PageSentence>()
        for (i in walked.indices.reversed()) {
            val sentence = walked[i]
            if (sentence.href == href) {
                found.addFirst(sentence)
                if (found.size == LOOKBACK) break
            } else if (found.isNotEmpty()) {
                break
            }
        }
        return found.toList()
    }

    /**
     * The book's sentence whose translation shows [selected] in [href], so
     * read aloud can start from the words the book has; when the selection
     * runs on into the next translation, the one it starts in. A sentence
     * the walk left in the original is found by its own words, since the
     * text before it may still be translated. [before] is
     * the page's text before the selection; it picks between translations
     * that both contain the selected words, and rules out one that only
     * happens to contain words selected elsewhere.
     */
    fun original(href: String, selected: String, before: String?): PageSentence? {
        val key = squash(selected).take(MATCHED)
        if (key.isEmpty()) return null
        val leading = squash(before.orEmpty())
        // After the translations, so a sentence's own words are only ever a fallback for it.
        val untranslated = walked.filter { it.href == href && it !in placed }.map { PageSwap(-1, it, it.text) }
        val swaps = mutableSwaps.value[href].orEmpty() + untranslated
        return swaps
            .mapIndexedNotNull { i, swap ->
                val shown = squash(swap.translation)
                // Swaps come in the order they were made, which a jump or a refused sentence breaks; the text says what follows what.
                val next = swaps.firstOrNull { follows(swap.sentence, it.sentence) } ?: swaps.getOrNull(i + 1)
                // Every place the words are in it, since a translation can say them twice.
                val places = generateSequence(shown.indexOf(key).takeIf { it >= 0 }) { from ->
                    shown.indexOf(key, from + 1).takeIf { it >= 0 }
                }.toList().ifEmpty { listOfNotNull(runsOn(shown, key, next?.let { squash(it.translation) })) }
                // Just before it on the page: the translation of the sentence before it, or the book's own words when that one was left alone.
                val book = squash(swap.sentence.before.orEmpty())
                // At the start of an element nothing in it comes before; the walk knows what the page shows above it.
                val above = (predecessors[swap.sentence] ?: walked.getOrNull(walked.indexOf(swap.sentence) - 1))
                    ?.takeIf { it.href == swap.sentence.href }
                val earlier = listOfNotNull(
                    swaps.firstOrNull { follows(it.sentence, swap.sentence) }?.let { squash(it.translation) },
                    swaps.getOrNull(i - 1)
                        ?.takeIf { it.sentence.href == swap.sentence.href && it.sentence.selector == swap.sentence.selector }
                        ?.let { squash(it.translation) },
                    above?.let { previous -> squash(swaps.firstOrNull { it.sentence == previous }?.translation ?: previous.text) },
                    // Nothing before it on the page only when nothing is above it.
                    book.takeLast(BOOK_TAIL).takeUnless { it.isEmpty() && above != null },
                )
                // Words that only happen to be in a translation are not on the page after it.
                val lead = places
                    .flatMap { at -> earlier.map { (it + shown.substring(0, at)).takeLast(MATCHED) } }
                    .filter(leading::endsWith)
                    .maxByOrNull { it.length }
                    ?: return@mapIndexedNotNull null
                swap.sentence to lead.length
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Whether [sentence] comes straight after [previous] in the same element, as the text before it says. */
    private fun follows(previous: PageSentence, sentence: PageSentence): Boolean {
        if (previous === sentence || previous.href != sentence.href || previous.selector != sentence.selector) return false
        val before = squash(sentence.before.orEmpty())
        val text = squash(previous.text)
        if (before.isEmpty() || text.isEmpty()) return false
        // The text before a sentence can stop partway into a long one.
        return before.endsWith(text) || (before.length >= MATCHED && text.endsWith(before))
    }

    fun onReader(reader: PageReader<P>) {
        when (reader) {
            is PageReader.Within -> {
                val at = walked.indexOfLast { it == reader.sentence }
                if (at < 0) return
                seen = at
            }
            PageReader.Beyond -> catchingUp = true
            is PageReader.Elsewhere -> {
                // The page answers the same way until the walk reaches it, which is no reason to start over.
                // A reader back before what the walk has done is: a restart clears the walk, so this happens once.
                if (reader.key == startedAt && !(reader.before && walked.isNotEmpty())) return
                startedAt = reader.key
                restart = reader
                // The sentence on its way is for the place the reader left, and a slow service would hold the jump up.
                asking?.cancel()
            }
        }
        wake.trySend(Unit)
    }

    /** Takes up again after [PageTranslationState.Halted], from the sentence that stopped it. */
    fun retry() {
        halted = null
        wake.trySend(Unit)
    }

    /** Stops asking and lets go of the translator. What was put in the page stays in [swaps]. */
    fun stop() {
        job?.cancel()
        wake.close()
        translator.close()
    }

    private suspend fun walk(first: PageSentences) {
        var sentences = first
        var previous: String? = null
        var pending: PageSentence? = null
        var ended = false
        while (true) {
            restart?.let {
                restart = null
                sentences = sentencesFrom(it.place)
                walked.clear()
                seen = -1
                previous = null
                pending = null
                ended = false
                catchingUp = false
            }
            val error = halted
            val waiting = when {
                error != null -> PageTranslationState.Halted(error)
                ended -> PageTranslationState.Ended
                walked.size - 1 - seen >= ahead && !catchingUp -> PageTranslationState.Ahead
                else -> null
            }
            if (waiting != null) {
                mutableState.value = waiting
                wake.receive()
                continue
            }
            val sentence = pending ?: sentences.next()
            if (sentence == null) {
                ended = true
                continue
            }
            if (catchingUp) {
                if (behind(sentence)) {
                    pending = null
                    walked += sentence
                    seen = walked.size - 1
                    previous = sentence.text
                    continue
                }
                catchingUp = false
            }
            pending = sentence
            mutableState.value = PageTranslationState.Translating
            val translation = try {
                coroutineScope {
                    val asked = async { translated(sentence, previous) }
                    asking = asked
                    asked.await()
                }
            } catch (e: CancellationException) {
                // Dropped for a jump: the walk starts again where the reader went. A stopped run ends here.
                currentCoroutineContext().ensureActive()
                if (restart == null) throw e
                continue
            } catch (e: TranslationError) {
                if (e.halts) {
                    halted = e
                    continue
                }
                // This sentence only: it stays in the original and the walk goes on.
                null
            }
            asking = null
            pending = null
            walked += sentence
            previous = sentence.text
            if (translation != null && placed.add(sentence)) {
                walked.getOrNull(walked.size - 2)?.let { predecessors[sentence] = it }
                val swap = PageSwap(nextId++, sentence, translation)
                mutableSwaps.value += sentence.href to (mutableSwaps.value[sentence.href].orEmpty() + swap)
            }
        }
    }

    private suspend fun translated(sentence: PageSentence, context: String?): String {
        fun keyOf(identity: String) = listOf(identity, context.orEmpty(), sentence.text).joinToString("\u0000")
        var identity = translator.answering()
        var lookup = cache.get(keyOf(identity))
        // Settings changed during the lookup: what was found answers for the service before.
        while (true) {
            val current = translator.answering()
            if (current == identity) break
            identity = current
            lookup = cache.get(keyOf(identity))
        }
        lookup.translation?.let { return it }
        // Settings changed while it was asked may have sent it to another service.
        return translator.translate(sentence.text, context).also {
            // Far longer than the sentence is not its translation, and it would be held for the whole session.
            if (it.length > maxOf(LONGEST, sentence.text.length * GROWTH)) {
                throw TranslationError.Malformed("the translation is far longer than the sentence")
            }
            if (translator.answering() == identity) cache.put(keyOf(identity), it, lookup.stamp)
        }
    }

    private val TranslationError.halts: Boolean
        get() = when (this) {
            is TranslationError.Refused, is TranslationError.Empty,
            is TranslationError.Truncated, is TranslationError.Malformed,
            -> false
            else -> true
        }

    companion object {
        /** How many sentences past the last one on screen are kept translated. */
        const val AHEAD = 12

        /** How far back the page is asked about: a reader further back than this has jumped. */
        const val LOOKBACK = 200

        // Enough of a selection, and of the text before it, to tell sentences apart.
        private const val MATCHED = 40

        // A translation can run longer than its sentence, but not by this much.
        private const val LONGEST = 2_000
        private const val GROWTH = 8

        // Enough of the book's words before a sentence left untranslated to see they are on the page.
        private const val BOOK_TAIL = 8

        // The page and the translation differ in line breaks and collapsed spaces.
        private fun squash(text: String) = text.filterNot(Char::isWhitespace)

        /** Where in [shown] a [key] starts that goes on into [next], the translation after it on the page. */
        private fun runsOn(shown: String, key: String, next: String?): Int? {
            if (next.isNullOrEmpty()) return null
            return (maxOf(0, shown.length - key.length + 1) until shown.length).firstOrNull { at ->
                val head = shown.substring(at)
                val rest = key.substring(head.length)
                key.startsWith(head) && (next.startsWith(rest) || rest.startsWith(next))
            }
        }
    }
}
