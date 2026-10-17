package com.chmouel.liseur.companion

import android.util.Log
import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How recaps are written: the identity they are saved under, the chat
 * that writes them, and how much of a chapter one request may hold.
 */
data class RecapSetup(val identity: RecapIdentity, val chat: CompanionChat, val batchCharacters: Int = Recaps.BATCH_CHARACTERS)

/** Whether recaps are on, and with what; [setup] is null until a server and model are chosen. */
data class RecapSettings(val enabled: Boolean, val setup: RecapSetup?)

/**
 * Whether this book's reading is tracked for recaps: its own choice when
 * the reader made one, over the switch in settings.
 */
fun recapsEnabledFor(bookChoice: Boolean?, setting: Boolean): Boolean = bookChoice ?: setting

/** A chapter the reader asked to have recapped now, never sent while it is at or past [ceiling]. */
/** A chapter to recap below [ceiling]; [automatic] when Story asked on its own, so revoking that consent drops it. */
data class RecapRequest(val unit: RecapUnit, val ceiling: Int, val replace: Boolean = false, val automatic: Boolean = false)

/**
 * A running summary waiting to be written: [last] is the highest chapter
 * whose recap it sends, and it is sent only while that chapter lies before
 * the one on screen. [write] gets the chat of the chosen service, and is
 * told before every request whether it may still go; it returns normally,
 * or throws, when it is done.
 */
class SummaryWork(
    val last: Int,
    val automatic: Boolean = false,
    val write: suspend (chat: CompanionChat, allowed: () -> Boolean) -> Unit,
)

/** Why a chapter the reader asked for has no recap; it can be asked for again. */
data class RecapFailure(val failure: CompanionFailure, val details: FailureDetails?)

/** What the scheduler is doing, for the reader to see. */
data class RecapActivity(
    /** Chapters the reader asked for, in order, the one in hand included. */
    val waiting: List<Int> = emptyList(),
    /** The chapter being recapped, asked for or not, and its batch out of [batches]. */
    val working: Int? = null,
    val batch: Int = 0,
    val batches: Int = 0,
    /** Chapters asked for this session that could not be recapped. */
    val failed: Map<Int, RecapFailure> = emptyMap(),
    val summarizing: Boolean = false,
)

/**
 * Recaps the chapters of one open book: the ones the reader asks for, and
 * in the background the ones they finish, one at a time.
 *
 * A chapter is queued in the background when ordinary reading moves on
 * from it to the next one; jumps queue nothing, and turning recaps on
 * part-way through a book does not go back. Chapters asked for go first
 * ([request]), then a running summary ([summarize]), then the background
 * queue, which gives way between requests. Work runs only while the app is
 * in the foreground, stands aside for a lookup ([aside]), and stops when
 * [scope] ends with the book. Nothing is sent unless it lies wholly before
 * the chapter on screen, checked again before every request; anything
 * left undone in the background stays queued for the next opening, with
 * its finished batches kept.
 *
 * The chapter on screen is tracked only while some work could be sent
 * ([tracking]); otherwise it is forgotten, so a stale place is never
 * trusted.
 */
class RecapScheduler<P>(
    private val scope: CoroutineScope,
    private val book: RecapBook,
    private val catalog: BookCatalog<P>,
    private val settings: StateFlow<RecapSettings>,
    private val foreground: StateFlow<Boolean>,
    /** Whether Story may send on its own; read live, so work offered on a stale value is refused. */
    private val automatic: StateFlow<Boolean> = MutableStateFlow(true),
) {
    private val displayed = MutableStateFlow<Int?>(null)
    private val origin = MutableStateFlow<Int?>(null)
    private val pauses = MutableStateFlow(0)
    private val wake = MutableStateFlow(0)
    private val running = MutableStateFlow(false)
    private val lane = MutableStateFlow<List<RecapRequest>>(emptyList())
    private val summary = MutableStateFlow<SummaryWork?>(null)
    private val failed = mutableSetOf<String>()
    private val _activity = MutableStateFlow(RecapActivity())
    private var loop: Job? = null

    /** What is being done and waiting, for the reader. */
    val activity: StateFlow<RecapActivity> = _activity.asStateFlow()

    private fun tracks(s: RecapSettings, l: List<RecapRequest>, w: SummaryWork?) = s.enabled || l.isNotEmpty() || w != null

    /** Whether the chapter on screen is needed: background recaps are on, or asked-for work is pending. */
    val tracking: StateFlow<Boolean> = combine(settings, lane, summary, ::tracks)
        .stateIn(scope, SharingStarted.Eagerly, settings.value.enabled)

    private data class Gate(
        val setup: RecapSetup?,
        val enabled: Boolean,
        val shown: Int?,
        val origin: Int?,
        val ready: Boolean,
        val consented: Boolean,
        val lane: List<RecapRequest>,
        val summary: SummaryWork?,
    ) {
        /** The first chapter nothing at or past may be sent, under [ceiling] too. */
        fun current(ceiling: Int = Int.MAX_VALUE): Int? = shown?.let { minOf(it, origin ?: it, ceiling) }

        val runnable: Boolean get() = ready && setup != null && shown != null

        fun sendable(request: RecapRequest): Boolean = !request.automatic || consented

        fun requested(): RecapRequest? =
            if (runnable) lane.firstOrNull { sendable(it) && it.unit.index < current(it.ceiling)!! } else null

        fun summarizes(): Boolean =
            runnable && summary != null && (!summary.automatic || consented) && summary.last < current()!!
    }

    private class Inputs(val s: RecapSettings, val fg: Boolean, val shown: Int?, val from: Int?, val paused: Int)

    private fun gate(i: Inputs, consented: Boolean, lane: List<RecapRequest>, work: SummaryWork?) =
        Gate(i.s.setup, i.s.enabled, i.shown, i.from, i.fg && i.paused == 0, consented, lane, work)

    private fun gateNow() = gate(
        Inputs(settings.value, foreground.value, displayed.value, origin.value, pauses.value),
        automatic.value,
        lane.value,
        summary.value,
    )

    private val gates = combine(
        combine(settings, foreground, displayed, origin, pauses, ::Inputs),
        automatic,
        lane,
        summary,
        ::gate,
    )

    fun start() {
        if (loop != null) return
        loop = scope.launch { run() }
        scope.launch {
            // Asked-for work was asked of one service and model; another drops it.
            settings.map { it.setup?.identity?.let { id -> id.serverId to id.model } }
                .distinctUntilChanged()
                .drop(1)
                .collect { stop() }
        }
        // Revoked consent drops what Story queued on its own, shown or not; taps still work.
        scope.launch { automatic.collect { if (!it) dropAutomatic() } }
    }

    /**
     * The reader is now in chapter [lo]. [movement] says the reader got
     * there by reading on (a page turn or a scroll), not by a jump.
     */
    suspend fun moved(lo: Int, movement: Boolean) {
        if (!tracks(settings.value, lane.value, summary.value)) {
            displayed.value = null
            return
        }
        val previous = displayed.value
        displayed.value = lo
        if (movement && previous != null && lo == previous + 1 && settings.value.enabled) {
            if (book.enqueue(RecapUnit(previous, catalog.map.unitKey(previous)))) wake.update { it + 1 }
        }
    }

    /** The reader moved while nothing needed their place: it is no longer known. */
    fun unplaced() {
        if (!tracks(settings.value, lane.value, summary.value)) displayed.value = null
    }

    /** Where a source was followed from while it is shown, so nothing past it is recapped meanwhile. */
    fun peeking(originLo: Int?) {
        origin.value = originLo
    }

    /**
     * Recaps [requests] before anything else, in order. Each is sent only
     * below its own ceiling and the chapter on screen; while nothing
     * tracked the place it is unknown, and the work waits for [moved].
     * A chapter asked for by the reader stops being [RecapRequest.automatic];
     * automatic ones are refused while Story may not send on its own.
     */
    fun request(offered: List<RecapRequest>) {
        val requests = offered.filter { !it.automatic || automatic.value }
        if (requests.isEmpty()) return
        val asked = requests.map { it.unit.index }.toSet()
        _activity.update { it.copy(failed = it.failed - asked) }
        lane.update { current ->
            val explicit = requests.filter { !it.automatic }.map { it.unit.key }.toSet()
            val kept = current.map { if (it.automatic && it.unit.key in explicit) it.copy(automatic = false) else it }
            val known = current.map { it.unit.key }.toSet()
            kept + requests.filter { it.unit.key !in known }.distinctBy { it.unit.key }
        }
        syncWaiting()
    }

    /** Writes a running summary once no chapter asked for can go first; replaces one not yet written. */
    fun summarize(work: SummaryWork) {
        if (work.automatic && !automatic.value) return
        summary.value = work
        _activity.update { it.copy(summarizing = true) }
    }

    /** Story may no longer send on its own: what it queued that way goes, and stops if in flight. */
    fun dropAutomatic() {
        lane.update { current -> current.filterNot { it.automatic } }
        val work = summary.value
        if (work != null && work.automatic && summary.compareAndSet(work, null)) {
            _activity.update { it.copy(summarizing = false) }
        }
        syncWaiting()
    }

    /** Drops every chapter asked for and the running summary, stopping the request in flight for them. */
    fun stop() {
        lane.value = emptyList()
        summary.value = null
        _activity.update { it.copy(waiting = emptyList(), summarizing = false) }
    }

    /** Runs [block] with recapping stopped, after any request it had in flight is cancelled. */
    suspend fun <T> aside(block: suspend () -> T): T {
        pauses.update { it + 1 }
        try {
            running.first { !it }
            return block()
        } finally {
            pauses.update { it - 1 }
        }
    }

    private fun syncWaiting() = _activity.update { it.copy(waiting = lane.value.map { r -> r.unit.index }) }

    private suspend fun run() {
        while (true) {
            running.value = true
            val seen = wake.value
            val gate = gateNow()
            val asked = gate.requested()
            try {
                when {
                    asked != null -> requested(asked, gate.setup!!)
                    gate.summarizes() -> summarized(gate.summary!!, gate.setup!!)
                    else -> {
                        val unit = if (gate.runnable && gate.enabled) {
                            book.queued().firstOrNull { it.index < gate.current()!! && it.key !in failed }
                        } else {
                            null
                        }
                        if (unit == null) {
                            running.value = false
                            combine(gates, wake) { g, w -> g to w }.first { (g, w) -> g != gate || w != seen }
                            continue
                        }
                        background(unit, gate.setup!!)
                    }
                }
            } finally {
                running.value = false
                _activity.update { it.copy(working = null, batch = 0, batches = 0) }
            }
        }
    }

    private fun progress(unit: RecapUnit): (Int, Int) -> Unit = { batch, batches ->
        _activity.update { it.copy(working = unit.index, batch = batch, batches = batches) }
    }

    // Background work gives way, between requests, to anything asked for.
    private suspend fun background(unit: RecapUnit, setup: RecapSetup) {
        fun allowed(g: Gate) = g.runnable && g.enabled && g.setup == setup && unit.index < g.current()!!
        guarded({ allowed(it) }) {
            try {
                val worker = RecapWorker(book, catalog)
                val done = worker.recap(unit, setup, onBatch = progress(unit)) {
                    val g = gateNow()
                    allowed(g) && g.requested() == null && !g.summarizes()
                }
                if (!done) failed += unit.key
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Tried again on the next opening; book text and replies are never logged.
                failed += unit.key
                Log.w(TAG, "Could not recap a chapter: ${CompanionError.describe(e)}")
            }
        }
    }

    private suspend fun requested(request: RecapRequest, setup: RecapSetup) {
        fun allowed(g: Gate) = g.runnable && g.setup == setup &&
            g.lane.any { it.unit.key == request.unit.key && g.sendable(it) } &&
            request.unit.index < g.current(request.ceiling)!!
        val counted = CountedChat(setup.chat)
        var failure: RecapFailure? = null
        val finished = guarded({ allowed(it) }) {
            try {
                val done = RecapWorker(book, catalog)
                    .recap(request.unit, setup.copy(chat = counted), request.replace, progress(request.unit)) { allowed(gateNow()) }
                if (!done) failure = RecapFailure(CompanionFailure.CANCELLED, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not recap a chapter asked for: ${CompanionError.describe(e)}")
                failure = RecapFailure(CompanionError.failureOf(e), FailureDetails.of(e, requests = counted.requests))
            }
        }
        if (!finished) return
        lane.update { lane -> lane.filterNot { it.unit.key == request.unit.key } }
        failure?.let { f -> _activity.update { it.copy(failed = it.failed + (request.unit.index to f)) } }
        syncWaiting()
    }

    private suspend fun summarized(work: SummaryWork, setup: RecapSetup) {
        fun allowed(g: Gate) = g.runnable && g.setup == setup && g.summary === work &&
            (!work.automatic || g.consented) && work.last < g.current()!!
        val finished = guarded({ allowed(it) }) {
            try {
                work.write(setup.chat) {
                    val g = gateNow()
                    allowed(g) && g.requested() == null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not write the running summary: ${CompanionError.describe(e)}")
            }
        }
        if (finished && summary.compareAndSet(work, null)) _activity.update { it.copy(summarizing = false) }
    }

    /**
     * Runs [block], cancelling it as soon as [allowed] fails; true when it
     * ran to its end rather than being stopped or giving way.
     */
    private suspend fun guarded(allowed: (Gate) -> Boolean, block: suspend () -> Unit): Boolean = coroutineScope {
        var ended = false
        val job = launch {
            try {
                block()
                ended = true
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    // Gave way before a request: not a failure, tried again later.
                    return@launch
                }
                throw e
            }
        }
        val watch = launch {
            gates.first { !allowed(it) }
            job.cancel()
        }
        job.join()
        watch.cancel()
        ended
    }

    private companion object {
        const val TAG = "RecapScheduler"
    }
}

/** Recaps one chapter: batch by batch, each saved as it is done, then merged here without asking anyone. */
class RecapWorker<P>(private val book: RecapBook, private val catalog: BookCatalog<P>) {

    /**
     * [allowed] is asked before every request, and a false ends the work
     * as a cancellation. False when the recap could not be kept: recaps
     * were turned off or the book removed meanwhile. A saved recap is kept
     * unless [replace] and it cannot be read, and is written over only
     * once the new one is done.
     */
    suspend fun recap(
        unit: RecapUnit,
        setup: RecapSetup,
        replace: Boolean = false,
        onBatch: (batch: Int, batches: Int) -> Unit = { _, _ -> },
        allowed: () -> Boolean,
    ): Boolean {
        val identity = setup.identity
        val ticket = book.ticket()
        // Asked again, only a recap that cannot be read is written over.
        val kept = book.recapOf(identity, unit)?.let { !replace || ChapterRecap.decode(it) != null } == true
        if (catalog.map.unitKey(unit.index) != unit.key || kept) {
            book.dequeue(unit)
            return true
        }
        val passages = catalog.passagesOf(unit.index)
        if (passages.any { it.range.hi != unit.index }) throw IllegalStateException("A passage is outside its chapter")
        val batches = Recaps.batches(passages, setup.batchCharacters)
        val done = book.parts(identity, unit, batches.size).toMutableMap()
        val recaps = batches.mapIndexed { index, batch ->
            onBatch(index + 1, batches.size)
            done[index]?.let(ChapterRecap::decode) ?: ask(batch, unit, setup, allowed).also {
                if (!book.savePart(identity, unit, index, batches.size, it.encode(), ticket)) return false
            }
        }
        return book.saveRecap(identity, unit, ChapterRecap.merge(recaps).encode(), ticket)
    }

    private suspend fun ask(batch: List<Passage<P>>, unit: RecapUnit, setup: RecapSetup, allowed: () -> Boolean): ChapterRecap {
        var requests = 0
        val user = Recaps.user(batch, unit.index, catalog.map.chapterCount)
        var messages = listOf(ChatMessage("system", Recaps.system()), ChatMessage("user", user))
        var code = "not_json"
        repeat(2) { attempt ->
            val reply = try {
                setup.chat.reply(messages) {
                    if (!allowed()) throw CancellationException("No longer allowed")
                    if (++requests > MAX_REQUESTS) throw CompanionError.TooManyRequests()
                }
            } catch (_: TranslationError.Truncated) {
                null
            }
            code = if (reply == null) CompanionPrompt.TOO_LONG else "not_json"
            reply?.let { Recaps.parse(it, batch) }?.let { return it }
            if (attempt == 0) messages = listOf(messages[0], ChatMessage("user", user + "\n\n" + Recaps.correction(code)))
        }
        throw CompanionError.Malformed(code)
    }

    private companion object {
        // The request, its one correction, and a temperature retry for each.
        const val MAX_REQUESTS = 4
    }
}

/** Counts the requests let go, for the details of a failure. */
internal class CountedChat(private val chat: CompanionChat) : CompanionChat {
    @Volatile var requests = 0
        private set

    override suspend fun reply(messages: List<ChatMessage>, beforeRequest: () -> Unit): String =
        chat.reply(messages) {
            beforeRequest()
            requests++
        }
}
