package com.chmouel.liseur.companion

import android.util.Log
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator

/**
 * The Story tab of Codex for the open book: the earlier chapters with their
 * recaps, the running summary over them, and the work the reader asked for.
 *
 * Shown for one frozen scope at a time ([show]); hiding it stops only the
 * view, and the work asked for goes on while the book is open. Nothing is
 * sent on the reader's behalf before they allow it once ([allow]).
 */
internal class StoryController(
    private val scope: CoroutineScope,
    private val book: CompanionStore.BookRecaps,
    private val catalog: PublicationCatalog,
    private val scheduler: RecapScheduler<Locator>,
    private val choice: StateFlow<CompanionChoice?>,
    private val allowed: StateFlow<Boolean>,
    /** Allows at once, so [allowed] is true before this returns; storing it may finish later. */
    private val setAllowed: () -> Unit,
) {
    private val events = StoryEvents()
    private val stages = MutableStateFlow(0)
    private val decoder = StoryDecoder()
    private val earlier = object : LinkedHashMap<AskingScope, List<EarlierChapter<Locator>>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AskingScope, List<EarlierChapter<Locator>>>) = size > 4
    }
    private val tops = MutableStateFlow<List<SavedSummary>?>(null)
    private val summaryFailure = MutableStateFlow<Pair<String, RecapFailure>?>(null)
    // Written automatically once each; a reader's tap writes again.
    private val autoWritten = mutableSetOf<String>()
    private var planned: Pair<List<SummaryInput>, SummaryStep?>? = null

    private val _view = MutableStateFlow<StoryView?>(null)
    val view: StateFlow<StoryView?> = _view.asStateFlow()

    private var shown: AskingScope? = null
    private var lastShown: AskingScope? = null
    private var job: Job? = null
    private var rows: List<StoryChapter<Locator>> = emptyList()
    private var top: SummaryStep? = null

    fun show(asked: AskingScope) {
        if (asked == shown && job?.isActive == true) return
        job?.cancel()
        shown = asked
        rows = emptyList()
        top = null
        // The same scope back after a peek keeps its view while it refreshes, so the list keeps its place.
        if (asked != lastShown || _view.value == null) _view.value = StoryView()
        lastShown = asked
        job = scope.launch {
            try {
                val chapters = earlier[asked] ?: catalog.chaptersBefore(asked).also { earlier[asked] = it }
                val listed = chapters.associate { it.unit to catalog.map.unitKey(it.unit) }
                if (tops.value == null) refreshTops()
                val touched = HashSet<String>()
                var shownTop: String? = null
                val state = combine(scheduler.activity, allowed, stages, tops, summaryFailure) { a, ok, _, saved, failure ->
                    Inputs(a, ok, saved, failure)
                }
                combine(book.watch().distinctUntilChanged(), state, choice, ::Triple).collect { (saved, inputs, chosen) ->
                    val stored = Story.chapters(catalog.map, chapters, if (chapters.isEmpty()) emptyList() else saved, decoder::of)
                    val failed = { f: RecapFailure -> failedView(f, chosen) }
                    var current = Story.progress(stored, inputs.activity, failed)
                    // Live, not inputs.allowed: consent may have been revoked while this collector was suspended.
                    val picks = events.shown(asked, allowed.value, current)
                    if (picks.isNotEmpty()) {
                        request(asked, current, picks, automatic = true)
                        current = Story.progress(stored, scheduler.activity.value, failed)
                    }
                    rows = current
                    val fresh = Story.shown(catalog.map, stored).filter { touched.add(it) }
                    if (fresh.isNotEmpty()) quietly { book.touch(fresh) }

                    val step = plan(stored.map { StorySummaries.input(it.unit, listed.getValue(it.unit), it.recap) }, asked.lo)
                    top = step
                    val best = StorySummaries.best(inputs.saved.orEmpty(), listed, step?.key)
                    if (best != null && best.key != shownTop) {
                        shownTop = best.key
                        quietly { book.summaries.touch(best.key) }
                    }
                    val upToDate = StorySummaries.written(inputs.saved.orEmpty(), step)
                    fun writesOnItsOwn() = step != null && !upToDate && StorySummaries.writable(step) &&
                        step.key !in autoWritten && events.automatic(asked, allowed.value) &&
                        scheduler.activity.value.let { !it.summarizing && it.waiting.isEmpty() }
                    // The lane can drain before Room delivers the last recap; a summary of the older rows would be
                    // paid twice. After that read, everything is checked again with no suspension before queueing,
                    // so a Stop, a revoked consent or a Write tapped meanwhile wins.
                    if (writesOnItsOwn() && quietly { book.watch().first() } == saved && writesOnItsOwn()) {
                        autoWritten.add(step!!.key)
                        write(step, automatic = true)
                    }
                    _view.value = StoryView(
                        loading = false,
                        chapters = current,
                        asking = !inputs.allowed && events.stage(asked) == StoryEvents.Stage.ASKING && chapters.isNotEmpty(),
                        service = chosen?.let(::serviceName),
                        soFar = StorySoFar(
                            text = best?.text,
                            through = best?.deps?.lastOrNull()?.unit,
                            unsummarized = best?.deps.orEmpty().filterNot { it.summarized }.map { it.unit },
                            current = upToDate,
                            writable = StorySummaries.writable(step),
                            writing = scheduler.activity.value.summarizing,
                            failed = inputs.failure?.takeIf { it.first == step?.key }?.second?.let { failed(it) },
                        ),
                        work = scheduler.activity.value.let { a ->
                            if (a.waiting.isEmpty() && !a.summarizing) null
                            else StoryWork(a.waiting.count { it != a.working }, a.working, a.batch, a.batches, a.summarizing)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Story failed: ${e.javaClass.simpleName}")
                _view.update { (it ?: StoryView()).copy(loading = false, failed = true) }
            }
        }
    }

    /** The tab is no longer on screen; what was asked for goes on. */
    fun hide() {
        job?.cancel()
        job = null
        shown = null
    }

    /** The reader allows Story to send on its own, from now on and in every book. */
    fun allow() {
        val asked = shown ?: return
        setAllowed()
        request(asked, rows, events.allow(asked, rows), automatic = true)
        stages.update { it + 1 }
    }

    /** Not now: nothing is sent on its own for this opening. */
    fun decline() {
        val asked = shown ?: return
        events.decline(asked)
        stages.update { it + 1 }
    }

    /** Recaps [unit] now; a recap that cannot be read is written again. */
    fun summarize(unit: Int) {
        val asked = shown ?: return
        request(asked, rows, listOf(unit))
    }

    /** Recaps every missing chapter, nearest first. */
    fun summarizeMissing() {
        val asked = shown ?: return
        request(asked, rows, Story.missing(rows))
    }

    /** Writes the running summary now. */
    fun write() {
        if (shown == null) return
        val step = top?.takeIf(StorySummaries::writable) ?: return
        write(step, automatic = false)
    }

    /** Stops everything Story asked for; nothing more starts on its own for this opening. */
    fun stop() {
        // Before the scheduler empties: a collector resuming inline would otherwise start the summary again.
        shown?.let(events::stop)
        scheduler.stop()
        stages.update { it + 1 }
    }

    private fun request(asked: AskingScope, chapters: List<StoryChapter<Locator>>, units: List<Int>, automatic: Boolean = false) {
        val byUnit = chapters.associateBy { it.unit }
        val requests = units.mapNotNull { byUnit[it] }.filter { it.askable }.map { chapter ->
            RecapRequest(
                RecapUnit(chapter.unit, catalog.map.unitKey(chapter.unit)),
                ceiling = asked.lo,
                replace = chapter.recap == StoryRecap.Unreadable,
                automatic = automatic,
            )
        }
        scheduler.request(requests)
    }

    private fun write(step: SummaryStep, automatic: Boolean) {
        summaryFailure.value = null
        scheduler.summarize(
            SummaryWork(step.last, automatic) { chat, allowed ->
                val counted = CountedChat(chat)
                try {
                    SummaryWriter(book.summaries, counted, catalog.map.chapterCount).write(step, allowed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write the story so far: ${CompanionError.describe(e)}")
                    summaryFailure.value = step.key to
                        RecapFailure(CompanionError.failureOf(e), FailureDetails.of(e, requests = counted.requests))
                } finally {
                    refreshTops()
                }
            },
        )
    }

    private suspend fun plan(inputs: List<SummaryInput>, ceiling: Int): SummaryStep? {
        planned?.let { (was, step) -> if (was == inputs) return step }
        val step = withContext(Dispatchers.Default) { StorySummaries.plan(book.contentSha, inputs, ceiling) }
        planned = inputs to step
        return step
    }

    private suspend fun refreshTops() {
        tops.value = quietly { book.summaries.tops() } ?: emptyList()
    }

    private fun failedView(f: RecapFailure, chosen: CompanionChoice?) = CompanionView.Failed(
        f.failure,
        f.details?.copy(host = f.details.host ?: chosen?.let(::serviceName), model = f.details.model ?: chosen?.model),
    )

    private class Inputs(
        val activity: RecapActivity,
        val allowed: Boolean,
        val saved: List<SavedSummary>?,
        val failure: Pair<String, RecapFailure>?,
    )

    private suspend fun <T> quietly(work: suspend () -> T): T? = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Story: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "Story"

        fun serviceName(chosen: CompanionChoice): String =
            runCatching { URI(chosen.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: chosen.url
    }
}
