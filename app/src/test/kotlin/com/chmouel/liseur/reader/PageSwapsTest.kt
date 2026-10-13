package com.chmouel.liseur.reader

import com.chmouel.liseur.translate.PageSentence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.RhinoException

/**
 * The page scripts are checked against the generated code and the
 * selection range logic runs against a small JavaScript DOM fixture.
 */
class PageSwapsTest {

    private val sentence = PageSentence(
        text = "It was a dark and stormy night.",
        href = "/EPUB/text/ch1.xhtml",
        selector = "p.first",
        before = "Chapter One ",
    )

    private fun script(fx: Float = 0.5f, fy: Float = 0.5f): String =
        PageSwaps.markedAt("https://book/EPUB/text/ch1.xhtml", sentence, fx, fy)

    /** Only the markedAt half of the page's script, not its neighbours. */
    private fun body(): String = script()
        .substringAfter("markedAt(url, s, fx, fy) {")
        .substringBefore("restore() {")

    private fun bodyAfter(script: String, marker: String): String {
        val bodyStart = script.indexOf(marker) + marker.length
        var depth = 1
        var bodyEnd = bodyStart
        while (depth > 0) {
            when (script[bodyEnd]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth > 0) bodyEnd++
        }
        return script.substring(bodyStart, bodyEnd)
    }

    private fun selectionIntersects(node: String, start: Int, end: Int): Boolean {
        val script = PageSwaps.selectionIntersectsAppliedSwap(
            "https://book/EPUB/text/ch1.xhtml",
            sentence,
        )
        val functionBody = bodyAfter(script, "selectionIntersectsAppliedSwap(url, s) {")
        val rangeStart = functionBody.indexOf("const range = selection.getRangeAt(0);")
        val rangeLogic = functionBody.substring(rangeStart)
            .replace(
                "for (const [node, extent] of bounds) {",
                """
                    for (var boundIndex = 0; boundIndex < bounds.length; boundIndex++) {
                      var node = bounds[boundIndex][0];
                      var extent = bounds[boundIndex][1];
                """.trimIndent(),
            )
            .replace(
                "for (const edit of edits.get(node) || []) {",
                """
                    var nodeEdits = edits.get(node) || [];
                    for (var editIndex = 0; editIndex < nodeEdits.length; editIndex++) {
                      var edit = nodeEdits[editIndex];
                """.trimIndent(),
            )
            .replace(Regex("\\bconst\\b"), "var")
        val shownBody = bodyAfter(script, "const shown = (node, at) => {")
            .replace(
                "for (const e of list) {",
                """
                    for (var shownIndex = 0; shownIndex < list.length; shownIndex++) {
                      var e = list[shownIndex];
                """.trimIndent(),
            )
            .replace(Regex("\\bconst\\b"), "var")
        val context = Context.enter()
        context.languageVersion = Context.VERSION_ES6
        var fixture = ""
        return try {
            val scope = context.initStandardObjects()
            fixture = """
                    const translation = "La pluie";
                    const text = { data: "Thus was a La pluie and stormy night.", order: 0 };
                    const note = { data: "[1]", order: 19 };
                    const following = { data: " after", order: note.order + note.data.length };
                    const bounds = [[text, [0, ${sentence.text.length}]]];
                    const edits = {
                      get: function() {
                        return [
                          { from: 0, to: 2, text: "Thus" },
                          { from: 9, to: 13, text: translation }
                        ];
                      }
                    };
                    function shown(node, at) {
                      $shownBody
                    }
                    function absolute(node, offset) { return node.order + offset; }
                    const Range = { END_TO_START: 1, START_TO_END: 2 };
                    function FakeRange(startNode, startOffset, endNode, endOffset) {
                      this.startNode = startNode;
                      this.startOffset = startOffset;
                      this.endNode = endNode;
                      this.endOffset = endOffset;
                    }
                    FakeRange.prototype.setStart = function(node, offset) {
                      this.startNode = node;
                      this.startOffset = offset;
                    };
                    FakeRange.prototype.setEnd = function(node, offset) {
                      this.endNode = node;
                      this.endOffset = offset;
                    };
                    FakeRange.prototype.compareBoundaryPoints = function(how, other) {
                      if (how === Range.END_TO_START) {
                        return absolute(this.endNode, this.endOffset) - absolute(other.startNode, other.startOffset);
                      }
                      return absolute(this.startNode, this.startOffset) - absolute(other.endNode, other.endOffset);
                    };
                    const selectedRange = new FakeRange(__NODE__, __START__, __NODE__, __END__);
                    const selection = { rangeCount: 1, isCollapsed: false, getRangeAt() { return selectedRange; } };
                    const document = { createRange() { return new FakeRange(null, 0, null, 0); } };
                    (function() {
                      $rangeLogic
                    })();
                """.trimIndent()
                .replace("__NODE__", node)
                .replace("__START__", start.toString())
                .replace("__END__", end.toString())
            val result = context.evaluateString(
                scope,
                fixture,
                "PageSwapsTest.js",
                1,
                null,
            )
            Context.toBoolean(result)
        } catch (e: RhinoException) {
            throw AssertionError(
                "${e.message}: ${e.lineSource() ?: e.lineNumber().let { line -> fixture.lines().getOrNull(line - 1) }}",
                e,
            )
        } finally {
            Context.exit()
        }
    }

    private companion object {
        const val TRANSLATION_NODE = "text"
        const val NOTE_REFERENCE_NODE = "note"
        const val FOLLOWING_TEXT_NODE = "following"
    }

    @Test
    fun `another document's page answers nothing`() {
        // Readium reuses web views between chapters, so every call names
        // the document it was meant for and a different one must not
        // answer for it.
        assertTrue(script().contains("markedAt("))
        assertTrue(body().contains("if (!here(url)) return null;"))
    }

    @Test
    fun `the script carries the point it was asked about`() {
        val js = script(0.25f, 0.75f)
        assertTrue(js.contains("0.25"))
        assertTrue(js.contains("0.75"))
    }

    @Test
    fun `the point is a fraction of the viewport, not a pixel count`() {
        // A fixed-layout page is drawn at whatever scale fits it, so a
        // fraction of the viewport is the same fraction however the page
        // is scaled; pixels of one kind or the other are not.
        val js = script()
        assertTrue(js.contains("fx * VW"))
        assertTrue(js.contains("fy * VH"))
        assertTrue(js.contains("window.innerWidth"))
        assertTrue(js.contains("document.documentElement.clientWidth"))
    }

    @Test
    fun `the words are found again rather than read out of a painted mark`() {
        // The answer must be the same on a WebView that cannot paint the
        // mark at all, so the sentence is located as `mark` locates it
        // and the CSS Custom Highlight is never asked.
        val body = body()
        assertTrue(body.contains("locate(s.selector, s.before, s.text)"))
        assertTrue(body.contains("shownRange(at)"))
        assertFalse(body.contains("CSS.highlights"))
    }

    @Test
    fun `dictionary language follows edits intersecting the current selection`() {
        val js = PageSwaps.selectionIntersectsAppliedSwap(
            "https://book/EPUB/text/ch1.xhtml",
            sentence,
        )
        assertTrue(js.contains("window.getSelection()"))
        assertTrue(js.contains("edits.get(node)"))
        assertTrue(js.contains("range.compareBoundaryPoints(Range.END_TO_START, changed)"))
        assertTrue(js.contains("range.compareBoundaryPoints(Range.START_TO_END, changed)"))
        val body = js.substringAfter("selectionIntersectsAppliedSwap(url, s) {")
            .substringBefore("markedAt(url, s, fx, fy) {")
        assertTrue(
            body.contains("if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;"),
        )
        assertTrue(body.contains("if (!at) return null;"))
    }

    @Test
    fun `dictionary language follows whether selected words were translated`() {
        assertTrue(selectionIntersects(TRANSLATION_NODE, 12, 15))
        assertFalse(selectionIntersects(FOLLOWING_TEXT_NODE, 0, 4))
        assertFalse(selectionIntersects(NOTE_REFERENCE_NODE, 0, 3))
    }

    @Test
    fun `a rect with nothing in it is not a place a finger can land`() {
        assertTrue(body().contains("r.width > 0 && r.height > 0"))
    }

    @Test
    fun `a finger is allowed to miss by a few pixels`() {
        val body = body()
        assertTrue(body.contains("const SLOP = "))
        assertTrue(body.contains("r.left - SLOP"))
        assertTrue(body.contains("r.bottom + SLOP"))
    }
}
