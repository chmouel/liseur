package com.chmouel.liseur.reader.footnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteLayoutTest {

    @Test
    fun `the javascript answer is read through its json quoting`() {
        assertEquals(FootnoteLayout.Result.CHANGED, FootnoteLayout.parse("\"changed\""))
        assertEquals(FootnoteLayout.Result.STABLE, FootnoteLayout.parse("\"stable\""))
        assertEquals(FootnoteLayout.Result.BLOCKED, FootnoteLayout.parse("\"blocked\""))
    }

    @Test
    fun `no answer is a failure, never a silent success`() {
        assertEquals(FootnoteLayout.Result.FAILED, FootnoteLayout.parse(null))
        assertEquals(FootnoteLayout.Result.FAILED, FootnoteLayout.parse(""))
        assertEquals(FootnoteLayout.Result.FAILED, FootnoteLayout.parse("null"))
        assertEquals(FootnoteLayout.Result.FAILED, FootnoteLayout.parse("undefined"))
    }

    @Test
    fun `the script is one expression, so evaluateJavascript returns its value`() {
        val script = FootnoteLayout.SCRIPT.trim()
        assertTrue(script.startsWith("(function"))
        assertTrue(script.endsWith("})();"))
    }

    // The page and the card have to agree about what a note is, or the same
    // note is hidden without being poppable, or popped without being hidden.
    // The script spells the vocabulary out as literals so the headless
    // harness can run it as plain text; this is what stops the two drifting.

    @Test
    fun `the script knows the same note types as the resolver`() {
        assertEquals(NoteVocabulary.NOTE_TYPES, scriptWords("NOTE_TYPES"))
    }

    @Test
    fun `the script knows the same note roles as the resolver`() {
        assertEquals(NoteVocabulary.NOTE_ROLES, scriptWords("NOTE_ROLES"))
    }

    @Test
    fun `the script knows the same reference vocabulary`() {
        assertEquals(NoteVocabulary.REF_TYPES, scriptWords("REF_TYPES"))
        assertEquals(NoteVocabulary.REF_ROLES, scriptWords("REF_ROLES"))
    }

    @Test
    fun `the script falls back to the same bare element`() {
        assertTrue(FootnoteLayout.SCRIPT.contains("var NOTE_TAG = \"${NoteVocabulary.NOTE_TAG}\";"))
    }

    @Test
    fun `the script uses the same majority threshold`() {
        assertTrue(
            FootnoteLayout.SCRIPT.contains("var MOSTLY_NOTES = ${FootnoteLayout.MOSTLY_NOTES};"),
        )
    }

    @Test
    fun `a note is hidden only because something in the page points at it`() {
        // Notes are reached through the anchors that reference them. Sweeping
        // the document for asides instead would empty a chapter of endnotes,
        // which is a chapter somebody meant to be read.
        assertTrue(FootnoteLayout.SCRIPT.contains("getElementsByTagName(\"a\")"))
        assertFalse(FootnoteLayout.SCRIPT.contains("querySelectorAll(\"aside"))
    }

    @Test
    fun `a link from inside a note is not evidence the note was referenced`() {
        assertTrue(FootnoteLayout.SCRIPT.contains("insideNote(anchor)"))
    }

    @Test
    fun `a document that is mostly notes is left alone entirely`() {
        assertTrue(FootnoteLayout.SCRIPT.contains("held / page > MOSTLY_NOTES"))
    }

    @Test
    fun `the marker is owned by a per-document token, not a guessable name`() {
        assertTrue(FootnoteLayout.SCRIPT.contains("state.token"))
    }

    @Test
    fun `the author's own markup is never rewritten`() {
        assertFalse(FootnoteLayout.SCRIPT.contains("className"))
        assertFalse(FootnoteLayout.SCRIPT.contains("classList"))
        assertFalse(FootnoteLayout.SCRIPT.contains("innerHTML"))
        // The one thing given any content is the stylesheet we made
        // ourselves; nothing the publisher shipped is ever written to.
        FootnoteLayout.SCRIPT.lineSequence()
            .filter { it.contains("textContent =") }
            .forEach { assertTrue(it, it.trim().startsWith("css.textContent =")) }
    }

    @Test
    fun `a stylesheet the book's policy refused is reported rather than assumed`() {
        assertTrue(FootnoteLayout.SCRIPT.contains("if (!css.sheet) return \"blocked\""))
    }

    @Test
    fun `a marker is sized against the text rather than the screen`() {
        // ReadiumCSS caps images at 95vh, which is most of a phone. An `em`
        // is the only unit that keeps a marker the size of the words it sits
        // between when the reader changes the type size.
        assertTrue(FootnoteLayout.SCRIPT.contains("max-height:1.2em!important"))
        assertFalse(FootnoteLayout.SCRIPT.contains("vh!important"))
    }

    @Test
    fun `both epub attribute spellings are looked for`() {
        // Served as XHTML the attribute is namespaced; served as HTML it is
        // not, and books arrive both ways.
        assertTrue(FootnoteLayout.SCRIPT.contains("getAttributeNS(EPUB_NS, \"type\")"))
        assertTrue(FootnoteLayout.SCRIPT.contains("getAttribute(\"epub:type\")"))
    }

    @Test
    fun `a fragment is quoted into the reveal script, never pasted into it`() {
        val script = FootnoteLayout.revealScript("n\"1")
        assertTrue(script.contains("""var id = "n\"1";"""))
    }

    @Test
    fun `a fragment cannot close the script it is carried in`() {
        val script = FootnoteLayout.revealScript("</script><img onerror=alert(1)>")
        assertFalse(script.contains("</script>"))
    }

    @Test
    fun `a fragment outside plain ascii survives as an escape`() {
        assertEquals("\"\\u6ce8\"", FootnoteLayout.jsString("注"))
        assertEquals("\"\\u000a\"", FootnoteLayout.jsString("\n"))
        assertEquals("\"note-1\"", FootnoteLayout.jsString("note-1"))
    }

    @Test
    fun `revealing is remembered, or the next layout pass hides it again`() {
        val script = FootnoteLayout.revealScript("note-1")
        assertTrue(script.contains("revealed[id] = true"))
        assertTrue(script.contains("removeAttribute(\"data-liseur-note\")"))
    }

    /** The words of a `var NAME = [...]` list in the script. */
    private fun scriptWords(name: String): Set<String> {
        val list = Regex("var $name = \\[([^\\]]*)]")
            .find(FootnoteLayout.SCRIPT)
            ?.groupValues
            ?.get(1)
        requireNotNull(list) { "$name is not declared in the script" }
        return list.split(',')
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
