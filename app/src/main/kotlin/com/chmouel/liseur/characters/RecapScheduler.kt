package com.chmouel.liseur.characters

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How recaps are written: the identity they are saved under and the chat that writes them. */
data class RecapSetup(val identity: RecapIdentity, val chat: CharacterChat)

/** Whether recaps are on, and with what; [setup] is null until a server and model are chosen. */
data class RecapSettings(val enabled: Boolean, val setup: RecapSetup?)

/**
 * Recaps the chapters the reader finishes in one open book, one at a
 * time, in the background.
 *
 * A chapter is queued when ordinary reading moves on from it to the next
 * one; jumps queue nothing, and turning recaps on part-way through a book
 * does not go back. Work runs only while the app is in the foreground,
 * stands aside for a lookup ([aside]), and stops when [scope] ends with
 * the book. A chapter is sent only while it lies wholly before the one on
 * screen, checked again before every request; anything left undone stays
 * queued for the next opening, with its finished batches kept.
 */
class RecapScheduler<P>(
    private val scope: CoroutineScope,
    private val book: RecapBook,
    private val catalog: BookCatalog<P>,
    private val settings: StateFlow<RecapSettings>,
    private val foreground: StateFlow<Boolean>,
) {
    private val displayed = MutableStateFlow<Int?>(null)
    private val origin = MutableStateFlow<Int?>(null)
    private val pauses = MutableStateFlow(0)
    private val wake = MutableStateFlow(0)
    private val running = MutableStateFlow(false)
    private val failed = mutableSetOf<String>()
    private var loop: Job? = null

    private data class Gate(val setup: RecapSetup?, val current: Int?, val runnable: Boolean)

    private fun gate(s: RecapSettings, fg: Boolean, shown: Int?, from: Int?, paused: Int): Gate {
        val current = listOfNotNull(shown, from).minOrNull()
        val setup = s.setup.takeIf { s.enabled }
        return Gate(setup, current, fg && paused == 0 && setup != null && current != null)
    }

    private fun gateNow() = gate(settings.value, foreground.value, displayed.value, origin.value, pauses.value)

    private val gates = combine(settings, foreground, displayed, origin, pauses, ::gate)

    fun start() {
        if (loop == null) loop = scope.launch { run() }
    }

    /**
     * The reader is now in chapter [lo]. [movement] says the reader got
     * there by reading on (a page turn or a scroll), not by a jump.
     */
    suspend fun moved(lo: Int, movement: Boolean) {
        val previous = displayed.value
        displayed.value = lo
        if (movement && previous != null && lo == previous + 1 && settings.value.enabled) {
            if (book.enqueue(RecapUnit(previous, catalog.map.unitKey(previous)))) wake.update { it + 1 }
        }
    }

    /** Where a source was followed from while it is shown, so nothing past it is recapped meanwhile. */
    fun peeking(originLo: Int?) {
        origin.value = originLo
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

    private suspend fun run() {
        while (true) {
            running.value = true
            val seen = wake.value
            val gate = gateNow()
            val unit = if (gate.runnable) book.queued().firstOrNull { it.index < gate.current!! && it.key !in failed } else null
            if (unit == null) {
                running.value = false
                combine(gates, wake) { g, w -> g to w }.first { (g, w) -> g != gate || w != seen }
                continue
            }
            try {
                work(unit, gate.setup!!)
            } finally {
                running.value = false
            }
        }
    }

    private fun allowed(unit: RecapUnit, setup: RecapSetup, gate: Gate) =
        gate.runnable && gate.setup == setup && unit.index < gate.current!!

    private suspend fun work(unit: RecapUnit, setup: RecapSetup) = coroutineScope {
        val job = launch {
            try {
                if (!RecapWorker(book, catalog).recap(unit, setup) { allowed(unit, setup, gateNow()) }) failed += unit.key
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Tried again on the next opening; book text and replies are never logged.
                failed += unit.key
                Log.w(TAG, "Could not recap a chapter: ${e.javaClass.simpleName}")
            }
        }
        val watch = launch {
            gates.first { !allowed(unit, setup, it) }
            job.cancel()
        }
        job.join()
        watch.cancel()
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
     * were turned off or the book removed meanwhile.
     */
    suspend fun recap(unit: RecapUnit, setup: RecapSetup, allowed: () -> Boolean): Boolean {
        val identity = setup.identity
        val ticket = book.ticket()
        if (catalog.map.unitKey(unit.index) != unit.key || book.hasRecap(identity, unit)) {
            book.dequeue(unit)
            return true
        }
        val passages = catalog.passagesOf(unit.index)
        if (passages.any { it.range.hi != unit.index }) throw IllegalStateException("A passage is outside its chapter")
        val batches = Recaps.batches(passages)
        val done = book.parts(identity, unit, batches.size).toMutableMap()
        val recaps = batches.mapIndexed { index, batch ->
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
        repeat(2) { attempt ->
            val reply = setup.chat.reply(messages) {
                if (!allowed()) throw CancellationException("No longer allowed")
                if (++requests > MAX_REQUESTS) throw CharacterError.TooManyRequests()
            }
            Recaps.parse(reply, batch)?.let { return it }
            if (attempt == 0) messages = listOf(messages[0], ChatMessage("user", user + "\n\n" + Recaps.correction("not_json")))
        }
        throw CharacterError.Malformed()
    }

    private companion object {
        // The request, its one correction, and a temperature retry for each.
        const val MAX_REQUESTS = 4
    }
}
