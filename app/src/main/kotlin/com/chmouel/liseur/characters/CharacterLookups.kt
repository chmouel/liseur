package com.chmouel.liseur.characters

import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the character panel shows. */
sealed interface CharacterView<out P> {
    /** No server or model is chosen for character profiles yet. */
    data object Setup : CharacterView<Nothing>

    /** The reader asked in the first chapter: there is nothing before it. */
    data object FirstChapter : CharacterView<Nothing>

    /** The chapter the reader asked in could not be told, so nothing can be shown safely. */
    data object Unplaced : CharacterView<Nothing>

    /** The selection is too long to be a name. */
    data object NotAName : CharacterView<Nothing>

    data class Loading(val stage: Stage) : CharacterView<Nothing>

    data class Failed(val failure: CharacterFailure) : CharacterView<Nothing>

    data class Ambiguous<P>(val candidates: List<Candidate<P>>) : CharacterView<P>

    data object Insufficient : CharacterView<Nothing>

    data class Profile<P>(val profile: CharacterAnswer.Profile<P>) : CharacterView<P>

    enum class Stage {
        /** Finding the earlier chapters' text in the book. */
        READING,

        /** Waiting on the server, which may search and read several times. */
        ASKING,
    }
}

/** Why a lookup has no answer to show; each can be tried again. */
enum class CharacterFailure {
    CANCELLED,
    KEY,
    RATE_LIMITED,
    LOCAL_NETWORK,
    NETWORK,
    MODEL_MISSING,
    SERVER,
    MALFORMED,
    LIMIT,
    UNREADABLE,
}

/**
 * The panel for one selection.
 *
 * [scope] is fixed when the panel opens: following a source and coming
 * back, or choosing between candidates, asks about the same chapters.
 * [visible] is false while a source is shown in the book.
 */
data class CharacterPanel<P>(
    val selection: String,
    val name: String?,
    val scope: AskingScope?,
    val view: CharacterView<P>,
    val visible: Boolean = true,
    /** The name first selected, while a candidate it was ambiguous between is shown. */
    val also: String? = null,
)

/**
 * Character lookups for one open book.
 *
 * One lookup runs at a time; opening another, cancelling or closing the
 * panel cancels the request in flight. [aside] runs a lookup with the
 * book's background recaps paused, and [hints] reads what saved recaps
 * say about the chapters a scope allows. Completed answers are cached for
 * the open book under the server, model, language, asking scope, name and
 * recap hints that produced them, so tapping the same character again in
 * the same chapter is instant; an explicit [retry] bypasses the cache.
 */
class CharacterLookups<P>(
    private val scope: CoroutineScope,
    private val catalog: suspend () -> BookCatalog<P>,
    private val choice: suspend () -> CharacterChoice?,
    private val chatFor: (CharacterChoice) -> CharacterChat,
    private val hints: suspend (CharacterChoice, AskingScope, List<Passage<P>>) -> List<RecapHint>,
    private val aside: suspend (suspend () -> CharacterAnswer<P>) -> CharacterAnswer<P>,
    private val language: () -> String,
    private val limits: InvestigationLimits = InvestigationLimits(),
    private val log: (Throwable) -> Unit = {},
) {
    private data class CacheKey(
        val serverId: String,
        val model: String,
        val generation: Int,
        val language: String,
        val scope: AskingScope,
        val name: String,
        val also: String?,
        val hints: List<RecapHint>,
    )

    private val cache = object : LinkedHashMap<CacheKey, CharacterAnswer<P>>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CharacterAnswer<P>>): Boolean =
            size > CACHE_LIMIT
    }

    private val _panel = MutableStateFlow<CharacterPanel<P>?>(null)
    val panel: StateFlow<CharacterPanel<P>?> = _panel.asStateFlow()

    private var job: Job? = null

    /** Asks about [selection], selected at [place]; [translated] when the page showed a translation. */
    fun open(selection: String, place: P, translated: Boolean) {
        job?.cancel()
        val name = PassageText.name(selection)
        _panel.value = CharacterPanel(selection, name, null, CharacterView.Loading(CharacterView.Stage.READING))
        job = scope.launch {
            val asked = try {
                catalog().scopeOf(place, translated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e)
                show(CharacterView.Failed(CharacterFailure.UNREADABLE))
                return@launch
            }
            _panel.update { it?.copy(scope = asked) }
            when {
                asked == null -> show(CharacterView.Unplaced)
                !asked.hasEarlier -> show(CharacterView.FirstChapter)
                name == null -> show(CharacterView.NotAName)
                else -> ask(asked, name, null, useCache = true)
            }
        }
    }

    /** Asks again about what the panel holds, in the same chapters. */
    fun retry() = rerun(useCache = false)

    /** A server or model was chosen: a panel waiting on setup asks now. */
    fun setUp() {
        if (_panel.value?.view == CharacterView.Setup) rerun(useCache = true)
    }

    /** Looks up [candidate] instead, afresh but in the same chapters. */
    fun choose(candidate: Candidate<P>) {
        val panel = _panel.value ?: return
        _panel.value = panel.copy(name = candidate.name, also = panel.also ?: panel.name)
        rerun(useCache = true)
    }

    private fun rerun(useCache: Boolean) {
        val panel = _panel.value ?: return
        val asked = panel.scope ?: return
        val name = panel.name ?: return
        if (!asked.hasEarlier) return
        job?.cancel()
        show(CharacterView.Loading(CharacterView.Stage.READING))
        job = scope.launch { ask(asked, name, panel.also, useCache) }
    }

    fun cancel() {
        if (_panel.value?.view !is CharacterView.Loading) return
        job?.cancel()
        show(CharacterView.Failed(CharacterFailure.CANCELLED))
    }

    /** Hides the panel while a source is shown, keeping what it says. */
    fun hide() {
        _panel.update { it?.copy(visible = false) }
    }

    fun reveal() {
        _panel.update { it?.copy(visible = true) }
    }

    fun close() {
        job?.cancel()
        _panel.value = null
    }

    private fun show(view: CharacterView<P>) {
        _panel.update { it?.copy(view = view) }
    }

    private suspend fun ask(asked: AskingScope, name: String, also: String?, useCache: Boolean) {
        // A server changed between choosing and asking is asked again
        // from the start, once, so no lookup mixes two of them.
        repeat(2) { attempt ->
            val chosen = choice()
            if (chosen == null) {
                show(CharacterView.Setup)
                return
            }
            show(CharacterView.Loading(CharacterView.Stage.READING))
            try {
                val book = catalog()
                val passages = book.passagesBefore(asked)
                val offered = hints(chosen, asked, passages)
                val lang = language()
                val key = CacheKey(
                    serverId = chosen.serverId,
                    model = chosen.model,
                    generation = chosen.generation,
                    language = lang,
                    scope = asked,
                    name = PassageText.fold(name),
                    also = also?.let(PassageText::fold),
                    hints = offered,
                )
                val cached = if (useCache) synchronized(cache) { cache[key] } else null
                val answer = cached ?: aside {
                    show(CharacterView.Loading(CharacterView.Stage.ASKING))
                    Investigation(chatFor(chosen), asked, passages, lang, limits).run(name, also, offered)
                }.also { fresh ->
                    if (fresh is CharacterAnswer.Profile || fresh is CharacterAnswer.Ambiguous) {
                        synchronized(cache) { cache[key] = fresh }
                    }
                }
                show(
                    when (answer) {
                        is CharacterAnswer.Profile -> CharacterView.Profile(answer)
                        is CharacterAnswer.Ambiguous -> CharacterView.Ambiguous(answer.candidates)
                        CharacterAnswer.Insufficient -> CharacterView.Insufficient
                    },
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationError.Changed) {
                if (attempt == 1) show(CharacterView.Failed(CharacterFailure.SERVER))
            } catch (e: TranslationError.NotSetUp) {
                show(CharacterView.Setup)
                return
            } catch (e: Exception) {
                log(e)
                show(CharacterView.Failed(failureOf(e)))
                return
            }
        }
    }

    private fun failureOf(e: Exception): CharacterFailure = when (e) {
        is CharacterError.Malformed -> CharacterFailure.MALFORMED
        is CharacterError.TooManyRequests -> CharacterFailure.LIMIT
        is TranslationError.InvalidKey -> CharacterFailure.KEY
        is TranslationError.RateLimited -> CharacterFailure.RATE_LIMITED
        is TranslationError.LocalNetworkBlocked -> CharacterFailure.LOCAL_NETWORK
        is TranslationError.Network -> CharacterFailure.NETWORK
        is TranslationError.ModelNotFound -> CharacterFailure.MODEL_MISSING
        is TranslationError.Malformed, is TranslationError.Empty, is TranslationError.Truncated,
        is TranslationError.Refused -> CharacterFailure.MALFORMED
        is TranslationError -> CharacterFailure.SERVER
        else -> CharacterFailure.UNREADABLE
    }

    private companion object {
        const val CACHE_LIMIT = 64
    }
}
