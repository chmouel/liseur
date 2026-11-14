package com.chmouel.liseur.domain.quotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyQuoteTest {
    private val candidates = (1..4).flatMap { book ->
        (1..5).map { line -> QuoteCandidate("book$book", "Line $line of book $book.", null, null, score = line.toDouble()) }
    }

    @Test
    fun `the same quote all day, whatever order the candidates come in`() {
        val first = DailyQuote.pick(candidates, 20_000, 0)
        assertEquals(first, DailyQuote.pick(candidates.shuffled(), 20_000, 0))
    }

    @Test
    fun `asking for another moves to another book until each has had its turn`() {
        val books = (0 until 4).map { DailyQuote.pick(candidates, 20_000, it)?.bookUrl }
        assertEquals(4, books.toSet().size)
        assertEquals(books[0], DailyQuote.pick(candidates, 20_000, 4)?.bookUrl)
    }

    @Test
    fun `a book's highlights are quoted rather than its found lines`() {
        val highlight = QuoteCandidate("book1", "Chosen by the reader.", null, null, highlight = true)
        val only = candidates.filter { it.bookUrl == "book1" } + highlight
        repeat(10) { assertEquals(highlight, DailyQuote.pick(only, 20_000L + it, 0)) }
    }

    @Test
    fun `the line already shown is kept when the candidates change under it`() {
        val kept = QuoteCandidate("book3", "The line on screen.", null, null, score = 1.0)
        val withKept = candidates + kept
        val key = DailyQuote.key(kept)

        // A scan finishing or a book becoming eligible changes the set
        // the day seeds; the reader should not watch the line swap.
        repeat(5) { assertEquals(kept, DailyQuote.pick(withKept, 20_000L + it, 0, keep = key)) }
    }

    @Test
    fun `a kept line that is gone leaves the day's own pick`() {
        val kept = DailyQuote.key(QuoteCandidate("book1", "A line since removed.", null, null))
        assertEquals(
            DailyQuote.pick(candidates, 20_000, 0),
            DailyQuote.pick(candidates, 20_000, 0, keep = kept),
        )
    }

    @Test
    fun `what Home remembers is not the words of the quote`() {
        val secret = QuoteCandidate("file:///books/moby-dick.epub", "Call me Ishmael.", null, null)
        val key = DailyQuote.key(secret)

        // The store that holds this is carried by Android's backup, and
        // a quote is meant to stay on the device.
        assertFalse(key.contains("Ishmael"))
        assertFalse(key.contains("moby-dick"))
        assertTrue(key.length >= 32)
        assertEquals(key, DailyQuote.key(secret.copy()))
        assertNotEquals(key, DailyQuote.key(secret.copy(text = "Call me Jonah.")))
    }

    @Test
    fun `nothing to quote gives nothing`() {
        assertNull(DailyQuote.pick(emptyList(), 20_000, 0))
    }

    @Test
    fun `a long quote is cut at a word with an ellipsis`() {
        val text = "word ".repeat(100).trim()
        val short = DailyQuote.shorten(text, 50)
        assertTrue(short.length <= 50)
        assertTrue(short.endsWith("word…"))
        assertEquals("Two lines here.", DailyQuote.shorten("Two  lines\nhere."))
        assertEquals("the more—man", DailyQuote.shorten("the more\uFEFF—\u2060man"))
        assertNotEquals(text, short)
    }
}
