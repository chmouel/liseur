package com.chmouel.liseur.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideContentFitTest {

    @Test
    fun `the javascript answer is read through its json quoting`() {
        assertEquals(WideContentFit.Result.CHANGED, WideContentFit.parse("\"changed\""))
        assertEquals(WideContentFit.Result.STABLE, WideContentFit.parse("\"stable\""))
        assertEquals(WideContentFit.Result.BLOCKED, WideContentFit.parse("\"blocked\""))
    }

    @Test
    fun `an unquoted answer is read too`() {
        assertEquals(WideContentFit.Result.CHANGED, WideContentFit.parse("changed"))
        assertEquals(WideContentFit.Result.STABLE, WideContentFit.parse(" stable "))
    }

    @Test
    fun `no answer is a failure, never a silent success`() {
        assertEquals(WideContentFit.Result.FAILED, WideContentFit.parse(null))
        assertEquals(WideContentFit.Result.FAILED, WideContentFit.parse(""))
        assertEquals(WideContentFit.Result.FAILED, WideContentFit.parse("null"))
        assertEquals(WideContentFit.Result.FAILED, WideContentFit.parse("undefined"))
        assertEquals(WideContentFit.Result.FAILED, WideContentFit.parse("\"whatever\""))
    }

    @Test
    fun `the script is one expression, so evaluateJavascript returns its value`() {
        val script = WideContentFit.SCRIPT.trim()
        assertTrue(script.startsWith("(function"))
        assertTrue(script.endsWith("})();"))
    }

    @Test
    fun `width is measured per fragment, not from the union of them`() {
        // getBoundingClientRect() spans every column a table is spread over, so
        // a long index that fits reports several times the page width.
        assertTrue(WideContentFit.SCRIPT.contains("getClientRects"))
        assertFalse(WideContentFit.SCRIPT.contains("getBoundingClientRect"))
    }

    @Test
    fun `breaking words anywhere is confined to what was measured as too wide`() {
        WideContentFit.SCRIPT.lineSequence()
            .filter { it.contains("overflow-wrap:anywhere") }
            .forEach { assertTrue(it, it.contains("SEL")) }
        assertTrue(WideContentFit.SCRIPT.contains("overflow-wrap:anywhere"))
    }

    @Test
    fun `the marker is owned by a per-document token, not a guessable name`() {
        assertTrue(WideContentFit.SCRIPT.contains("state.token"))
        assertFalse(WideContentFit.SCRIPT.contains("liseur-wide"))
        assertFalse(WideContentFit.SCRIPT.contains("getElementById"))
    }

    @Test
    fun `the author's own markup is never rewritten`() {
        // Only our attribute is ever removed; a class the publisher shipped
        // stays exactly where they put it.
        assertTrue(WideContentFit.SCRIPT.contains("removeAttribute(ATTR)"))
        assertFalse(WideContentFit.SCRIPT.contains("className"))
        assertFalse(WideContentFit.SCRIPT.contains("classList"))
        assertFalse(WideContentFit.SCRIPT.contains("innerHTML"))
    }

    @Test
    fun `a stylesheet the book's policy refused is reported rather than assumed`() {
        assertTrue(WideContentFit.SCRIPT.contains("if (!css.sheet) return \"blocked\""))
    }
}
