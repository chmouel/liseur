package com.chmouel.liseur.companion

import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapTest {

    // -- Batches and merging -----------------------------------------------

    @Test
    fun `batches keep every passage once and in order, a giant one alone`() {
        val passages = listOf(
            passage(0, 0, "a".repeat(500)),
            passage(0, 1, "b".repeat(600)),
            passage(0, 2, "c".repeat(5_000)),
            passage(0, 3, "d".repeat(100)),
        )

        val batches = Recaps.batches(passages, limit = 1_000)

        assertEquals(passages, batches.flatten())
        assertEquals(listOf(1, 1, 1, 1), batches.map { it.size })
        assertEquals(listOf(passages[2]), batches[2])
        assertEquals(listOf(2), Recaps.batches(passages.take(2), limit = 1_100).map { it.size })
        assertTrue(Recaps.batches(emptyList<Passage<Int>>()).isEmpty())
    }

    @Test
    fun `merging batches keeps every summary, alias, fact and ref`() {
        val first = ChapterRecap(
            listOf("Mira reaches the mill."),
            listOf(RecapCharacter("Mira", listOf("Wren"), listOf(RecapFact("Keeps the ledger.", listOf("r0.1#x"))), emptyList())),
        )
        val second = ChapterRecap(
            listOf("Tobin arrives."),
            listOf(
                RecapCharacter("Wren", emptyList(), listOf(RecapFact("Hides the key.", listOf("r0.9#y"))), listOf(RecapFact("Her brother.", listOf("r0.8#z"), "Tobin"))),
                RecapCharacter("Tobin", emptyList(), listOf(RecapFact("A carter.", listOf("r0.8#z"))), emptyList()),
            ),
        )

        val merged = ChapterRecap.merge(listOf(first, second))

        assertEquals(listOf("Mira reaches the mill.", "Tobin arrives."), merged.summary)
        assertEquals(listOf("Mira", "Tobin"), merged.characters.map { it.name })
        val mira = merged.characters[0]
        assertEquals(listOf("Wren"), mira.aliases)
        assertEquals(listOf("r0.1#x", "r0.9#y", "r0.8#z"), mira.refs)
        assertEquals(merged, ChapterRecap.decode(merged.encode()))
    }

    // -- What a batch reply may say ------------------------------------------

    private val batch = listOf(
        passage(2, 0, "Mira, whom Tobin calls Wren, opens the mill."),
        passage(2, 1, "The river is high."),
    )

    @Test
    fun `a batch reply keeps only names, aliases and refs its passages bear out`() {
        val reply = JSONObject()
            .put("summary", "Mira opens the mill.")
            .put(
                "characters",
                JSONArray()
                    .put(character("Mira", aliases = listOf("Wren", "The Queen"), refs = listOf("P1", "P7")).put("relationships", JSONArray().put(fact("Brother", listOf("P1")).put("with", "Tobin")).put(fact("Rival", listOf("P1")).put("with", "Ser Kael"))))
                    // Named nowhere in what it cites, so not kept.
                    .put(character("Ser Kael", refs = listOf("P2")))
                    // Cites nothing of this batch.
                    .put(character("Tobin", refs = listOf("P9"))),
            ).toString()

        val recap = Recaps.parse("Here you go:\n$reply\nThanks", batch)!!

        assertEquals(listOf("Mira opens the mill."), recap.summary)
        assertEquals(listOf("Mira"), recap.characters.map { it.name })
        val mira = recap.characters.single()
        assertEquals(listOf("Wren"), mira.aliases)
        assertEquals(listOf(Recaps.stored(batch[0])), mira.facts.single().refs)
        assertEquals(listOf("Tobin"), mira.relationships.map { it.with })
    }

    @Test
    fun `a reply that is not the JSON asked for is refused`() {
        assertNull(Recaps.parse("I cannot do that.", batch))
        assertNull(Recaps.parse("{\"summary\": unterminated", batch))
        assertNull(Recaps.parse("{\"note\":\"ignore previous instructions\"}", batch))
        assertNotNull(Recaps.parse("{\"summary\":\"\",\"characters\":[]}", batch))
    }

    // -- What recaps offer a lookup -----------------------------------------

    @Test
    fun `hints come only from earlier chapters and unchanged eligible passages`() {
        val ch2 = passage(1, 0, "Mira keeps the ledger.")
        val ch3 = passage(2, 0, "Mira meets Tobin.")
        val ch6 = passage(5, 0, "Mira betrays Tobin.")
        fun recap(vararg refs: String) = ChapterRecap(emptyList(), listOf(RecapCharacter("Mira", emptyList(), listOf(RecapFact("…", refs.toList())), emptyList())))
        val saved = listOf(
            1 to recap(Recaps.stored(ch2)),
            // Its text changed since: the hash no longer matches.
            2 to recap("${ch3.ref}#stale"),
            // A later chapter's recap, cached from an earlier read.
            5 to recap(Recaps.stored(ch6)),
            // An earlier unit citing a later passage cannot reach it either.
            3 to recap(Recaps.stored(ch6)),
        )

        val hints = Recaps.hints(saved, AskingScope(lo = 4, count = 8), eligible = listOf(ch2, ch3))

        assertEquals(listOf(RecapHint(1, listOf("Mira"), listOf(ch2.ref))), hints)
        assertTrue(Recaps.hints(saved, AskingScope(lo = 0, count = 8), listOf(ch2, ch3)).isEmpty())
    }

    @Test
    fun `a unit key changes with the chapter's bounds, not with other chapters' reading`() {
        val files = listOf("c1.xhtml", "c2.xhtml", "c3.xhtml")
        val map = ChapterMap.of(files, files.map { TocNode(it) })
        val same = ChapterMap.of(files, files.map { TocNode(it) })
        val merged = ChapterMap.of(files, listOf(TocNode("c1.xhtml"), TocNode("c3.xhtml")))

        assertEquals(map.unitKey(1), same.unitKey(1))
        assertNotEquals(map.unitKey(0), map.unitKey(1))
        assertNotEquals(map.unitKey(0), merged.unitKey(0))
    }

    // -- Scheduling ----------------------------------------------------------

    @Test
    fun `a book is tracked for recaps by its own choice, otherwise by the setting`() {
        // The setting off and no choice for the book: nothing is tracked.
        assertFalse(recapsEnabledFor(bookChoice = null, setting = false))
        // The book turned on keeps it tracked while the setting is off.
        assertTrue(recapsEnabledFor(bookChoice = true, setting = false))
        // The setting on alone tracks it.
        assertTrue(recapsEnabledFor(bookChoice = null, setting = true))
        // The book turned off wins over the setting.
        assertFalse(recapsEnabledFor(bookChoice = false, setting = true))
    }

    @Test
    fun `reading on into the next chapter recaps the one just finished, and only its text`() = runTest {
        val h = harness()
        h.scheduler.moved(0, movement = false)
        h.scheduler.moved(1, movement = true)
        h.scheduler.moved(2, movement = true)
        runCurrent()

        assertEquals(setOf(0, 1), h.book.saved.keys.map { it.index }.toSet())
        assertEquals(2, h.chat.requests.size)
        for ((k, request) in h.chat.requests.withIndex()) {
            val sent = request.joinToString("\n") { it.content }
            assertTrue(sent.contains("Chapter ${k + 1} text"))
            for (later in k + 2..h.chapters) assertFalse("chapter $later sent", sent.contains("Chapter $later text"))
        }
        assertTrue(h.book.queue.isEmpty())
        assertEquals("Mira", ChapterRecap.decode(h.book.saved.values.first())!!.characters.single().name)
    }

    @Test
    fun `jumps, skipped chapters, opening and turning recaps on part-way queue nothing`() = runTest {
        val h = harness(enabled = false)
        h.scheduler.moved(3, movement = false) // opening the book in chapter 4
        h.scheduler.moved(4, movement = true) // read on while recaps are off
        h.settings.value = RecapSettings(true, h.setup)
        h.scheduler.moved(6, movement = false) // a contents jump
        h.scheduler.moved(1, movement = false) // and back
        h.scheduler.moved(3, movement = true) // a two-chapter swipe is not finishing one
        runCurrent()

        assertTrue(h.book.queue.isEmpty())
        assertTrue(h.chat.requests.isEmpty())

        h.scheduler.moved(4, movement = true)
        runCurrent()
        assertEquals(listOf(3), h.book.saved.keys.map { it.index })
    }

    @Test
    fun `queued work waits while its chapter is on screen or after it, and stops if the reader goes back`() = runTest {
        val h = harness(hold = true)
        h.scheduler.moved(2, movement = false)
        h.scheduler.moved(3, movement = true) // queues chapter 3
        runCurrent()
        assertEquals(1, h.chat.requests.size)

        h.scheduler.moved(2, movement = false) // back into chapter 3, mid-request
        runCurrent()
        assertTrue(h.chat.cancelled)
        assertTrue(h.book.saved.isEmpty())
        assertEquals(listOf(2), h.book.queue.map { it.index })

        h.chat.hold = false
        h.scheduler.moved(1, movement = false)
        runCurrent()
        assertEquals("nothing is sent from the chapter on screen", 1, h.chat.requests.size)

        h.scheduler.moved(5, movement = false)
        runCurrent()
        assertEquals(listOf(2), h.book.saved.keys.map { it.index })
    }

    @Test
    fun `nothing is sent past a followed source's origin`() = runTest {
        val h = harness()
        h.book.queue += h.unit(3)
        h.scheduler.peeking(originLo = 3) // asked in chapter 4, now reading a chapter 2 source
        h.scheduler.moved(1, movement = false)
        h.scheduler.moved(5, movement = false)
        runCurrent()
        assertTrue(h.chat.requests.isEmpty())

        h.scheduler.peeking(null)
        runCurrent()
        assertEquals(listOf(3), h.book.saved.keys.map { it.index })
    }

    @Test
    fun `work pauses in the background and stands aside for a lookup`() = runTest {
        val h = harness(hold = true)
        h.book.queue += h.unit(0)
        h.foreground.value = false
        h.scheduler.moved(3, movement = false)
        runCurrent()
        assertTrue(h.chat.requests.isEmpty())

        h.foreground.value = true
        runCurrent()
        assertEquals(1, h.chat.requests.size)
        h.foreground.value = false
        runCurrent()
        assertTrue(h.chat.cancelled)

        h.foreground.value = true
        runCurrent()
        assertEquals(2, h.chat.requests.size)
        h.chat.cancelled = false
        h.chat.hold = false
        // What the lookup sees when it starts: the recap request ended, nothing new sent, the chapter still queued.
        val lookup = async { h.scheduler.aside { Triple(h.chat.cancelled, h.chat.requests.size, h.book.queue.map { it.index }) } }
        runCurrent()
        assertEquals(Triple(true, 2, listOf(0)), lookup.await())

        // Then the recap starts again.
        assertEquals(listOf(0), h.book.saved.keys.map { it.index })
    }

    @Test
    fun `a long chapter resumes on reopening without paying again for finished batches`() = runTest {
        // About 33,000 characters: three batches.
        val long = (0 until 30).map { k -> "Chapter 1 text. Mira walks on, step $k. " + "x".repeat(1_050) }
        val book = FakeBook()
        val first = harness(book = book, hold = true, longChapter = long)
        first.book.queue += first.unit(0)
        first.scheduler.moved(2, movement = false)
        first.chat.holdAfter = 2 // the third request hangs
        runCurrent()
        assertEquals(3, first.chat.requests.size)
        first.close() // the book is closed mid-chapter
        runCurrent()
        assertEquals(2, book.parts.size)

        val second = harness(book = book, longChapter = long)
        second.scheduler.moved(2, movement = false)
        runCurrent()

        assertEquals("only the unfinished batch is sent again", 1, second.chat.requests.size)
        val recap = ChapterRecap.decode(book.saved.values.single())!!
        assertEquals(3, recap.summary.size)
        assertEquals(3, recap.characters.single().refs.size)
        assertTrue(book.parts.isEmpty())
    }

    @Test
    fun `a chapter that cannot be recapped stays queued for the next opening`() = runTest {
        val book = FakeBook()
        val h = harness(book = book, malformed = true)
        h.book.queue += h.unit(0)
        h.scheduler.moved(2, movement = false)
        runCurrent()
        assertEquals("one request and one correction", 2, h.chat.requests.size)
        assertTrue(h.chat.requests[1].last().content.contains("could not be used"))
        assertFalse("the bad reply is never sent back", h.chat.requests[1].joinToString { it.content }.contains("Sorry"))
        assertEquals(listOf(0), book.queue.map { it.index })
        h.close()

        val again = harness(book = book)
        again.scheduler.moved(2, movement = false)
        runCurrent()
        assertEquals(listOf(0), book.saved.keys.map { it.index })
    }

    @Test
    fun `turning recaps off stops the work and keeps nothing it was writing`() = runTest {
        val h = harness(hold = true)
        h.book.queue += h.unit(0)
        h.scheduler.moved(3, movement = false)
        runCurrent()
        assertEquals(1, h.chat.requests.size)

        h.settings.value = RecapSettings(false, h.setup)
        runCurrent()
        assertTrue(h.chat.cancelled)
        assertTrue(h.book.saved.isEmpty())
    }

    @Test
    fun `a recap fenced off by the store is not retried in a loop`() = runTest {
        val h = harness()
        h.book.fenced = true
        h.book.queue += h.unit(0)
        h.scheduler.moved(3, movement = false)
        runCurrent()
        assertEquals(1, h.chat.requests.size)
        assertTrue(h.book.saved.isEmpty())
    }

    @Test
    fun `a saved recap from another format is written again, one from another server or model is kept`() = runTest {
        val h = harness()
        h.book.saved[h.unit(0)] = "{}"
        h.book.savedBy[h.unit(0)] = h.setup.identity.copy(format = h.setup.identity.format + 1)
        h.book.queue += h.unit(0)
        h.scheduler.moved(3, movement = false)
        runCurrent()
        assertEquals(1, h.chat.requests.size)

        h.book.savedBy[h.unit(0)] = h.setup.identity.copy(serverId = "other", model = "other")
        val switched = harness(book = h.book)
        switched.book.queue += switched.unit(0)
        switched.scheduler.moved(3, movement = false)
        runCurrent()
        assertTrue("already recapped by another model", switched.chat.requests.isEmpty())
        assertTrue(switched.book.queue.isEmpty())
    }

    @Test
    fun `a recap cut off at the output limit is asked for again, shorter`() = runTest {
        val h = harness(truncateFirst = true)
        h.book.queue += h.unit(0)
        h.scheduler.moved(3, movement = false)
        runCurrent()

        assertEquals(2, h.chat.requests.size)
        assertTrue(h.chat.requests[1].last().content.contains("(too_long)"))
        assertEquals(listOf(0), h.book.saved.keys.map { it.index })
    }

    // -- Fixtures --------------------------------------------------------------

    private class Harness(
        val scheduler: RecapScheduler<Int>,
        val book: FakeBook,
        val chat: RecapChat,
        val settings: MutableStateFlow<RecapSettings>,
        val foreground: MutableStateFlow<Boolean>,
        val setup: RecapSetup,
        val catalog: FakeCatalog,
        val scope: CoroutineScope,
    ) {
        val chapters get() = catalog.map.chapterCount

        fun unit(index: Int) = RecapUnit(index, catalog.map.unitKey(index))

        fun close() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    private fun TestScope.harness(
        enabled: Boolean = true,
        hold: Boolean = false,
        malformed: Boolean = false,
        book: FakeBook = FakeBook(),
        longChapter: List<String>? = null,
        truncateFirst: Boolean = false,
    ): Harness {
        val catalog = FakeCatalog(8, longChapter)
        val chat = RecapChat(hold, malformed, truncateFirst)
        val setup = RecapSetup(RecapIdentity("sha", "server", "model", Recaps.FORMAT), chat)
        val settings = MutableStateFlow(RecapSettings(enabled, setup))
        val foreground = MutableStateFlow(true)
        val scope = CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        val scheduler = RecapScheduler(scope, book, catalog, settings, foreground)
        scheduler.start()
        return Harness(scheduler, book, chat, settings, foreground, setup, catalog, scope)
    }

    /** Replies with a recap of Mira citing the first passage, or hangs until cancelled while [hold]. */
    private class RecapChat(var hold: Boolean, private val malformed: Boolean, private val truncateFirst: Boolean = false) : CompanionChat {
        val requests = mutableListOf<List<ChatMessage>>()
        var cancelled = false
        var holdAfter = -1

        override suspend fun reply(messages: List<ChatMessage>, beforeRequest: () -> Unit): String {
            beforeRequest()
            requests += messages
            if (hold && (holdAfter < 0 || requests.size > holdAfter)) {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cancelled = true
                }
            }
            if (malformed) return "Sorry, here is a poem instead."
            if (truncateFirst && requests.size == 1) throw TranslationError.Truncated()
            val text = messages.last().content
            val step = Regex("step ([0-9]+)").find(text)?.value ?: "the mill"
            return JSONObject()
                .put("summary", "Mira at $step.")
                .put("characters", JSONArray().put(character("Mira", refs = listOf("P1"))))
                .toString()
        }
    }

    private class FakeBook : RecapBook {
        val saved = mutableMapOf<RecapUnit, String>()
        val savedBy = mutableMapOf<RecapUnit, RecapIdentity>()
        val parts = mutableMapOf<Pair<RecapUnit, Int>, String>()
        val queue = mutableListOf<RecapUnit>()
        var fenced = false

        override fun ticket() = 0L

        // As the store: any server or model, the same file and format.
        private fun RecapIdentity?.reads(identity: RecapIdentity) =
            this != null && contentSha == identity.contentSha && format == identity.format

        override suspend fun recaps(identity: RecapIdentity) =
            saved.filter { savedBy[it.key].reads(identity) }.map { SavedRecap(it.key, it.value) }

        override suspend fun hasRecap(identity: RecapIdentity, unit: RecapUnit) = unit in saved && savedBy[unit].reads(identity)

        override suspend fun parts(identity: RecapIdentity, unit: RecapUnit, batchCount: Int) =
            parts.filterKeys { it.first == unit }.mapKeys { it.key.second }

        override suspend fun savePart(identity: RecapIdentity, unit: RecapUnit, index: Int, count: Int, json: String, ticket: Long): Boolean {
            if (fenced) return false
            parts[unit to index] = json
            return true
        }

        override suspend fun saveRecap(identity: RecapIdentity, unit: RecapUnit, json: String, ticket: Long): Boolean {
            if (fenced) return false
            saved[unit] = json
            savedBy[unit] = identity
            parts.keys.removeAll { it.first == unit }
            queue.remove(unit)
            return true
        }

        override suspend fun enqueue(unit: RecapUnit): Boolean {
            if (unit !in queue) queue += unit
            return true
        }

        override suspend fun queued() = queue.toList()

        override suspend fun dequeue(unit: RecapUnit) {
            queue.remove(unit)
        }
    }

    /** One file per chapter; Chapter k's passages say "Chapter k text". */
    private class FakeCatalog(chapters: Int, longChapter: List<String>?) : BookCatalog<Int> {
        private val files = (1..chapters).map { "c$it.xhtml" }
        override val map = ChapterMap.of(files, files.map { TocNode(it) })
        private val passages = (0 until chapters).flatMap { c ->
            val texts = if (c == 0 && longChapter != null) longChapter else listOf("Chapter ${c + 1} text. Mira walks.", "The river, chapter ${c + 1}.")
            texts.mapIndexed { k, t -> passage(c, k, t) }
        }

        override suspend fun scopeOf(place: Int, translated: Boolean) = AskingScope(place, map.chapterCount)

        override suspend fun passagesBefore(scope: AskingScope) = passages.filter { Eligibility.allows(scope, it.range) }

        override suspend fun passagesOf(unit: Int) = passages.filter { it.range.hi == unit }
    }

    private companion object {
        fun passage(chapter: Int, ordinal: Int, text: String) = Passage(
            ref = PassageText.ref(chapter, ordinal, null),
            hash = PassageText.hash(text),
            resource = chapter,
            text = text,
            range = ChapterRange(chapter, chapter),
            place = chapter,
        )

        fun fact(text: String, refs: List<String>) = JSONObject().put("text", text).put("refs", JSONArray(refs))

        fun character(name: String, aliases: List<String> = emptyList(), refs: List<String>) = JSONObject()
            .put("name", name)
            .put("aliases", JSONArray(aliases))
            .put("facts", JSONArray().put(fact("Is here.", refs)))
    }
}
