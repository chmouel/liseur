package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectionHandleFixTest {

    @Test
    fun `the javascript answer is read through its json quoting`() {
        assertEquals(SelectionHandleFix.Result.CHANGED, SelectionHandleFix.parse("\"changed\""))
        assertEquals(SelectionHandleFix.Result.STABLE, SelectionHandleFix.parse("\"stable\""))
        assertEquals(SelectionHandleFix.Result.BLOCKED, SelectionHandleFix.parse("\"blocked\""))
        assertEquals(SelectionHandleFix.Result.STABLE, SelectionHandleFix.parse(" stable "))
    }

    @Test
    fun `no answer is a failure, never a silent success`() {
        assertEquals(SelectionHandleFix.Result.FAILED, SelectionHandleFix.parse(null))
        assertEquals(SelectionHandleFix.Result.FAILED, SelectionHandleFix.parse(""))
        assertEquals(SelectionHandleFix.Result.FAILED, SelectionHandleFix.parse("null"))
        assertEquals(SelectionHandleFix.Result.FAILED, SelectionHandleFix.parse("\"whatever\""))
    }

    @Test
    fun `the script is one expression, so evaluateJavascript returns its value`() {
        val script = SelectionHandleFix.SCRIPT.trim()
        assertTrue(script.startsWith("(function"))
        assertTrue(script.endsWith("})();"))
    }

    @Test
    fun `two non-breaking spaces lead the block, since offset one still triggers the bug`() {
        // U+00A0, not a zero-width space: Blink's ::first-letter skips spaces
        // and lands on the real letter, whereas a ZWSP would become the drop cap.
        assertTrue(SelectionHandleFix.SCRIPT.contains("\\u00a0\\u00a0"))
        assertFalse(SelectionHandleFix.SCRIPT.contains("\\u200b"))
        assertFalse(SelectionHandleFix.SCRIPT.contains("\\200b"))
    }

    @Test
    fun `the spaces take no room and use generated content alt text where available`() {
        // Font size alone is not enough: letter- and word-spacing inherit as
        // computed lengths and would give the invisible spaces a width.
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("font-size:0!important"))
        assertTrue(script.contains("line-height:0!important"))
        assertTrue(script.contains("letter-spacing:0!important"))
        assertTrue(script.contains("word-spacing:0!important"))
        assertTrue(script.contains("CSS.supports(\"content\", '\"x\" / \"\"')"))
        assertTrue(script.contains("content += ' / \"\"'"))
        assertTrue(script.contains("+ ' !important;'"))
        assertTrue(script.contains("content:\"\\u00a0\\u00a0\""))
    }

    @Test
    fun `an author's dormant pseudo-element styles get no box from our content`() {
        // `p::before { display: block; margin-top: 1em }` with no content
        // renders nothing until content arrives; ours must not wake it.
        val script = SelectionHandleFix.SCRIPT
        for (reset in listOf(
            "display:inline!important", "position:static!important", "float:none!important",
            "margin:0!important", "padding:0!important", "border:0!important",
            "background:none!important", "width:auto!important", "height:auto!important",
            "box-shadow:none!important", "outline:none!important",
            "vertical-align:baseline!important", "counter-increment:none!important",
            "counter-reset:none!important", "counter-set:none!important",
        )) {
            assertTrue(reset, script.contains(reset))
        }
    }

    @Test
    fun `the rule is keyed on a per-document token, never a blanket selector`() {
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("state.token"))
        assertTrue(script.contains("state.attr"))
        assertTrue(script.contains("data-liseur-lead-"))
        assertTrue(script.contains("SEL = '[' + ATTR + ']'"))
        assertTrue(script.contains("SEL + '::before"))
        assertFalse(script.contains("p::before"))
        assertFalse(script.contains("*::before"))
        assertFalse(script.contains("getElementById"))
        assertFalse(script.contains("\"data-liseur-lead\""))
    }

    @Test
    fun `only blocks that open with inline content are marked`() {
        // A ::before on a block of blocks gets its own line box and pushes the
        // content down a line, so the first in-flow child is what decides.
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("childNodes"))
        assertTrue(script.contains("s.getPropertyValue(\"float\") !== \"none\""))
        assertTrue(script.contains("d === \"contents\""))
        assertTrue(script.contains("startsInline(el) !== true"))
        assertTrue(script.contains("return null"))
        assertTrue(script.contains("indexOf(\"inline\") !== 0 && d.indexOf(\"ruby\") !== 0) return false"))
    }

    @Test
    fun `every block is a candidate whatever its tag`() {
        // A figure holding text or a custom element styled as a block has
        // the bug as much as a p; the computed display decides, not the tag.
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("getElementsByTagName(\"*\")"))
        assertFalse(script.contains("\"p,li"))
        assertFalse(script.contains("SKIP"))
    }

    @Test
    fun `an empty inline is looked through`() {
        // A chapter often opens with `<a id="..."></a>` before its heading;
        // treating that anchor as inline content would mark the section and
        // put a blank line above the heading.
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("if (d === \"inline\" && !ATOMIC[node.localName]) return startsInline(node)"))
        assertTrue(script.contains("\"img\": 1"))
        assertTrue(script.contains("\"br\": 1"))
    }

    @Test
    fun `a cancelled caller is not reported as a failed page`() {
        val source = File(System.getProperty("user.dir"), "src/main/kotlin/com/chmouel/liseur/reader/SelectionHandleFix.kt").readText()
        assertTrue(source.contains("catch (cancellation: CancellationException)"))
        assertTrue(source.contains("throw cancellation"))
        assertFalse(source.contains("runCatching"))
    }

    @Test
    fun `an author's own generated content is left alone`() {
        assertTrue(SelectionHandleFix.SCRIPT.contains("getComputedStyle(el, \"::before\").content"))
        assertTrue(SelectionHandleFix.SCRIPT.contains("before !== \"none\" && before !== \"normal\""))
    }

    @Test
    fun `the author's own markup is never rewritten`() {
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("setAttribute(ATTR, \"\")"))
        assertTrue(script.contains("removeAttribute(ATTR)"))
        assertFalse(script.contains("className"))
        assertFalse(script.contains("classList"))
        assertFalse(script.contains("innerHTML"))
        assertFalse(script.contains("insertBefore"))
        assertFalse(script.contains("createTextNode"))
    }

    @Test
    fun `eligibility is rechecked on every pass`() {
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("document.querySelectorAll(SEL)"))
        assertTrue(script.contains("removeAttribute(ATTR)"))
        assertTrue(script.contains("new Set()"))
        assertTrue(script.contains("had.has(el)"))
        assertTrue(script.contains("had.size"))
        assertTrue(script.contains("getComputedStyle(el, \"::before\").content"))
        assertFalse(script.contains("state.marked"))
    }

    @Test
    fun `a higher specificity authored rule cannot leave an unsafe marker`() {
        val script = SelectionHandleFix.SCRIPT
        assertTrue(script.contains("function safePseudo(style)"))
        assertTrue(script.contains("if (!safePseudo(getComputedStyle(el, \"::before\")))"))
        assertTrue(script.contains("el.removeAttribute(ATTR)"))
        assertTrue(script.contains("neutralCounter(style, name)"))
    }

    @Test
    fun `a stylesheet the book's policy refused is reported rather than assumed`() {
        assertTrue(SelectionHandleFix.SCRIPT.contains("if (!state.styleEl.sheet) return \"blocked\""))
        assertFalse(SelectionHandleFix.SCRIPT.contains("if (!css.sheet) return \"blocked\""))
    }
}
