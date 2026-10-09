package com.chmouel.liseur.translate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

    /** None of the walk is on screen: the walk starts again from [place], which [key] names. */
    data class Elsewhere<P>(val place: P, val key: String) : PageReader<P>
}

/**
 * Translations of sentences already asked, by service, model, languages,
 * sentence and the sentence before it, so walking back over a page or
 * starting again costs nothing. Kept while the book is open.
 */
class PageTranslationCache(private val capacity: Int = CAPACITY) {
    private val entries = object : LinkedHashMap<String, String>(CAPACITY_HINT, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }

    operator fun get(key: String): String? = entries[key]

    operator fun set(key: String, value: String) {
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
    private var seen = -1
    private var restart: PageReader.Elsewhere<P>? = null
    private var catchingUp = false
    private var startedAt: String? = null
    private var halted: TranslationError? = null
    private var nextId = 0
    private var job: Job? = null

    fun start(place: P, key: String) {
        check(job == null) { "Already started" }
        startedAt = key
        job = scope.launch { walk(sentencesFrom(place)) }
    }

    /** The latest sentences of the walk in [href], in reading order, for asking the page where the reader is. */
    fun walkedIn(href: String): List<PageSentence> = walked.filter { it.href == href }.takeLast(LOOKBACK)

    /**
     * The book's sentence whose translation shows [selected] in [href], so
     * read aloud can start from the words the book has. [before] is the
     * page's text before the selection; it picks between translations that
     * both contain the selected words.
     */
    fun original(href: String, selected: String, before: String?): PageSentence? {
        val key = squash(selected).take(MATCHED)
        if (key.isEmpty()) return null
        val leading = squash(before.orEmpty())
        val swaps = mutableSwaps.value[href].orEmpty()
        return swaps
            .mapIndexedNotNull { i, swap ->
                val shown = squash(swap.translation)
                val at = shown.indexOf(key)
                if (at < 0) return@mapIndexedNotNull null
                // The sentence before it in the same element is on the page just before it.
                val earlier = swaps.getOrNull(i - 1)
                    ?.takeIf { it.sentence.href == swap.sentence.href && it.sentence.selector == swap.sentence.selector }
                    ?.let { squash(it.translation) }
                    .orEmpty()
                val lead = (earlier + shown.substring(0, at)).takeLast(MATCHED)
                swap.sentence to if (leading.endsWith(lead)) lead.length else -1
            }
            .maxByOrNull { it.second }
            ?.first
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
                if (reader.key == startedAt) return
                startedAt = reader.key
                restart = reader
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
                translated(sentence, previous)
            } catch (e: TranslationError) {
                if (e.halts) {
                    halted = e
                    continue
                }
                // This sentence only: it stays in the original and the walk goes on.
                null
            }
            pending = null
            walked += sentence
            previous = sentence.text
            if (translation != null && placed.add(sentence)) {
                val swap = PageSwap(nextId++, sentence, translation)
                mutableSwaps.value += sentence.href to (mutableSwaps.value[sentence.href].orEmpty() + swap)
            }
        }
    }

    private suspend fun translated(sentence: PageSentence, context: String?): String {
        val identity = translator.answering()
        val key = listOf(identity, context.orEmpty(), sentence.text).joinToString("\u0000")
        cache[key]?.let { return it }
        // Settings changed while it was asked may have sent it to another service.
        return translator.translate(sentence.text, context).also { if (translator.answering() == identity) cache[key] = it }
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

        // The page and the translation differ in line breaks and collapsed spaces.
        private fun squash(text: String) = text.filterNot(Char::isWhitespace)
    }
}
