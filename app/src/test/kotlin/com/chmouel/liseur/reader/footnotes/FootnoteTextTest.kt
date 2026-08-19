package com.chmouel.liseur.reader.footnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteTextTest {

    @Test
    fun `the backlink goes, because there is nowhere to go back to`() {
        val html = """<p>Is that my governess? <a href="chapter-11.xhtml#noteref-1">↩︎</a></p>"""
        val card = FootnoteText.forCard(html)!!
        assertTrue(card, !card.contains("↩"))
        assertEquals("Is that my governess?", FootnoteText.plainText(card))
    }

    @Test
    fun `a backlink still wearing its role goes too`() {
        val html = """<p>A note. <a role="doc-backlink" href="#ref">back</a></p>"""
        val card = FootnoteText.forCard(html)!!
        assertEquals("A note.", FootnoteText.plainText(card))
    }

    @Test
    fun `a link that is part of the note keeps its words`() {
        val html = """<p>See <a href="other.xhtml">the appendix</a> for more.</p>"""
        val card = FootnoteText.forCard(html)!!
        assertEquals("See the appendix for more.", FootnoteText.plainText(card))
        assertTrue(card, !card.contains("<a"))
    }

    @Test
    fun `emphasis survives, because it is part of what was written`() {
        val html = "<p>The <em>Ligue des Rats</em>, a fable.</p>"
        val card = FootnoteText.forCard(html)!!
        assertTrue(card, card.contains("<em>"))
    }

    @Test
    fun `images go, since the card has no way to draw them`() {
        val html = """<p>A plate.<img src="plate.jpg" alt="plate"/></p>"""
        val card = FootnoteText.forCard(html)!!
        assertTrue(card, !card.contains("img"))
    }

    @Test
    fun `entities and hard spaces come out as the characters they name`() {
        val html = "<p>Mesdames,&#160;vous &#234;tes servies!</p>"
        assertEquals("Mesdames, vous êtes servies!", FootnoteText.plainText(FootnoteText.forCard(html)!!))
    }

    @Test
    fun `a note that is only a backlink is nothing worth showing`() {
        val html = """<p><a href="#ref">↩</a></p>"""
        assertNull(FootnoteText.forCard(html))
    }

    @Test
    fun `several paragraphs stay several paragraphs`() {
        val html = "<p>First.</p><p>Second.</p>"
        val card = FootnoteText.forCard(html)!!
        assertEquals(2, Regex("<p>").findAll(card).count())
    }
}
