package com.chmouel.liseur.companion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMapTest {
    private val files = (1..8).map { "ch$it.xhtml" }

    private fun toc(vararg hrefs: String) = hrefs.map { TocNode(it) }

    @Test
    fun `chapter five sees chapters one to four and nothing after`() {
        val map = ChapterMap.of(files, toc(*files.toTypedArray()))
        assertEquals(8, map.chapterCount)
        val scope = AskingScope(map.resourceRange(4).lo, map.chapterCount)
        assertEquals(4, scope.lo)
        (0..3).forEach { assertTrue(Eligibility.allows(scope, map.resourceRange(it))) }
        (4..7).forEach { assertFalse(Eligibility.allows(scope, map.resourceRange(it))) }
    }

    @Test
    fun `going back narrows what is eligible`() {
        val map = ChapterMap.of(files, toc(*files.toTypedArray()))
        val scope = AskingScope(map.resourceRange(1).lo, map.chapterCount)
        assertTrue(Eligibility.allows(scope, map.resourceRange(0)))
        (1..7).forEach { assertFalse(Eligibility.allows(scope, map.resourceRange(it))) }
    }

    @Test
    fun `the first chapter has nothing before it`() {
        val map = ChapterMap.of(files, toc(*files.toTypedArray()))
        assertFalse(AskingScope(map.resourceRange(0).lo, map.chapterCount).hasEarlier)
    }

    @Test
    fun `a chapter listed out of reading order is not taken to start before the reader`() {
        val book = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml", "ch4.xhtml", "ch5.xhtml")
        val map = ChapterMap.of(book, toc("ch1.xhtml", "ch5.xhtml", "ch3.xhtml"))
        val lo = map.resourceRange(3).lo
        assertEquals(2, lo)
        val reader = ReadPoint(3, 10)
        // Chapter 2 in the contents is ch5, after the reader, though its number is below lo.
        assertEquals(listOf(0), (0 until lo).filter { map.startsBefore(it, reader, emptyMap()) })
    }

    @Test
    fun `a chapter starting in the asking file is before only from a located anchor before the reader`() {
        val map = ChapterMap.of(files, toc("ch1.xhtml", "ch2.xhtml", "ch2.xhtml#c3", "ch3.xhtml"))
        val anchors = mapOf("c3" to 20)
        assertTrue(map.startsBefore(1, ReadPoint(1, 5), anchors))
        assertTrue(map.startsBefore(2, ReadPoint(1, 30), anchors))
        assertFalse(map.startsBefore(2, ReadPoint(1, 20), anchors))
        assertFalse(map.startsBefore(2, ReadPoint(1, 10), anchors))
        // An anchor not found, or a reader whose place in the file is unknown.
        assertFalse(map.startsBefore(2, ReadPoint(1, 30), emptyMap()))
        assertFalse(map.startsBefore(2, ReadPoint(1, 0), anchors))
        assertTrue(map.startsBefore(2, ReadPoint(2, 0), emptyMap()))
        assertFalse(map.startsBefore(1, null, anchors))
    }

    @Test
    fun `a chapter starting in a file listed twice is placed at its last listing`() {
        val map = ChapterMap.of(listOf("a.xhtml", "notes.xhtml", "b.xhtml", "notes.xhtml"), toc("a.xhtml", "notes.xhtml", "b.xhtml"))
        assertEquals(ChapterStart(1, null), map.startOf(1))
        assertFalse(map.startsBefore(1, ReadPoint(2, 5), emptyMap()))
        assertTrue(map.startsBefore(2, ReadPoint(3, 0), emptyMap()))
    }

    @Test
    fun `front matter before the first entry joins the first chapter`() {
        val map = ChapterMap.of(listOf("cover.xhtml") + files, toc(*files.toTypedArray()))
        assertEquals(ChapterRange(0, 0), map.resourceRange(0))
        assertEquals(ChapterRange(0, 0), map.resourceRange(1))
        assertEquals(ChapterRange(1, 1), map.resourceRange(2))
    }

    @Test
    fun `a book without contents is one chapter`() {
        val map = ChapterMap.of(files, emptyList())
        assertEquals(1, map.chapterCount)
        files.indices.forEach { assertEquals(ChapterRange(0, 0), map.resourceRange(it)) }
    }

    @Test
    fun `entries outside the reading order and duplicates are ignored`() {
        val map = ChapterMap.of(files.take(3), toc("ch1.xhtml", "ch1.xhtml", "notes.xhtml", "ch2.xhtml", "ch3.xhtml"))
        assertEquals(3, map.chapterCount)
        assertEquals(ChapterRange(2, 2), map.resourceRange(2))
    }

    @Test
    fun `untitled resources continue the chapter before them`() {
        val map = ChapterMap.of(files.take(4), toc("ch1.xhtml", "ch3.xhtml"))
        assertEquals(ChapterRange(0, 0), map.resourceRange(1))
        assertEquals(ChapterRange(1, 1), map.resourceRange(3))
    }

    @Test
    fun `nested entries never split a chapter, even in another file`() {
        // Chapter 5 is ch5a.xhtml and its Section 2 is ch5b.xhtml.
        val order = listOf("ch1.xhtml", "ch2.xhtml", "ch3.xhtml", "ch4.xhtml", "ch5a.xhtml", "ch5b.xhtml", "ch6.xhtml")
        val contents = listOf(
            TocNode("ch1.xhtml"), TocNode("ch2.xhtml"), TocNode("ch3.xhtml"), TocNode("ch4.xhtml"),
            TocNode("ch5a.xhtml", listOf(TocNode("ch5b.xhtml"))),
            TocNode("ch6.xhtml"),
        )
        val map = ChapterMap.of(order, contents)
        assertEquals(6, map.chapterCount)
        val scope = AskingScope(map.resourceRange(5).lo, map.chapterCount)
        assertEquals(4, scope.lo)
        assertFalse(Eligibility.allows(scope, map.resourceRange(4)))
        assertTrue(Eligibility.allows(scope, map.resourceRange(3)))
    }

    @Test
    fun `a lone wrapper is looked through and a part over several files opens up`() {
        val contents = listOf(
            TocNode(
                "ch1.xhtml",
                listOf(
                    TocNode("ch1.xhtml", listOf(TocNode("ch2.xhtml"), TocNode("ch3.xhtml"))),
                    TocNode("ch4.xhtml", listOf(TocNode("ch5.xhtml"))),
                ),
            ),
        )
        val map = ChapterMap.of(files.take(5), contents)
        assertEquals(4, map.chapterCount)
        assertEquals(ChapterRange(2, 2), map.resourceRange(2))
        // A single section in another file does not open its chapter up.
        assertEquals(ChapterRange(3, 3), map.resourceRange(4))
    }

    @Test
    fun `chapters nested under volumes are chapters, as in Gutenberg's Monte Cristo`() {
        val order = listOf("title.xhtml", "v1c1.xhtml", "c2.xhtml", "c3.xhtml", "v2.xhtml", "c4.xhtml", "c5.xhtml", "notes.xhtml")
        val contents = listOf(
            TocNode("title.xhtml#t", title = "THE COUNT OF MONTE CRISTO"),
            TocNode("title.xhtml#contents", title = "Contents"),
            TocNode(
                "v1c1.xhtml#v1",
                listOf(TocNode("v1c1.xhtml#c1", title = "Chapter 1"), TocNode("c2.xhtml#c2", title = "Chapter 2"), TocNode("c3.xhtml#c3", title = "Chapter 3")),
                title = "VOLUME ONE",
            ),
            TocNode("v2.xhtml#v2", listOf(TocNode("c4.xhtml#c4"), TocNode("c5.xhtml#c5")), title = "VOLUME TWO"),
            TocNode("notes.xhtml#n", title = "FOOTNOTES:"),
        )
        val map = ChapterMap.of(order, contents)
        assertEquals(10, map.chapterCount)
        assertEquals("Chapter 3", map.titleOf(5))
        assertEquals(null, map.titleOf(7))
        val anchors = mapOf("v1" to 0, "c1" to 2, "c2" to 0, "c3" to 0)
        val scope = AskingScope(map.range(3, 4, 6, anchors).lo, map.chapterCount)
        assertEquals(5, scope.lo)
        assertTrue(Eligibility.allows(scope, map.range(1, 5, 9, anchors)))
        assertTrue(Eligibility.allows(scope, map.range(2, 3, 9, anchors)))
        assertFalse(Eligibility.allows(scope, map.range(3, 1, 2, anchors)))
        assertFalse(Eligibility.allows(scope, map.resourceRange(5)))
    }

    @Test
    fun `sections inside a chapter's own file never split it`() {
        val contents = listOf(
            TocNode("ch1.xhtml"),
            TocNode("ch2.xhtml", listOf(TocNode("ch2.xhtml#s1"), TocNode("ch2.xhtml#s2"))),
            TocNode("ch3.xhtml"),
        )
        val map = ChapterMap.of(files.take(3), contents)
        assertEquals(3, map.chapterCount)
        assertEquals(ChapterRange(1, 1), map.range(1, 5, 9, mapOf("s1" to 2, "s2" to 6)))
    }

    @Test
    fun `chapters inside one file split at their located anchors`() {
        val map = ChapterMap.of(listOf("all.xhtml"), toc("all.xhtml#c1", "all.xhtml#c2", "all.xhtml#c3"))
        val anchors = mapOf("c2" to 10, "c3" to 20)
        assertEquals(setOf("c2", "c3"), map.anchorsIn(0))
        assertEquals(ChapterRange(0, 0), map.range(0, 3, 4, anchors))
        assertEquals(ChapterRange(1, 1), map.range(0, 10, 12, anchors))
        assertEquals(ChapterRange(2, 2), map.range(0, 25, 25, anchors))
        // An element holding the anchor, or reaching past it, belongs to both sides.
        assertEquals(ChapterRange(0, 1), map.range(0, 8, 15, anchors))
        assertEquals(ChapterRange(0, 2), map.range(0, 5, 30, anchors))
        // Text of unknown place takes the whole file.
        assertEquals(ChapterRange(0, 2), map.range(0, null, null, anchors))
    }

    @Test
    fun `an anchor that cannot be found widens its whole file but not the next`() {
        val map = ChapterMap.of(listOf("a.xhtml", "b.xhtml", "c.xhtml"), toc("a.xhtml", "b.xhtml#x", "c.xhtml"))
        assertEquals(ChapterRange(0, 0), map.resourceRange(0))
        assertEquals(ChapterRange(0, 1), map.range(1, 2, 3, emptyMap()))
        assertEquals(ChapterRange(2, 2), map.resourceRange(2))
        // A lookup from b sees nothing of b, wherever its anchor is.
        val scope = AskingScope(map.range(1, 50, 50, emptyMap()).lo, map.chapterCount)
        assertEquals(0, scope.lo)
    }

    @Test
    fun `a file listed twice takes every place it is listed`() {
        val order = listOf("ch1.xhtml", "map.xhtml", "ch2.xhtml", "map.xhtml", "ch3.xhtml")
        val map = ChapterMap.of(order, toc("ch1.xhtml", "ch2.xhtml", "ch3.xhtml"))
        assertEquals(ChapterRange(0, 1), map.range(1, 1, 1, emptyMap()))
        assertEquals(ChapterRange(0, 1), map.range(3, 1, 1, emptyMap()))
        assertEquals(listOf(1, 3), map.occurrencesOf(3))
        assertFalse(Eligibility.allows(AskingScope(1, 3), map.resourceRange(1)))
    }

    @Test
    fun `contents out of reading order never make later text eligible`() {
        val map = ChapterMap.of(files.take(6), toc("ch1.xhtml", "ch5.xhtml", "ch3.xhtml"))
        files.take(6).indices.forEach { asked ->
            val scope = AskingScope(map.resourceRange(asked).lo, map.chapterCount)
            (asked until 6).forEach { later -> assertFalse(Eligibility.allows(scope, map.resourceRange(later))) }
        }
    }

    @Test
    fun `nothing at or after the asking place is ever eligible`() {
        // Every place in a book with anchors, missing anchors and a repeated file.
        val order = listOf("a.xhtml", "b.xhtml", "c.xhtml", "b.xhtml", "d.xhtml")
        val map = ChapterMap.of(order, toc("a.xhtml", "b.xhtml#one", "c.xhtml#two", "c.xhtml#three", "d.xhtml"))
        val anchors = mapOf("two" to 5)
        val places = order.indices.flatMap { r -> (0..10).map { r to it } }
        places.forEach { (r, at) ->
            val scope = AskingScope(map.range(r, at, at, anchors).lo, map.chapterCount)
            places.filter { (r2, at2) -> r2 > r || (r2 == r && at2 >= at) }.forEach { (r2, at2) ->
                assertFalse("$r:$at sees $r2:$at2", Eligibility.allows(scope, map.range(r2, at2, at2, anchors)))
            }
        }
    }

    @Test
    fun `the walk can stop once nothing further can be eligible`() {
        val map = ChapterMap.of(files, toc(*files.toTypedArray()))
        assertEquals(0, map.floorFrom(0))
        assertEquals(4, map.floorFrom(4))
    }

    @Test
    fun `the catalog places passages by their elements`() = runBlocking {
        val html = """
            <html><body>
            <h1 id="c1">One</h1><p>Alice met Bob.</p>
            <h1 id="c2">Two</h1><p>Bob left.</p>
            <div><p>Carol</p><h1 id="c3">Three</h1></div>
            <p>Spoiler.</p>
            </body></html>
        """.trimIndent()
        val map = ChapterMap.of(listOf("all.xhtml"), toc("all.xhtml#c1", "all.xhtml#c2", "all.xhtml#c3"))
        val builder = PassageCatalogBuilder<Int>(map, listOf("all.xhtml")) { html }
        val outline = ResourceOutline(html)
        val elements = listOf("#c1", "body > p:nth-of-type(1)", "#c2", "body > p:nth-of-type(2)", "div", "body > p:nth-of-type(3)")
        val passages = elements.mapIndexed { i, selector ->
            assertTrue("missing $selector", outline.span(selector) != null)
            builder.add(SourceElement("all.xhtml", selector, "text $i", i))
        }.flatten()
        assertEquals(
            listOf(ChapterRange(0, 0), ChapterRange(0, 0), ChapterRange(1, 1), ChapterRange(1, 1), ChapterRange(1, 2), ChapterRange(2, 2)),
            passages.map { it.range },
        )
        assertEquals(ChapterRange(1, 1), builder.rangeOf(0, "body > p:nth-of-type(2)"))
        assertEquals(ChapterRange(0, 2), builder.rangeOf(0, "no-such-thing"))
    }

    @Test
    fun `elements from files outside the reading order are withheld`() = runBlocking {
        val map = ChapterMap.of(listOf("a.xhtml"), toc("a.xhtml"))
        val builder = PassageCatalogBuilder<Int>(map, listOf("a.xhtml")) { null }
        assertTrue(builder.add(SourceElement("stray.xhtml", null, "text", 0)).isEmpty())
        assertEquals("r0.0", builder.add(SourceElement("a.xhtml", null, "text", 0)).single().ref)
    }

    @Test
    fun `long elements are cut without losing text`() {
        val sentence = "The quick brown fox jumps over the lazy dog. "
        val text = sentence.repeat(100)
        val parts = PassageText.split(text)
        assertTrue(parts.all { it.length <= PassageText.MAX_LENGTH })
        assertEquals(PassageText.fold(text), PassageText.fold(parts.joinToString(" ")))
        val giant = "x".repeat(3000) + "\uD83D\uDE00" + "y".repeat(3000)
        val cut = PassageText.split(giant)
        assertEquals(giant, cut.joinToString(""))
        assertTrue(cut.none { it.first().isLowSurrogate() })
    }

    @Test
    fun `names are folded and matched as whole words`() {
        assertTrue(PassageText.mentions("Then Élodie said hello.", "elodie"))
        assertFalse(PassageText.mentions("Then Élodiette said hello.", "Élodie"))
        assertEquals("Mr Darcy", PassageText.name("  “Mr  Darcy,” "))
        assertEquals(null, PassageText.name("one two three four five six seven"))
    }

    @Test
    fun `names in scripts without spaces are found inside the text`() {
        assertTrue(PassageText.mentions("林黛玉听了，便说道。", "林黛玉"))
        assertTrue(PassageText.mentions("ハリーは笑った。", "ハリー"))
        assertFalse(PassageText.mentions("贾宝玉听了。", "林黛玉"))
        assertFalse(PassageText.mentions("Then Harrison said hello.", "Harry"))
    }
}
