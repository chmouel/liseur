package com.chmouel.liseur.reader.progress

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookPositionsTest {

    private fun locator(
        href: String,
        progression: Double?,
        position: Int?,
        totalProgression: Double? = null,
    ): Locator {
        val absoluteHref = "https://example.com/$href"
        val locations = JSONObject()
        progression?.let { locations.put("progression", it) }
        position?.let { locations.put("position", it) }
        totalProgression?.let { locations.put("totalProgression", it) }
        return requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", absoluteHref)
                    .put("type", "application/xhtml+xml")
                    .put("locations", locations),
            ),
        )
    }

    private fun positions(
        resources: List<List<Locator>>,
        chapters: List<BookChapter> = emptyList(),
    ) = BookPositions(
        locators = resources.flatten(),
        chapters = chapters,
        chapterIndexByResource = emptyMap(),
        positionsByResource = resources,
    )

    @Test
    fun `interpolates fractional pages between stable positions`() {
        val first = listOf(
            locator("chapter-1.xhtml", 0.0, 1),
            locator("chapter-1.xhtml", 0.5, 2),
            locator("chapter-1.xhtml", 0.9, 3),
        )
        val second = listOf(
            locator("chapter-2.xhtml", 0.0, 4),
            locator("chapter-2.xhtml", 0.8, 5),
        )
        val book = positions(listOf(first, second))

        val progress = book.resolve(
            locator("chapter-1.xhtml", 0.25, 1, totalProgression = 0.95),
        )
        assertNotNull(progress)
        val resolved = requireNotNull(progress)

        assertEquals(1.5, resolved.coordinate, 0.0001)
        assertEquals(0.125, resolved.progression, 0.0001)
        // The navigator's unrelated layout-sensitive value is ignored.
        assertEquals(2, resolved.position)
    }

    @Test
    fun `pages sharing one integer position still advance fractionally`() {
        val book = positions(
            listOf(
                listOf(
                    locator("chapter.xhtml", 0.0, 1),
                    locator("chapter.xhtml", 0.5, 2),
                    locator("chapter.xhtml", 1.0, 3),
                ),
            ),
        )

        val first = book.resolve(locator("chapter.xhtml", 0.1, 1))!!
        val second = book.resolve(locator("chapter.xhtml", 0.4, 1))!!

        assertEquals(0.6, second.coordinate - first.coordinate, 0.0001)
    }

    @Test
    fun `the integer anchor selects the right duplicate href`() {
        val book = positions(
            listOf(
                listOf(
                    locator("shared.xhtml", 0.0, 1),
                    locator("shared.xhtml", 1.0, 2),
                ),
                listOf(
                    locator("shared.xhtml", 0.0, 3),
                    locator("shared.xhtml", 1.0, 4),
                ),
            ),
        )

        assertEquals(
            3.5,
            book.resolve(locator("shared.xhtml", 0.5, 3))!!.coordinate,
            0.0001,
        )
    }

    @Test
    fun `missing resource falls back to a finite stable position`() {
        val book = positions(
            listOf(
                listOf(
                    locator("known.xhtml", 0.0, 1),
                    locator("known.xhtml", 1.0, 2),
                ),
            ),
        )

        val progress = book.resolve(locator("missing.xhtml", 0.5, 2))

        assertEquals(2.0, progress!!.coordinate, 0.0001)
        assertEquals(1.0, progress.progression, 0.0001)
    }

    @Test
    fun `single position book has safe endpoints`() {
        val only = locator("short.xhtml", 0.0, 1)
        val book = positions(listOf(listOf(only)))

        assertEquals(0.0, book.resolve(locator("short.xhtml", 0.8, 1))!!.progression, 0.0)
        assertEquals(1, book.positionAtProgression(0f))
        assertEquals(1, book.positionAtProgression(1f))
    }

    @Test
    fun `progression inverse uses stable endpoints and nearest position`() {
        val resource = (1..5).map { position ->
            locator("book.xhtml", (position - 1) / 4.0, position)
        }
        val book = positions(listOf(resource))

        assertEquals(1, book.positionAtProgression(0f))
        assertEquals(3, book.positionAtProgression(0.5f))
        assertEquals(5, book.positionAtProgression(1f))
    }

    @Test
    fun `conservative fallback never starts after the target`() {
        val resource = (1..5).map { position ->
            locator("book.xhtml", (position - 1) / 4.0, position)
        }
        val book = positions(listOf(resource))

        assertEquals(2, book.locatorAtOrBeforeProgression(0.49)?.locations?.position)
        assertEquals(3, book.locatorAtOrBeforeProgression(0.50)?.locations?.position)
        assertEquals(5, book.locatorAtOrBeforeProgression(1.0)?.locations?.position)
    }

    @Test
    fun `approximate resume within two synthetic positions does not return chapter start`() {
        val book = positions(
            listOf(
                listOf(
                    locator("chapter.xhtml", 0.0, 1),
                    locator("chapter.xhtml", 0.5, 2),
                ),
            ),
        )
        val target = book.locatorAtOrBeforeProgression(0.85)!!
        assertEquals(0.425, target.locations.progression!!, 0.0001)
        assertEquals(0.85, book.resolve(target)!!.progression, 0.0001)
        assertEquals(0.85, target.locations.totalProgression!!, 0.0001)
    }

    @Test
    fun `approximate resume interpolates within the chosen resource and crosses boundaries`() {
        val book = positions(
            listOf(
                listOf(
                    locator("one.xhtml", 0.0, 1),
                    locator("one.xhtml", 0.5, 2),
                ),
                listOf(
                    locator("two.xhtml", 0.0, 3),
                    locator("two.xhtml", 0.6, 4),
                ),
            ),
        )
        for (progression in listOf(0.0, 0.2, 0.5, 2.0 / 3, 0.85, 1.0)) {
            val target = book.locatorAtOrBeforeProgression(progression)!!
            assertEquals(progression, book.resolve(target)!!.progression, 0.0001)
        }
        assertEquals(
            "https://example.com/two.xhtml",
            book.locatorAtOrBeforeProgression(2.0 / 3)!!.href.toString(),
        )
        assertEquals(0.0, book.locatorAtOrBeforeProgression(2.0 / 3)!!.locations.progression!!, 0.0)
    }

    @Test
    fun `approximate resume retains duplicate resource disambiguation`() {
        val book = positions(
            listOf(
                listOf(locator("same.xhtml", 0.0, 1), locator("same.xhtml", 0.5, 2)),
                listOf(locator("same.xhtml", 0.0, 3), locator("same.xhtml", 0.5, 4)),
            ),
        )
        val target = book.locatorAtOrBeforeProgression(0.85)!!
        assertEquals(3, target.locations.position)
        assertEquals(0.85, book.resolve(target)!!.progression, 0.0001)
    }

    @Test
    fun `a single position still carries a usable resource progression for resume`() {
        val book = positions(listOf(listOf(locator("short.xhtml", 0.0, 1))))
        assertEquals(0.85, book.locatorAtOrBeforeProgression(0.85)!!.locations.progression!!, 0.0)
    }

    /**
     * The reason a scrolled book has to measure its own distance before
     * saving a place. Readium's `findFirstVisibleLocator` names a place
     * and nothing else, and a place with no distance is the top of its
     * chapter to everything downstream — the footer, the pace estimator
     * and the percentage every server syncs.
     */
    @Test
    fun `a locator with no distance resolves to the start of its resource`() {
        val first = (1..4).map { position ->
            locator("chapter-1.xhtml", (position - 1) / 3.0, position)
        }
        val second = (5..8).map { position ->
            locator("chapter-2.xhtml", (position - 5) / 3.0, position)
        }
        val book = positions(listOf(first, second))

        val placeOnly = book.resolve(locator("chapter-2.xhtml", null, null))!!
        assertEquals(5.0, placeOnly.coordinate, 0.0)

        val measured = book.resolve(locator("chapter-2.xhtml", 2.0 / 3.0, null))!!
        assertEquals(7.0, measured.coordinate, 0.0)
    }
}
