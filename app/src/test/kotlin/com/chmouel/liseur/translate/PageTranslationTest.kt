package com.chmouel.liseur.translate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationTest {

    private val book = (0 until 40).map { PageSentence("s$it", "ch1.xhtml", "#p${it / 2}", if (it % 2 == 1) "s${it - 1}" else null) }

    private fun sentencesFrom(start: Int) = object : PageSentences {
        var at = start
        override suspend fun next() = book.getOrNull(at++)
    }

    private class FakeRun : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        var identity = "svc"
        override val destination: String? = null
        override suspend fun answering() = identity
        val asked = mutableListOf<Pair<String, String?>>()
        var failWith: ((String) -> TranslationError?)? = null
        var gate: CompletableDeferred<Unit>? = null
        var closed = false
        var answer: (String) -> String = String::uppercase

        override suspend fun translate(sentence: String, context: String?): String {
            asked += sentence to context
            gate?.await()
            failWith?.invoke(sentence)?.let { throw it }
            return answer(sentence)
        }

        override fun close() {
            closed = true
        }
    }

    private fun TestScope.translation(run: FakeRun, cache: PageTranslationCache = PageTranslationCache(), ahead: Int = 4) =
        PageTranslation(backgroundScope, run, cache, ::sentencesFrom, ahead)

    private fun PageTranslation<Int>.texts() = swaps.value["ch1.xhtml"].orEmpty().map { it.translation }

    @Test
    fun `translates ahead of the reader and no further`() = runTest {
        val run = FakeRun()
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        assertEquals(listOf("S0", "S1", "S2", "S3"), page.texts())
        assertEquals(PageTranslationState.Ahead, page.state.value)

        // The reader reaches the fourth sentence: four more fit in the window.
        page.onReader(PageReader.Within(book[3]))
        runCurrent()
        assertEquals(listOf("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7"), page.texts())
    }

    @Test
    fun `a reader still in a long element does not pull the walk through it`() = runTest {
        val long = (0 until 40).map { PageSentence("s$it", "ch1.xhtml", "#p0", null) }
        val run = FakeRun()
        val page = PageTranslation(backgroundScope, run, PageTranslationCache(), { at: Int ->
            object : PageSentences {
                var i = at
                override suspend fun next() = long.getOrNull(i++)
            }
        }, 4)
        page.start(0, "start")
        runCurrent()
        // The page keeps saying the same sentence is the last on screen as translations land.
        repeat(5) {
            page.onReader(PageReader.Within(long[1]))
            runCurrent()
        }
        assertEquals(6, run.asked.size)
    }

    @Test
    fun `a service changed mid-sentence does not file its answer under the old one`() = runTest {
        val cache = PageTranslationCache()
        val run = object : SentenceTranslator {
            override val source = "fr"
            override val target = "en"
            var identity = "old"
            override val destination: String? = null
            override suspend fun answering() = identity
            override suspend fun translate(sentence: String, context: String?): String {
                identity = "new"
                return sentence.uppercase()
            }
            override fun close() = Unit
        }
        val page = PageTranslation(backgroundScope, run, cache, ::sentencesFrom, 2)
        page.start(0, "start")
        runCurrent()
        assertEquals(null, cache["old\u0000\u0000s0"])
        assertEquals("S1", cache["new\u0000s0\u0000s1"])
    }

    @Test
    fun `a reader past the walk is caught up with, without asking for what they passed`() = runTest {
        val run = FakeRun()
        val page = PageTranslation(backgroundScope, run, PageTranslationCache(), ::sentencesFrom, 4, behind = { it.text.drop(1).toInt() < 30 })
        page.start(0, "start")
        runCurrent()
        // In one long element, as far as the page can tell: no restart would land there.
        page.onReader(PageReader.Beyond)
        runCurrent()
        assertEquals(listOf("s0", "s1", "s2", "s3", "s30", "s31", "s32", "s33"), run.asked.map { it.first })
        assertEquals("s29", run.asked[4].second)
        assertEquals(PageTranslationState.Ahead, page.state.value)
    }

    @Test
    fun `a service chosen since is asked again for what the old one translated`() = runTest {
        val run = FakeRun()
        val cache = PageTranslationCache()
        translation(run, cache).start(0, "start")
        runCurrent()
        run.identity = "other"
        translation(run, cache).start(0, "start")
        runCurrent()
        assertEquals(8, run.asked.size)
    }

    @Test
    fun `each sentence goes with the one before it as context`() = runTest {
        val run = FakeRun()
        translation(run).start(0, "start")
        runCurrent()
        assertEquals(listOf("s0" to null, "s1" to "s0", "s2" to "s1", "s3" to "s2"), run.asked)
    }

    @Test
    fun `a jump outside the walk starts again from there, once`() = runTest {
        val run = FakeRun()
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        page.onReader(PageReader.Elsewhere(20, "ch1.xhtml#p10"))
        runCurrent()
        assertEquals(listOf("S20", "S21", "S22", "S23"), page.texts().drop(4))
        assertEquals("s20" to null, run.asked[4])

        // The page still says the same place until the reader moves: no second restart.
        page.onReader(PageReader.Elsewhere(20, "ch1.xhtml#p10"))
        runCurrent()
        assertEquals(8, run.asked.size)
    }

    @Test
    fun `a reader back before the walk in the element it started from starts it again`() = runTest {
        val run = FakeRun()
        val page = translation(run)
        page.start(20, "ch1.xhtml#p10")
        runCurrent()
        // Same element, nothing new from the page: no restart.
        page.onReader(PageReader.Elsewhere(18, "ch1.xhtml#p10"))
        runCurrent()
        assertEquals(4, run.asked.size)

        // The page found the walk still to come: the reader went back within the element.
        page.onReader(PageReader.Elsewhere(18, "ch1.xhtml#p10", before = true))
        runCurrent()
        assertEquals("s18" to null, run.asked[4])
        // S20 and S21 are on the page already.
        assertEquals(listOf("S20", "S21", "S22", "S23", "S18", "S19"), page.texts())
    }

    @Test
    fun `a jump does not wait for the sentence on its way`() = runTest {
        val run = FakeRun()
        run.gate = CompletableDeferred()
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        // s0 is still with the service when the reader jumps; the jump goes ahead without its answer.
        run.gate = null
        page.onReader(PageReader.Elsewhere(20, "ch1.xhtml#p10"))
        runCurrent()
        assertEquals(listOf("S20", "S21", "S22", "S23"), page.texts())
        assertEquals("s20" to null, run.asked[1])
    }

    @Test
    fun `a reply far longer than its sentence is not put in the page`() = runTest {
        val run = FakeRun()
        run.answer = { if (it == "s1") "x".repeat(5_000) else it.uppercase() }
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        assertEquals(listOf("S0", "S2", "S3"), page.texts())
        assertEquals(PageTranslationState.Ahead, page.state.value)
    }

    @Test
    fun `a sentence already translated is not asked again`() = runTest {
        val cache = PageTranslationCache()
        val first = FakeRun()
        translation(first, cache).start(0, "start")
        runCurrent()

        val second = FakeRun()
        val page = translation(second, cache)
        page.start(0, "start")
        runCurrent()
        assertEquals(listOf("S0", "S1", "S2", "S3"), page.texts())
        assertTrue(second.asked.isEmpty())
    }

    @Test
    fun `a refused key halts on that sentence until retried`() = runTest {
        val run = FakeRun()
        run.failWith = { if (it == "s2") TranslationError.InvalidKey(401) else null }
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        assertEquals(listOf("S0", "S1"), page.texts())
        assertTrue(page.state.value is PageTranslationState.Halted)

        run.failWith = null
        page.retry()
        runCurrent()
        assertEquals(listOf("S0", "S1", "S2", "S3"), page.texts())
        assertEquals(listOf("s0", "s1", "s2", "s2", "s3"), run.asked.map { it.first })
    }

    @Test
    fun `a sentence the service will not translate stays original and the walk goes on`() = runTest {
        val run = FakeRun()
        run.failWith = { if (it == "s1") TranslationError.Refused() else null }
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        assertEquals(listOf("S0", "S2", "S3"), page.texts())
        assertEquals(PageTranslationState.Ahead, page.state.value)
    }

    @Test
    fun `the end of the book ends the run`() = runTest {
        val run = FakeRun()
        val page = translation(run, ahead = 100)
        page.start(38, "start")
        runCurrent()
        assertEquals(listOf("S38", "S39"), page.texts())
        assertEquals(PageTranslationState.Ended, page.state.value)
    }

    @Test
    fun `stop drops the sentence on its way and closes the translator`() = runTest {
        val run = FakeRun()
        run.gate = CompletableDeferred()
        val page = translation(run)
        page.start(0, "start")
        runCurrent()
        page.stop()
        run.gate!!.complete(Unit)
        runCurrent()
        assertTrue(page.texts().isEmpty())
        assertTrue(run.closed)
    }

    @Test
    fun `the walked sentences of a resource come in reading order`() = runTest {
        val page = translation(FakeRun())
        page.start(0, "start")
        runCurrent()
        assertEquals(book.take(4), page.walkedIn("ch1.xhtml"))
        assertTrue(page.walkedIn("ch2.xhtml").isEmpty())
    }

    @Test
    fun `the walked sentences of each resource stay apart once the walk crosses into the next`() = runTest {
        val two = (0 until 6).map { PageSentence("s$it", if (it < 3) "ch1.xhtml" else "ch2.xhtml", "#p$it", null) }
        val page = PageTranslation(backgroundScope, FakeRun(), PageTranslationCache(), { at: Int ->
            object : PageSentences {
                var i = at
                override suspend fun next() = two.getOrNull(i++)
            }
        }, 10)
        page.start(0, "start")
        runCurrent()
        assertEquals(two.take(3), page.walkedIn("ch1.xhtml"))
        assertEquals(two.drop(3), page.walkedIn("ch2.xhtml"))
    }

    @Test
    fun `a selection on translated words finds the sentence they translate`() = runTest {
        val run = FakeRun()
        val shown = mapOf("s0" to "It rains.", "s1" to "Yes, it rains.", "s2" to "No.", "s3" to "It rains.")
        run.answer = { shown.getValue(it) }
        val page = translation(run)
        page.start(0, "start")
        runCurrent()

        // The same words in three sentences: the text before the selection tells them apart.
        assertEquals("s1", page.original("ch1.xhtml", "rains", "It rains. Yes, it ")?.text)
        assertEquals("s3", page.original("ch1.xhtml", "rains", "No. It ")?.text)
        assertEquals(null, page.original("ch1.xhtml", "Oui", "No. "))
        assertEquals(null, page.original("ch2.xhtml", "rains", ""))

        // A selection running from one translation into the next starts in the first.
        assertEquals("s1", page.original("ch1.xhtml", "rains. No", "It rains. Yes, it ")?.text)
        assertEquals("s0", page.original("ch1.xhtml", "rains. Yes", "It ")?.text)

        // Words selected in text left untranslated, which a translation happens to contain.
        assertEquals(null, page.original("ch1.xhtml", "rains", "Elsewhere it "))
    }

    @Test
    fun `a selection after a sentence left untranslated still finds its sentence`() = runTest {
        val element = listOf(
            PageSentence("Il pleut.", "ch1.xhtml", "#p0", null),
            PageSentence("Il fait très froid ici.", "ch1.xhtml", "#p0", "Il pleut."),
            PageSentence("Il pleut fort.", "ch1.xhtml", "#p0", "Il pleut. Il fait très froid ici."),
        )
        val run = FakeRun()
        val shown = mapOf("Il pleut." to "It rains.", "Il pleut fort." to "It pours.")
        run.answer = { shown.getValue(it) }
        run.failWith = { if (it !in shown) TranslationError.Refused() else null }
        val page = PageTranslation(backgroundScope, run, PageTranslationCache(), { at: Int ->
            object : PageSentences {
                var i = at
                override suspend fun next() = element.getOrNull(i++)
            }
        }, 10)
        page.start(0, "start")
        runCurrent()

        assertEquals("Il pleut fort.", page.original("ch1.xhtml", "pours", "It rains. Il fait très froid ici. It ")?.text)
        assertEquals("Il pleut.", page.original("ch1.xhtml", "rains", "It ")?.text)
        // In the sentence left untranslated, after a translated one: found by its own words.
        assertEquals("Il fait très froid ici.", page.original("ch1.xhtml", "froid", "It rains. Il fait très ")?.text)
    }

    @Test
    fun `paragraphs that open with the same translation are told apart by the one above`() = runTest {
        val paragraphs = listOf(
            PageSentence("Non.", "ch1.xhtml", "#p0", null),
            PageSentence("Il pleut.", "ch1.xhtml", "#p1", null),
            PageSentence("Non.", "ch1.xhtml", "#p2", null),
        )
        val run = FakeRun()
        val shown = mapOf("Non." to "No.", "Il pleut." to "It rains.")
        run.answer = { shown.getValue(it) }
        val page = PageTranslation(backgroundScope, run, PageTranslationCache(), { at: Int ->
            object : PageSentences {
                var i = at
                override suspend fun next() = paragraphs.getOrNull(i++)
            }
        }, 10)
        page.start(0, "start")
        runCurrent()

        assertSame(paragraphs[2], page.original("ch1.xhtml", "No", "No. It rains. "))
        assertSame(paragraphs[0], page.original("ch1.xhtml", "No", ""))
    }

    @Test
    fun `a paragraph translated before a jump keeps the one above it`() = runTest {
        val paragraphs = listOf(
            PageSentence("Il neige.", "ch1.xhtml", "#p0", null),
            PageSentence("Non.", "ch1.xhtml", "#p1", null),
            PageSentence("Il pleut.", "ch1.xhtml", "#p2", null),
            PageSentence("Non.", "ch1.xhtml", "#p3", null),
        )
        val run = FakeRun()
        val shown = mapOf("Non." to "No.", "Il neige." to "It snows.")
        run.answer = { shown.getValue(it) }
        val page = PageTranslation(backgroundScope, run, PageTranslationCache(), { at: Int ->
            object : PageSentences {
                var i = at
                override suspend fun next() = paragraphs.getOrNull(i++)
            }
        }, 2)
        page.start(0, "start")
        runCurrent()
        page.onReader(PageReader.Elsewhere(3, "ch1.xhtml#p3"))
        runCurrent()

        assertSame(paragraphs[3], page.original("ch1.xhtml", "No", "Il pleut. "))
        assertSame(paragraphs[1], page.original("ch1.xhtml", "No", "It snows. "))
    }

    @Test
    fun `a word said twice in one translation is found where it was selected`() = runTest {
        val element = listOf(PageSentence("Il pleut, puis il pleut.", "ch1.xhtml", "#p0", null))
        val run = FakeRun()
        run.answer = { "It rains, then it rains." }
        val page = PageTranslation(backgroundScope, run, PageTranslationCache(), { at: Int ->
            object : PageSentences {
                var i = at
                override suspend fun next() = element.getOrNull(i++)
            }
        }, 10)
        page.start(0, "start")
        runCurrent()

        assertEquals("Il pleut, puis il pleut.", page.original("ch1.xhtml", "rains", "It rains, then it ")?.text)
        assertEquals("Il pleut, puis il pleut.", page.original("ch1.xhtml", "rains", "It ")?.text)
    }
}
