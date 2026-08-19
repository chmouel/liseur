package com.chmouel.liseur.reader.footnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteResolverTest {

    /** How Standard Ebooks writes an endnote, which is most of the corpus. */
    private val standardEbooks = """
        <html xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <section id="endnotes" epub:type="endnotes footnotes">
              <ol>
                <li id="note-1" epub:type="endnote footnote">
                  <p>Is that my governess?
                    <a href="chapter-11.xhtml#noteref-1" epub:type="backlink">↩︎</a>
                  </p>
                </li>
              </ol>
            </section>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun `finds an endnote marked with epub type`() {
        val note = FootnoteResolver.noteAt(standardEbooks, "note-1")!!
        assertTrue(note, note.contains("Is that my governess?"))
    }

    @Test
    fun `finds a note marked only with an ARIA role`() {
        val html = """
            <body><div id="fn3" role="doc-endnote"><p>A note by role alone.</p></div></body>
        """.trimIndent()
        val note = FootnoteResolver.noteAt(html, "fn3")!!
        assertTrue(note, note.contains("A note by role alone."))
    }

    @Test
    fun `finds a note in an aside, which is what the spec suggests`() {
        val html = """<body><aside id="fn7"><p>Set in an aside.</p></aside></body>"""
        val note = FootnoteResolver.noteAt(html, "fn7")!!
        assertTrue(note, note.contains("Set in an aside."))
    }

    @Test
    fun `a chapter is not a note, however it was linked to`() {
        val html = """
            <body><section id="chapter-4" epub:type="chapter"><p>Long ago.</p></section></body>
        """.trimIndent()
        assertNull(FootnoteResolver.noteAt(html, "chapter-4"))
    }

    @Test
    fun `a paragraph a cross-reference points at is not a note`() {
        val html = """<body><p id="para-9">See above.</p></body>"""
        assertNull(FootnoteResolver.noteAt(html, "para-9"))
    }

    @Test
    fun `a fragment naming nothing resolves to nothing`() {
        assertNull(FootnoteResolver.noteAt(standardEbooks, "note-404"))
    }

    @Test
    fun `an empty note is not worth popping up`() {
        val html = """<body><aside id="fn1">   </aside></body>"""
        assertNull(FootnoteResolver.noteAt(html, "fn1"))
    }

    @Test
    fun `scripts do not survive the trip out of the book`() {
        val html = """
            <body><aside id="fn1"><p>Safe.</p><script>alert(1)</script></aside></body>
        """.trimIndent()
        val note = FootnoteResolver.noteAt(html, "fn1")!!
        assertTrue(note, note.contains("Safe."))
        assertTrue(note, !note.contains("alert"))
    }

    @Test
    fun `the note keeps its own markup`() {
        val html = """<body><aside id="fn1"><p>An <em>emphatic</em> note.</p></aside></body>"""
        val note = FootnoteResolver.noteAt(html, "fn1")!!
        assertTrue(note, note.contains("<em>emphatic</em>"))
    }

    @Test
    fun `unparseable input is answered with nothing rather than a crash`() {
        assertEquals(null, FootnoteResolver.noteAt("", "fn1"))
    }
}
