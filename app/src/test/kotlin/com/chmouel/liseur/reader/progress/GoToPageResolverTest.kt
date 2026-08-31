package com.chmouel.liseur.reader.progress

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class GoToPageResolverTest {

    @Test
    fun `synthetic positions accept only the pages the footer shows`() {
        val spine = spine("first.xhtml" to 2, "second.xhtml" to 2)
        val resolver = GoToPageResolver(emptyList(), spine.positions()) { null }

        val prompt = resolver.promptAt(3)
        assertEquals(PageNumbering.POSITION, prompt.numbering)
        assertEquals("1", prompt.firstLabel)
        assertEquals("4", prompt.lastLabel)
        assertEquals(3, resolver.resolve(" 3 ")?.locator?.locations?.position)
        assertNull(resolver.resolve("0"))
        assertNull(resolver.resolve("5"))
        assertNull(resolver.resolve("iii"))
    }

    @Test
    fun `printed pages keep their labels and reject a missing page`() {
        val pages = listOf(
            link("front.xhtml", "iv"),
            link("chapter.xhtml#page-1", "1"),
            link("chapter.xhtml#page-3", "3"),
        )
        val spine = spine("front.xhtml" to 1, "chapter.xhtml" to 3)
        val resolver = GoToPageResolver(pages, spine.positions(), spine::locatorFromLink)

        val prompt = resolver.promptAt(1)
        assertEquals(PageNumbering.PRINTED, prompt.numbering)
        assertEquals("iv", prompt.firstLabel)
        assertEquals("3", prompt.lastLabel)

        val destination = resolver.resolve("3")
        // Readium moves the fragment out of the href, which is what lets
        // BookPositions match the page against a resource of the spine.
        assertEquals("chapter.xhtml", destination?.locator?.href?.toString())
        assertEquals(listOf("page-3"), destination?.locator?.locations?.fragments)
        assertNull(resolver.resolve("2"))
    }

    @Test
    fun `a printed page outside the reading order resolves to nothing`() {
        val spine = spine("first.xhtml" to 2, "second.xhtml" to 2)
        val pages = listOf(link("missing.xhtml#page-9", "9"))
        val resolver = GoToPageResolver(pages, spine.positions(), spine::locatorFromLink)

        assertNull(resolver.resolve("9"))
    }

    @Test
    fun `printed page previews the chapter at its resolved position`() {
        val spine = spine("first.xhtml" to 2, "second.xhtml" to 2)
        val positions = spine.positions(
            chapters = listOf(
                BookChapter("First", 1, 2),
                BookChapter("Second", 3, 4),
            ),
        )
        val page = link("second.xhtml#page-12", "12")
        val resolver = GoToPageResolver(listOf(page), positions, spine::locatorFromLink)

        assertEquals("Second", resolver.resolve("12")?.chapterTitle)
    }

    @Test
    fun `the first repeated printed label wins`() {
        val spine = spine("volume-1.xhtml" to 1, "volume-2.xhtml" to 1)
        val first = link("volume-1.xhtml", "1")
        val second = link("volume-2.xhtml", "1")
        val resolver = GoToPageResolver(listOf(first, second), spine.positions(), spine::locatorFromLink)

        assertEquals("volume-1.xhtml", resolver.resolve("1")?.locator?.href?.toString())
    }

    @Test
    fun `the dialog starts on the page the reader is on`() {
        val spine = spine("first.xhtml" to 2, "second.xhtml" to 2)
        val resolver = GoToPageResolver(emptyList(), spine.positions()) { null }

        assertEquals("3", resolver.promptAt(3).currentLabel)
        // A position off the end of the book is still a page to offer.
        assertEquals("4", resolver.promptAt(9).currentLabel)
        assertEquals("1", resolver.promptAt(0).currentLabel)
    }

    @Test
    fun `the dialog starts on the printed page the reader is on`() {
        val spine = spine("front.xhtml" to 2, "chapter.xhtml" to 2, "end.xhtml" to 1)
        val pages = listOf(
            link("chapter.xhtml", "1"),
            link("end.xhtml", "2"),
        )
        val resolver = GoToPageResolver(pages, spine.positions(), spine::locatorFromLink)

        // Before the book prints a number at all, the first one it does.
        assertEquals("1", resolver.promptAt(1).currentLabel)
        assertEquals("1", resolver.promptAt(3).currentLabel)
        assertEquals("1", resolver.promptAt(4).currentLabel)
        assertEquals("2", resolver.promptAt(5).currentLabel)
    }

    @Test
    fun `printed pages sharing a position keep the earliest label`() {
        val spine = spine("front.xhtml" to 1, "chapter.xhtml" to 2)
        val pages = listOf(
            link("chapter.xhtml#page-7", "7"),
            link("chapter.xhtml#page-8", "8"),
        )
        val resolver = GoToPageResolver(pages, spine.positions(), spine::locatorFromLink)

        assertEquals("7", resolver.promptAt(2).currentLabel)
    }

    @Test
    fun `a printed page the reading order does not carry is not offered`() {
        val spine = spine("first.xhtml" to 2)
        val pages = listOf(link("missing.xhtml", "9"))
        val resolver = GoToPageResolver(pages, spine.positions(), spine::locatorFromLink)

        assertEquals("9", resolver.promptAt(2).currentLabel)
    }

    @Test
    fun `a percentage lands where the footer starts calling it that`() {
        val spine = spine("first.xhtml" to 2, "second.xhtml" to 3)
        val positions = spine.positions()

        assertEquals(1, goToPercent("0", positions)?.locator?.locations?.position)
        assertEquals(5, goToPercent("100", positions)?.locator?.locations?.position)
        // Four steps between five positions: 75% is exactly the fourth.
        assertEquals(4, goToPercent(" 75 ", positions)?.locator?.locations?.position)
        // 80% falls between the fourth and the fifth, and the fourth is
        // still 75%, so the fifth is the first page that reads as 80.
        assertEquals(5, goToPercent("80", positions)?.locator?.locations?.position)
    }

    @Test
    fun `the percentage the footer shows leads back to the footer's own page`() {
        val positions = spine("long.xhtml" to 692).positions()

        // Every page of a long book, typed as the percentage printed
        // beside it, comes back reading as that same percentage.
        (1..692).forEach { page ->
            val percent = ((page - 1) * 100) / 691
            val landed = goToPercent(percent.toString(), positions)
                ?.locator?.locations?.position
                ?: error("page $page was refused")
            assertEquals(percent, ((landed - 1) * 100) / 691)
            // Confirming the prefilled answer never overshoots.
            assertTrue("page $page moved forward to $landed", landed <= page)
        }
    }

    @Test
    fun `a percentage previews the chapter it lands in`() {
        val spine = spine("first.xhtml" to 2, "second.xhtml" to 2)
        val positions = spine.positions(
            chapters = listOf(
                BookChapter("First", 1, 2),
                BookChapter("Second", 3, 4),
            ),
        )

        assertEquals("First", goToPercent("0", positions)?.chapterTitle)
        assertEquals("Second", goToPercent("100", positions)?.chapterTitle)
    }

    @Test
    fun `only a whole percentage in range is accepted`() {
        val positions = spine("first.xhtml" to 4).positions()

        assertNull(goToPercent("", positions))
        assertNull(goToPercent("-1", positions))
        assertNull(goToPercent("101", positions))
        assertNull(goToPercent("37.5", positions))
        assertNull(goToPercent("half", positions))
    }

    @Test
    fun `a one position book takes any percentage to its only page`() {
        val positions = spine("only.xhtml" to 1).positions()

        assertEquals(1, goToPercent("50", positions)?.locator?.locations?.position)
    }

    private fun spine(vararg resources: Pair<String, Int>) = Spine(resources.toList())

    /** A reading order of resources, each covering a number of positions. */
    private class Spine(private val resources: List<Pair<String, Int>>) {

        /** Readium's positions, grouped by resource as the real service groups them. */
        fun positions(chapters: List<BookChapter> = emptyList()): BookPositions {
            var position = 0
            val byResource = resources.map { (href, count) ->
                (0 until count).map { index ->
                    position += 1
                    locator(
                        href = href,
                        position = position,
                        progression = index.toDouble() / count,
                    )
                }
            }
            return BookPositions(
                locators = byResource.flatten(),
                chapters = chapters,
                chapterIndexByResource = emptyMap(),
                positionsByResource = byResource,
            )
        }

        /**
         * What Readium's `Publication.locatorFromLink` hands back: the fragment
         * moves out of the href into the locations, no synthetic position is
         * attached, and only a fragment-less link gets a progression. A link
         * pointing outside the reading order gets nothing.
         */
        fun locatorFromLink(link: Link): Locator? {
            val href = link.url().toString()
            val resource = href.substringBefore('#')
            if (resources.none { it.first == resource }) return null
            val fragment = href.substringAfter('#', "").takeIf(String::isNotEmpty)
            return locator(
                href = resource,
                fragment = fragment,
                progression = 0.0.takeIf { fragment == null },
            )
        }
    }

    private companion object {

        fun link(href: String, title: String): Link = requireNotNull(
            Link.fromJSON(JSONObject().put("href", href).put("title", title)),
        )

        fun locator(
            href: String,
            position: Int? = null,
            progression: Double? = null,
            fragment: String? = null,
        ): Locator {
            val locations = JSONObject()
            position?.let { locations.put("position", it) }
            progression?.let { locations.put("progression", it) }
            fragment?.let { locations.put("fragments", JSONArray().put(it)) }
            return requireNotNull(
                Locator.fromJSON(
                    JSONObject()
                        .put("href", href)
                        .put("type", "application/xhtml+xml")
                        .put("locations", locations),
                ),
            )
        }
    }
}
