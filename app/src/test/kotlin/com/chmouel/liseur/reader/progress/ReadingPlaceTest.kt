package com.chmouel.liseur.reader.progress

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ReadingPlaceTest {

    @Test
    fun `two screens inside one readium position are different pages`() {
        val first = anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)
        val second = anchored("p:nth-of-type(9)", "afterwards", position = 12, progression = 0.34)

        assertFalse(samePage(first, second))
    }

    @Test
    fun `the same screen looked at twice is the same page`() {
        val bookmarked = anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)
        val returned = anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)

        assertTrue(samePage(bookmarked, returned))
    }

    @Test
    fun `an anchor outlives the progression a reflow rewrites`() {
        val before = anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)
        val afterReflow = anchored(
            "p:nth-of-type(4)",
            "Marseille",
            position = 13,
            progression = 0.55,
        )

        assertTrue(samePage(before, afterReflow))
    }

    @Test
    fun `an anchor in another resource is another page`() {
        val here = anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)
        val elsewhere = Locator.fromJSON(
            JSONObject(here.toJSON().toString()).put("href", "/other.xhtml"),
        )

        assertFalse(samePage(here, elsewhere))
    }

    @Test
    fun `without anchors the progression decides`() {
        val here = plain(position = 12, progression = 0.21)
        val nearly = plain(position = 12, progression = 0.21 + 1e-9)
        val further = plain(position = 12, progression = 0.34)

        assertTrue(samePage(here, nearly))
        assertFalse(samePage(here, further))
    }

    @Test
    fun `contradicting positions are different pages whatever the progression`() {
        assertFalse(
            samePage(plain(position = 12, progression = 0.21), plain(position = 13, progression = 0.21)),
        )
    }

    @Test
    fun `an anchor on one side alone falls back to the coarser test`() {
        val bookmarked = anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)
        val uncaptured = plain(position = 12, progression = 0.21)

        assertTrue(samePage(bookmarked, uncaptured))
    }

    @Test
    fun `a locator naming nothing within its resource cannot place a page`() {
        val bare = requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "/chapter.xhtml")
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("totalProgression", 0.4)),
            ),
        )

        assertFalse(bare.namesItsPage())
        assertTrue(plain(position = 12, progression = 0.21).namesItsPage())
        assertTrue(
            anchored("p:nth-of-type(4)", "Marseille", position = 12, progression = 0.21)
                .namesItsPage(),
        )
    }

    @Test
    fun `nothing is nowhere`() {
        assertFalse(samePage(null, plain(position = 1, progression = 0.0)))
        assertFalse(samePage(plain(position = 1, progression = 0.0), null))
    }

    private fun plain(position: Int, progression: Double): Locator = requireNotNull(
        Locator.fromJSON(
            JSONObject()
                .put("href", "/chapter.xhtml")
                .put("type", "application/xhtml+xml")
                .put(
                    "locations",
                    JSONObject()
                        .put("progression", progression)
                        .put("position", position)
                        .put("totalProgression", 0.4),
                ),
        ),
    )

    private fun anchored(
        selector: String,
        word: String,
        position: Int,
        progression: Double,
    ): Locator = ExactLocatorAnchor.mark(
        plain(position, progression),
        ViewportTextAnchor(
            cssSelector = selector,
            before = "the road to ",
            highlight = word,
            after = " lay open",
        ),
    )
}
