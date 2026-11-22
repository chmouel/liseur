package com.chmouel.liseur.hardcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsHeuristicsTest {
    private val english =
        "When Rosemary Harper joins the crew of the Wayfarer, she isn't expecting much. The Wayfarer, a " +
            "patched-up ship that's seen better days, offers her everything she could possibly want: a small, " +
            "quiet spot to call home for a while, adventure in far-off corners of the galaxy, and distance from her past."
    private val french =
        "Rosemary, jeune humaine inexpérimentée, fuit sa famille de richissimes escrocs. Elle est engagée comme " +
            "greffière à bord du Voyageur, un vaisseau qui creuse des tunnels dans l'espace, où elle apprend à " +
            "vivre et à travailler avec des représentants de différentes espèces de la galaxie."

    @Test
    fun `common words tell english from french`() {
        assertEquals("en", TextLanguage.guess(english))
        assertEquals("fr", TextLanguage.guess(french))
        assertEquals("fr", TextLanguage.guess("<p>$french</p>"))
    }

    @Test
    fun `scripts decide russian and chinese`() {
        assertEquals("ru", TextLanguage.guess("Розмари присоединяется к экипажу корабля и узнаёт много нового о галактике."))
        assertEquals("zh", TextLanguage.guess("罗斯玛丽加入了旅行者号的船员队伍，她并没有抱太大的期望，但这艘船给了她一切。"))
    }

    @Test
    fun `short or unclear text is not guessed`() {
        assertNull(TextLanguage.guess("Wayfarers"))
        assertNull(TextLanguage.guess(null))
    }

    @Test
    fun `language tags reduce to two letters`() {
        assertEquals("fr", TextLanguage.codeOf("fr-FR"))
        assertEquals("fr", TextLanguage.codeOf("fre"))
        assertEquals("de", TextLanguage.codeOf("deu"))
        assertNull(TextLanguage.codeOf("tlh"))
    }

    @Test
    fun `a description in another language than the book differs`() {
        assertTrue(TextLanguage.differs(english, french, null))
        assertTrue(TextLanguage.differs(english, null, "fr"))
        assertFalse(TextLanguage.differs(english, null, "en-GB"))
        assertFalse(TextLanguage.differs(english, null, null))
        assertFalse(TextLanguage.differs("Short.", french, "fr"))
    }

    @Test
    fun `an english description is not offered over a french book`() {
        val local = LocalDetails(
            title = "L'espace d'un an",
            author = "Becky Chambers",
            description = french,
            seriesName = null,
            seriesIndex = null,
            language = "fr",
        )
        val remote = HardcoverEditionDetails(
            editionId = 1,
            title = "L'espace d'un an",
            author = "Becky Chambers",
            description = english,
            coverUrl = null,
            seriesName = null,
            seriesPosition = null,
        )
        assertTrue(proposeUpdates(local, remote).none { it.field == DetailField.DESCRIPTION })
    }

    @Test
    fun `the same picture at another size hashes close, another picture far`() {
        val gradient = IntArray(CoverSimilarity.WIDTH * CoverSimilarity.HEIGHT) { (it * 37) % 251 }
        val brighter = IntArray(gradient.size) { (gradient[it] * 1.1).toInt() + 3 }
        val other = IntArray(gradient.size) { 250 - gradient[it] }
        val base = CoverSimilarity.hash(gradient)
        assertTrue(CoverSimilarity.looksSame(base, CoverSimilarity.hash(brighter)))
        assertFalse(CoverSimilarity.looksSame(base, CoverSimilarity.hash(other)))
    }
}
