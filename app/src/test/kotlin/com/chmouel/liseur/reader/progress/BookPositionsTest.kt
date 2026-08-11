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
}
