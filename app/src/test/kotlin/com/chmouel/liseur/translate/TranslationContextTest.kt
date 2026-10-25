package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.TranslationContextSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationContextTest {
    private fun at(text: String, selector: String = "#p1", href: String = "ch1.xhtml", before: String? = null) =
        PageSentence(text, href, selector, before)

    // A chapter read from its start, each sentence's text before it as Readium gives it.
    private fun chapter(vararg sentences: Pair<String, String>, href: String = "ch1.xhtml"): List<PageSentence> {
        var before = ""
        return sentences.map { (selector, text) ->
            at(text, selector, href, before.ifEmpty { null }).also { before += "$text " }
        }
    }

    private class Opened(val book: List<PageSentence>) {
        var opens = 0
        var read = 0

        fun open(href: String): PageSentences {
            opens++
            val from = book.indexOfFirst { it.href == href }
            var at = from
            return PageSentences { book.getOrNull(at)?.also { at++; read++ } }
        }
    }

    @Test
    fun `the window keeps the nearest whole sentences within both limits`() {
        val small = TranslationContextSize.SMALL
        val earlier = (1..6).map { at("Sentence $it.") }
        assertEquals("Sentence 3. Sentence 4. Sentence 5. Sentence 6.", TranslationContext.window(earlier, small))
        val long = listOf(at("a".repeat(small.characters - 5)), at("bb"), at("cc"))
        // "bb cc" fits; the long one with its separator would not, so it is left out whole.
        assertEquals("bb cc", TranslationContext.window(long, small))
    }

    @Test
    fun `the window counts its separators and breaks lines between elements`() {
        val earlier = listOf(at("One.", "#p1"), at("Two.", "#p1"), at("Three.", "#p2"))
        assertEquals("One. Two.\nThree.", TranslationContext.window(earlier, TranslationContextSize.STANDARD))
        // Two halves of the budget fill it, and the space between them is one too many.
        val half = TranslationContextSize.SMALL.characters / 2
        val halves = listOf(at("a".repeat(half)), at("b".repeat(half)))
        assertEquals("b".repeat(half), TranslationContext.window(halves, TranslationContextSize.SMALL))
    }

    @Test
    fun `a nearest sentence longer than the budget goes as its end, never half a character`() {
        val emoji = "\uD83D\uDE00"
        val size = TranslationContextSize.SMALL
        val text = "x" + emoji.repeat(size.characters)
        val window = TranslationContext.window(listOf(at(text)), size)!!
        assertTrue(window.length <= size.characters)
        assertTrue(!window.first().isLowSurrogate())
        assertTrue(text.endsWith(window))
    }

    @Test
    fun `a size not stored, or not known, is the standard one`() {
        assertEquals(TranslationContextSize.STANDARD, TranslationContextSize.fromId(null))
        assertEquals(TranslationContextSize.STANDARD, TranslationContextSize.fromId("huge"))
        TranslationContextSize.entries.forEach { assertEquals(it, TranslationContextSize.fromId(it.id)) }
    }

    @Test
    fun `off or nothing earlier sends nothing`() {
        assertNull(TranslationContext.window(listOf(at("One.")), TranslationContextSize.OFF))
        assertNull(TranslationContext.window(emptyList(), TranslationContextSize.STANDARD))
    }

    @Test
    fun `the sentences before one come from its chapter, never itself or later`() = runTest {
        val book = chapter("#p1" to "Elle entra.", "#p1" to "Il la vit.", "#p2" to "Puis il partit.", "#p2" to "Fin.") +
            chapter("#p1" to "Suite.", href = "ch2.xhtml")
        val opened = Opened(book)
        val preceding = PrecedingSentences(opened::open)
        assertEquals(emptyList<PageSentence>(), preceding.before(book[0], 8))
        assertEquals(book.subList(0, 2).map { it.text }, preceding.before(book[2], 8)!!.map { it.text })
        assertEquals(listOf("Il la vit."), preceding.before(book[2], 1)!!.map { it.text })
        // Another chapter starts with nothing before it.
        assertEquals(emptyList<PageSentence>(), preceding.before(book[4], 8))
    }

    @Test
    fun `the same words twice are told apart by where they are`() = runTest {
        val book = chapter("#p1" to "Oui.", "#p1" to "Non.", "#p1" to "Oui.")
        val preceding = PrecedingSentences(Opened(book)::open)
        assertEquals(listOf("Oui.", "Non."), preceding.before(book[2], 8)!!.map { it.text })
        assertEquals(emptyList<String>(), preceding.before(book[0], 8)!!.map { it.text })
    }

    @Test
    fun `a repeat that cannot be told apart never brings itself or what follows`() = runTest {
        val ending = " walked slowly back along the river towards the old stone bridge in the rain."
        val book = chapter(
            "#p1" to "Marie$ending", "#p1" to "Il attendait.", "#p1" to "B.", "#p1" to "C.",
            "#p1" to "Pierre$ending", "#p1" to "Il attendait.", "#p1" to "D.",
        )
        val preceding = PrecedingSentences(Opened(book)::open)
        preceding.before(book[6], 8)
        preceding.before(book[3], 8)
        assertEquals(listOf("Marie$ending"), preceding.before(book[1], 8)!!.map { it.text })
        // The later repeat looks the same, so it is taken at the first place: less context, nothing after it.
        assertEquals(listOf("Marie$ending"), preceding.before(book[5], 8)!!.map { it.text })
    }

    @Test
    fun `a sentence asked again or before the last one is not read twice`() = runTest {
        val book = chapter(*(1..20).map { "#p1" to "S$it." }.toTypedArray())
        val opened = Opened(book)
        val preceding = PrecedingSentences(opened::open)
        preceding.before(book[15], 4)
        val read = opened.read
        preceding.before(book[3], 4)
        preceding.before(book[15], 4)
        assertEquals(read, opened.read)
        assertEquals(1, opened.opens)
    }

    @Test
    fun `a passage of several sentences is found at its first sentence`() = runTest {
        val book = chapter("#p1" to "Un.", "#p1" to "Deux.", "#p1" to "Trois.", "#p1" to "Quatre.", "#p1" to "Cinq.")
        val passage = { first: Int -> at("${book[first].text} ${book[first + 1].text}", "#p1", "ch1.xhtml", book[first].before) }
        val preceding = PrecedingSentences(Opened(book)::open)
        assertEquals(listOf("Un.", "Deux."), preceding.before(passage(2), 8)!!.map { it.text })
        // Found again once walked, without reading on.
        assertEquals(listOf("Un."), preceding.before(passage(1), 8)!!.map { it.text })
        // Words that only share the start of a sentence are not that sentence.
        assertNull(preceding.before(at("Troisième.", "#p1", "ch1.xhtml", book[2].before), 8))
    }

    @Test
    fun `a passage past an earlier sentence with the same start is found again`() = runTest {
        val ending = " walked slowly back along the river towards the old stone bridge in the rain."
        val book = chapter("#p1" to "Marie$ending", "#p1" to "Il attendait.", "#p1" to "Pierre$ending", "#p1" to "Il partit.", "#p1" to "Fin.")
        val passage = at("Il partit. Fin.", "#p1", "ch1.xhtml", book[3].before)
        val preceding = PrecedingSentences(Opened(book)::open)
        val first = preceding.before(passage, 8)!!.map { it.text }
        assertEquals(listOf("Marie$ending", "Il attendait.", "Pierre$ending"), first)
        assertEquals(first, preceding.before(passage, 8)?.map { it.text })
        // Asked first after its start was already walked past, it is found as on a fresh walk.
        val warm = PrecedingSentences(Opened(book)::open)
        assertEquals(listOf("Marie$ending", "Il attendait.", "Pierre$ending", "Il partit."), warm.before(book[4], 8)!!.map { it.text })
        assertEquals(first, warm.before(passage, 8)?.map { it.text })
    }

    @Test
    fun `a sentence not in its chapter is not found`() = runTest {
        val book = chapter("#p1" to "Un.", "#p1" to "Deux.")
        val preceding = PrecedingSentences(Opened(book)::open)
        assertNull(preceding.before(at("Ailleurs.", before = "Un. Deux. "), 8))
        val failing = PrecedingSentences({ throw IllegalStateException("unreadable") })
        assertNull(failing.before(book[1], 8))
    }

    private class Recording(var size: TranslationContextSize? = TranslationContextSize.STANDARD) : SentenceTranslator {
        override val source = "fr"
        override val target = "en"
        override val destination: String? = null
        var identity = "svc"
        val asked = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun answering() = identity

        override suspend fun contextSize(identity: String) = size

        override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
            asked += context
            gate?.await()
            return Translated(sentence.uppercase(), identity)
        }

        override fun close() = Unit
    }

    @Test
    fun `the page and the voice send the book's earlier text, whoever asks first`() = runTest {
        val book = chapter("#p1" to "Elle entra.", "#p1" to "Il la vit.", "#p2" to "Puis il partit.")
        val translator = Recording().apply { gate = CompletableDeferred() }
        val translations = SentenceTranslations(translator, MemoryPageTranslationCache(), PrecedingSentences(Opened(book)::open))
        // A start mid-chapter knows no sentence before; the voice knows only one.
        val page = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(book[2], null) }
        val voice = async(start = CoroutineStart.UNDISPATCHED) { translations.resolve(book[2], "Il la vit.") }
        runCurrent()
        translator.gate!!.complete(Unit)
        assertEquals(page.await(), voice.await())
        assertEquals(listOf<String?>("Elle entra. Il la vit."), translator.asked)
    }

    @Test
    fun `fallback context respects the selected size when the sentence cannot be found`() = runTest {
        val size = TranslationContextSize.SMALL
        val fallback = "x" + "\uD83D\uDE00".repeat(size.characters)
        val expected = TranslationContext.window(listOf(at(fallback)), size)
        for (preceding in listOf(null, PrecedingText { _, _ -> null })) {
            val translator = Recording(size)
            SentenceTranslations(translator, MemoryPageTranslationCache(), preceding)
                .resolve(at("Ailleurs."), fallback)
            assertEquals(listOf(expected), translator.asked)
        }
    }

    @Test
    fun `a service that takes no context gets none, and one that cannot find the place gets the caller's`() = runTest {
        val book = chapter("#p1" to "Un.", "#p1" to "Deux.")
        val off = Recording(TranslationContextSize.OFF)
        SentenceTranslations(off, MemoryPageTranslationCache(), PrecedingSentences(Opened(book)::open)).resolve(book[1], "Un.")
        assertEquals(listOf<String?>(null), off.asked)

        val lost = Recording()
        SentenceTranslations(lost, MemoryPageTranslationCache(), PrecedingSentences(Opened(book)::open))
            .resolve(at("Ailleurs.", before = "x"), "Avant.")
        assertEquals(listOf<String?>("Avant."), lost.asked)

        val plain = Recording(null)
        SentenceTranslations(plain, MemoryPageTranslationCache(), PrecedingSentences(Opened(book)::open)).resolve(book[1], "Un.")
        assertEquals(listOf<String?>("Un."), plain.asked)
    }
}
