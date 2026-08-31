package com.chmouel.liseur.reader.progress

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        assertEquals(PageNumbering.POSITION, resolver.prompt.numbering)
        assertEquals("1", resolver.prompt.firstLabel)
        assertEquals("4", resolver.prompt.lastLabel)
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

        assertEquals(PageNumbering.PRINTED, resolver.prompt.numbering)
        assertEquals("iv", resolver.prompt.firstLabel)
        assertEquals("3", resolver.prompt.lastLabel)

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
