package com.chmouel.liseur.data.komga

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule here came from a real server refusing a real position, and
 * the numbers are taken from an actual `GET /positions` answer.
 */
class KomgaLocatorTest {

    private fun readium(
        href: String,
        progression: Double? = null,
        total: Double? = null,
    ): JSONObject {
        val locations = JSONObject()
        progression?.let { locations.put("progression", it) }
        total?.let { locations.put("totalProgression", it) }
        return JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .apply { if (locations.length() > 0) put("locations", locations) }
    }

    @Test
    fun `a leading slash is removed, because komga refuses one outright`() {
        val sent = KomgaLocator.toKomga(readium("/OEBPS/Text/ch1.xhtml", 0.5))!!

        assertEquals("OEBPS/Text/ch1.xhtml", sent.getString("href"))
    }

    @Test
    fun `a progression is always sent, even at the start of a resource`() {
        // Readium leaves it out at the very top of a chapter; Komga
        // rejects an epub locator that does not carry one.
        val sent = KomgaLocator.toKomga(readium("OEBPS/ch1.xhtml"))!!

        assertEquals(0.0, sent.getJSONObject("locations").getDouble("progression"), 0.0)
    }

    @Test
    fun `fragments are always present, as the schema demands`() {
        val sent = KomgaLocator.toKomga(readium("OEBPS/ch1.xhtml", 0.25))!!

        assertEquals(0, sent.getJSONObject("locations").getJSONArray("fragments").length())
    }

    @Test
    fun `a locator with nowhere to point cannot be sent`() {
        assertNull(KomgaLocator.toKomga(JSONObject().put("type", "application/xhtml+xml")))
        assertNull(KomgaLocator.toKomga(JSONObject().put("href", "/")))
    }

    @Test
    fun `a type is supplied when the stored locator has none`() {
        val sent = KomgaLocator.toKomga(JSONObject().put("href", "OEBPS/ch1.xhtml"))!!

        assertEquals("application/xhtml+xml", sent.getString("type"))
    }

    @Test
    fun `what comes back from komga is stored without its kobo baggage`() {
        val stored = KomgaLocator.toReadium(JSONObject(A_POSITION))!!

        assertEquals("OEBPS/Text/titlepage.xhtml", stored.getString("href"))
        assertFalse(stored.has("koboSpan"))
        assertEquals(
            0.000856898,
            stored.getJSONObject("locations").getDouble("totalProgression"),
            1e-9,
        )
    }

    @Test
    fun `a round trip keeps the reader where they were`() {
        val original = readium("OEBPS/Text/ch1.xhtml", progression = 0.75, total = 0.4)

        val back = KomgaLocator.toReadium(KomgaLocator.toKomga(original)!!)!!

        assertEquals("OEBPS/Text/ch1.xhtml", back.getString("href"))
        assertEquals(0.75, back.getJSONObject("locations").getDouble("progression"), 1e-9)
        assertEquals(0.4, back.getJSONObject("locations").getDouble("totalProgression"), 1e-9)
    }

    @Test
    fun `snapping lands on a page already read rather than one that is not`() {
        val snapped = KomgaLocator.snap(positions(), readium("OEBPS/Text/titl001.xhtml", 0.5))!!

        // 0.333... is behind the reader; 0.666... would be ahead of them.
        assertEquals(0.33333334, snapped.getJSONObject("locations").getDouble("progression"), 1e-7)
    }

    @Test
    fun `snapping accepts a progression komga itself handed out`() {
        // The index is rounded, so comparing exactly would refuse the
        // very position that came from the server a moment earlier.
        val snapped = KomgaLocator.snap(
            positions(),
            readium("OEBPS/Text/titl001.xhtml", 0.33333334),
        )!!

        assertEquals(3, snapped.getJSONObject("locations").getInt("position"))
    }

    @Test
    fun `a reader before the first known position stays in their resource`() {
        val snapped = KomgaLocator.snap(positions(), readium("OEBPS/Text/titl001.xhtml", -1.0))!!

        assertEquals("OEBPS/Text/titl001.xhtml", snapped.getString("href"))
    }

    @Test
    fun `an href komga has never heard of falls back to the right part of the book`() {
        // This is what a spelling mismatch looks like from here. The
        // position is worth keeping even approximately.
        val snapped = KomgaLocator.snap(
            positions(),
            readium("something/else.xhtml", progression = 0.5, total = 0.0025),
        )!!

        assertEquals(3, snapped.getJSONObject("locations").getInt("position"))
    }

    @Test
    fun `an unknown href with nothing else to go on is not guessed at`() {
        // Writing a place the reader has never been into the one record
        // that says where they got to would be worse than not writing.
        assertNull(KomgaLocator.snap(positions(), readium("something/else.xhtml", 0.5)))
    }

    @Test
    fun `there is nothing to snap to in an empty index`() {
        assertNull(KomgaLocator.snap(emptyList(), readium("OEBPS/ch1.xhtml", 0.5)))
    }

    @Test
    fun `the positions index is read as komga sends it`() {
        val parsed = KomgaLocator.positionsOf(JSONObject(POSITIONS))

        assertEquals(3, parsed.size)
        assertTrue(parsed.all { it.getString("href").isNotEmpty() })
    }

    private fun positions() = KomgaLocator.positionsOf(JSONObject(POSITIONS))

    private companion object {
        const val A_POSITION = """
        {"href":"OEBPS/Text/titlepage.xhtml","type":"application/xhtml+xml",
         "locations":{"progression":0.0,"position":1,"totalProgression":0.000856898},
         "koboSpan":"kobo.1.1"}
        """

        /** The first three entries of a real 1167-position index. */
        const val POSITIONS = """
        {"total":3,"positions":[
          {"href":"OEBPS/Text/titlepage.xhtml","type":"application/xhtml+xml",
           "locations":{"progression":0.0,"position":1,"totalProgression":0.000856898},
           "koboSpan":"kobo.1.1"},
          {"href":"OEBPS/Text/titl001.xhtml","type":"application/xhtml+xml",
           "locations":{"progression":0.0,"position":2,"totalProgression":0.001713796},
           "koboSpan":"kobo.1.1"},
          {"href":"OEBPS/Text/titl001.xhtml","type":"application/xhtml+xml",
           "locations":{"progression":0.33333334,"position":3,"totalProgression":0.002570694},
           "koboSpan":"kobo.1.1"}
        ]}
        """
    }
}
