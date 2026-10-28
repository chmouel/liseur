package com.chmouel.liseur.tts

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NoteMarksTest {
    private fun spoken(body: String) = Jsoup.parse(NoteMarks.strip("<html><body>$body</body></html>")).body().text()

    @Test
    fun `drops marks labelled as note references`() {
        assertEquals("procession. Grant it", spoken("""<p>procession.<a href="#n1" epub:type="noteref">1</a> Grant it</p>"""))
        assertEquals("a b", spoken("""<p>a<a href="#n2" role="doc-noteref">2</a> b</p>"""))
    }

    @Test
    fun `drops a bare superscript link and its superscript`() {
        assertEquals("text more", spoken("""<p>text<sup>[<a href="#n3">3</a>]</sup> more</p>"""))
        assertEquals("text more", spoken("""<p>text<a href="#n4"><sup>*</sup></a> more</p>"""))
    }

    @Test
    fun `keeps superscripts and links that are not marks`() {
        val html = """<html><body><p>E = mc<sup>2</sup>, see <a href="#c2">chapter two</a></p></body></html>"""
        assertSame(html, NoteMarks.strip(html))
    }

    @Test
    fun `keeps the mark elements so selectors still match`() {
        val stripped = Jsoup.parse(NoteMarks.strip("""<p>a<a id="r1" epub:type="noteref">1</a></p><p>b</p>"""))
        assertEquals(1, stripped.select("#r1").size)
        assertEquals("b", stripped.select("body > p:nth-child(2)").text())
    }
}
