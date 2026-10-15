package com.chmouel.liseur.companion

import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/** What the character panel shows. */
sealed interface CompanionView<out P> {
    /** No server or model is chosen for character profiles yet. */
    data object Setup : CompanionView<Nothing>

    /** The reader asked in the first chapter: there is nothing before it. */
    data object FirstChapter : CompanionView<Nothing>

    /** The chapter the reader asked in could not be told, so nothing can be shown safely. */
    data object Unplaced : CompanionView<Nothing>

    /** The selection is too long to be a name. */
    data object NotAName : CompanionView<Nothing>

    data class Loading(val stage: Stage) : CompanionView<Nothing>

    data class Failed(val failure: CompanionFailure, val details: FailureDetails? = null) : CompanionView<Nothing>

    data class Ambiguous<P>(val candidates: List<Candidate<P>>) : CompanionView<P>

    data object Insufficient : CompanionView<Nothing>

    data class Profile<P>(val profile: CompanionAnswer.Profile<P>) : CompanionView<P>

    enum class Stage {
        /** Finding the earlier chapters' text in the book. */
        READING,

        /** Waiting on the server, which may search and read several times. */
        ASKING,
    }
}

/** Why a lookup has no answer to show; each can be tried again. */
enum class CompanionFailure {
    CANCELLED,
    KEY,
    RATE_LIMITED,
    LOCAL_NETWORK,
    NETWORK,
    MODEL_MISSING,
    SERVER,
    MALFORMED,
    REFUSED,
    LIMIT,
    TOO_LONG,
    UNREADABLE,
}

/**
 * What is known about a failed lookup, shown to the reader on request.
 * It stays in the panel: it is never logged, cached, saved or sent.
 */
data class FailureDetails(
    /** The host asked, or null when no service was reached. */
    val host: String? = null,
    val model: String? = null,
    /** The exception's class and its fixed message, which never holds book text or replies. */
    val error: String,
    val status: Int? = null,
    /** What the server said about refusing the request, bounded. */
    val serverMessage: String? = null,
    /** The fixed codes of the reply rules the model broke, each once, in the order first broken. */
    val rules: List<String> = emptyList(),
    /** The start of the model's last unusable reply. */
    val reply: String? = null,
    /** Requests sent before the lookup failed. */
    val requests: Int = 0,
) {
    companion object {
        const val REPLY_SNIPPET = 600

        fun of(e: Throwable, choice: CompanionChoice? = null, requests: Int = 0, lastReply: String? = null): FailureDetails =
            FailureDetails(
                host = choice?.let { runCatching { URI(it.url).host }.getOrNull() ?: it.url },
                model = choice?.model,
                error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
                status = when (e) {
                    is TranslationError.Service -> e.code
                    is TranslationError.InvalidKey -> e.code
                    is TranslationError.RateLimited -> e.code
                    is TranslationError.ModelNotFound -> 404
                    else -> null
                },
                serverMessage = (e as? TranslationError)?.serverMessage,
                rules = (e as? CompanionError.Malformed)?.code?.split('+')?.filter { it.isNotEmpty() }?.distinct().orEmpty(),
                reply = lastReply?.takeIf { e is CompanionError.Malformed }?.trim()?.let {
                    if (it.length > REPLY_SNIPPET) it.take(REPLY_SNIPPET) + "…" else it
                }?.ifEmpty { null },
                requests = requests,
            )
    }
}

/**
 * The panel for one selection.
 *
 * [scope] is fixed when the panel opens: following a source and coming
 * back, or choosing between candidates, asks about the same chapters.
 * [visible] is false while a source is shown in the book.
 */
data class CompanionPanel<P>(
    val selection: String,
    val name: String?,
    val scope: AskingScope?,
    val view: CompanionView<P>,
    val visible: Boolean = true,
    /** The name first selected, while a candidate it was ambiguous between is shown. */
    val also: String? = null,
    /** Each chapter's title in the contents, where it has one, to label sources; never sent away. */
    val titles: List<String?> = emptyList(),
)

/**
 * Character lookups for one open book.
 *
 * One lookup runs at a time; opening another, cancelling or closing the
 * panel cancels the request in flight. [aside] runs a lookup with the
 * book's background recaps paused, and [hints] reads what saved recaps
 * say about the chapters a scope allows. Completed answers are cached for
 * the open book under the server, model, asking scope, name and
 * recap hints that produced them, so tapping the same character again in
 * the same chapter is instant; an explicit [retry] bypasses the cache.
 */
class CompanionLookups<P>(
    private val scope: CoroutineScope,
    private val catalog: suspend () -> BookCatalog<P>,
    private val choice: suspend () -> CompanionChoice?,
    private val chatFor: (CompanionChoice) -> CompanionChat,
    private val hints: suspend (CompanionChoice, AskingScope, List<Passage<P>>) -> List<RecapHint>,
    private val aside: suspend (suspend () -> CompanionAnswer<P>) -> CompanionAnswer<P>,
    private val log: (Throwable) -> Unit = {},
) {
    private data class CacheKey(
        val serverId: String,
        val model: String,
        val generation: Int,
        val scope: AskingScope,
        val name: String,
        val also: String?,
        val hints: List<RecapHint>,
    )

    private val cache = object : LinkedHashMap<CacheKey, CompanionAnswer<P>>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CompanionAnswer<P>>): Boolean =
            size > CACHE_LIMIT
    }

    private val _panel = MutableStateFlow<CompanionPanel<P>?>(null)
    val panel: StateFlow<CompanionPanel<P>?> = _panel.asStateFlow()

    private var job: Job? = null

    /** Asks about [selection], selected at [place]; [translated] when the page showed a translation. */
    fun open(selection: String, place: P, translated: Boolean) {
        job?.cancel()
        val name = PassageText.name(selection)
        _panel.value = CompanionPanel(selection, name, null, CompanionView.Loading(CompanionView.Stage.READING))
        job = scope.launch {
            var titles = emptyList<String?>()
            val asked = try {
                val book = catalog()
                titles = List(book.map.chapterCount) { book.map.titleOf(it) }
                book.scopeOf(place, translated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e)
                show(CompanionView.Failed(CompanionFailure.UNREADABLE, FailureDetails.of(e)))
                return@launch
            }
            _panel.update { it?.copy(scope = asked, titles = titles) }
            when {
                asked == null -> show(CompanionView.Unplaced)
                !asked.hasEarlier -> show(CompanionView.FirstChapter)
                name == null -> show(CompanionView.NotAName)
                else -> ask(asked, name, null, useCache = true)
            }
        }
    }

    /** Asks again about what the panel holds, in the same chapters. */
    fun retry() = rerun(useCache = false)

    /**
     * A server or model was chosen: a panel waiting on setup asks now, and
     * one that failed asks again, as the reader may have switched model
     * after it did.
     */
    fun setUp() {
        val view = _panel.value?.view
        if (view == CompanionView.Setup || (view is CompanionView.Failed && view.failure != CompanionFailure.CANCELLED)) {
            rerun(useCache = true)
        }
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
        show(CompanionView.Loading(CompanionView.Stage.READING))
        job = scope.launch { ask(asked, name, panel.also, useCache) }
    }

    fun cancel() {
        if (_panel.value?.view !is CompanionView.Loading) return
        job?.cancel()
        show(CompanionView.Failed(CompanionFailure.CANCELLED))
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

    private fun show(view: CompanionView<P>) {
        _panel.update { it?.copy(view = view) }
    }

    private suspend fun ask(asked: AskingScope, name: String, also: String?, useCache: Boolean) {
        // A server changed between choosing and asking is asked again
        // from the start, once, so no lookup mixes two of them.
        repeat(2) { attempt ->
            val chosen = choice()
            if (chosen == null) {
                show(CompanionView.Setup)
                return
            }
            show(CompanionView.Loading(CompanionView.Stage.READING))
            val recorded = Recorded { chatFor(chosen) }
            try {
                val book = catalog()
                val passages = book.passagesBefore(asked)
                val offered = hints(chosen, asked, passages)
                val key = CacheKey(
                    serverId = chosen.serverId,
                    model = chosen.model,
                    generation = chosen.generation,
                    scope = asked,
                    name = PassageText.fold(name),
                    also = also?.let(PassageText::fold),
                    hints = offered,
                )
                val cached = if (useCache) synchronized(cache) { cache[key] } else null
                val answer = cached ?: aside {
                    show(CompanionView.Loading(CompanionView.Stage.ASKING))
                    Investigation(recorded, asked, passages, chosen.limits.investigation).run(name, also, offered)
                }.also { fresh ->
                    if (fresh is CompanionAnswer.Profile || fresh is CompanionAnswer.Ambiguous) {
                        synchronized(cache) { cache[key] = fresh }
                    }
                }
                show(
                    when (answer) {
                        is CompanionAnswer.Profile -> CompanionView.Profile(answer)
                        is CompanionAnswer.Ambiguous -> CompanionView.Ambiguous(answer.candidates)
                        CompanionAnswer.Insufficient -> CompanionView.Insufficient
                    },
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationError.Changed) {
                if (attempt == 1) show(CompanionView.Failed(CompanionFailure.SERVER, FailureDetails.of(e, chosen, recorded.requests)))
            } catch (e: TranslationError.NotSetUp) {
                show(CompanionView.Setup)
                return
            } catch (e: Exception) {
                log(e)
                show(CompanionView.Failed(failureOf(e), FailureDetails.of(e, chosen, recorded.requests, recorded.last)))
                return
            }
        }
    }

    private fun failureOf(e: Exception): CompanionFailure = when (e) {
        is CompanionError.Malformed -> CompanionFailure.MALFORMED
        is CompanionError.TooManyRequests -> CompanionFailure.LIMIT
        is CompanionError.TooLong -> CompanionFailure.TOO_LONG
        is TranslationError.InvalidKey -> CompanionFailure.KEY
        is TranslationError.RateLimited -> CompanionFailure.RATE_LIMITED
        is TranslationError.LocalNetworkBlocked -> CompanionFailure.LOCAL_NETWORK
        is TranslationError.Network -> CompanionFailure.NETWORK
        is TranslationError.ModelNotFound -> CompanionFailure.MODEL_MISSING
        is TranslationError.Refused -> CompanionFailure.REFUSED
        is TranslationError.Malformed, is TranslationError.Empty, is TranslationError.Truncated -> CompanionFailure.MALFORMED
        is TranslationError -> CompanionFailure.SERVER
        else -> CompanionFailure.UNREADABLE
    }

    /** A chat that counts the requests it lets go and keeps its last reply, for [FailureDetails]. */
    private class Recorded(make: () -> CompanionChat) : CompanionChat {
        private val chat by lazy(make)

        @Volatile var requests = 0
            private set

        @Volatile var last: String? = null
            private set

        override suspend fun reply(messages: List<ChatMessage>, beforeRequest: () -> Unit): String {
            last = null
            return chat.reply(messages) {
                beforeRequest()
                requests++
            }.also { last = it }
        }
    }

    private companion object {
        const val CACHE_LIMIT = 64
    }
}
