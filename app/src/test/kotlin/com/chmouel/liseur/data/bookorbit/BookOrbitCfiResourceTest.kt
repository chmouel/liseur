package com.chmouel.liseur.data.bookorbit

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BookOrbitCfiResourceTest {
    private val publication by lazy {
        BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            requireNotNull(javaClass.getResourceAsStream("/bookorbit/OPS/package.opf")).use { it.readBytes() },
        )
    }

    @Test
    fun `Foliate points and ranges select the same local EPUB resource`() {
        val corpus = JSONArray(requireNotNull(javaClass.getResource("/bookorbit/foliate-cfis.json")).readText())
        for (i in 0 until corpus.length()) {
            val fixture = corpus.getJSONObject(i)
            val target = requireNotNull(BookOrbitCfiResource.locate(BookOrbitCfi.parse(fixture.getString("cfi")), publication))
            assertEquals("OPS/one.xhtml", target.href)
            assertEquals(fixture.getInt("start"), target.start.offset?.character)
            if (fixture.getString("name").startsWith("range")) {
                assertEquals(fixture.getInt("end"), target.end?.offset?.character)
            } else {
                assertNull(target.end)
            }
        }
    }

    @Test
    fun `wrong spine index or assertion cannot select an unrelated edition`() {
        for (raw in listOf(
            "epubcfi(/4[reading-order]/2[ref-one]!/4/2/1:9)",
            "epubcfi(/6[other]/2[ref-one]!/4/2/1:9)",
            "epubcfi(/6[reading-order]/4[ref-one]!/4/2/1:9)",
            "epubcfi(/6[reading-order]/2[other]!/4/2/1:9)",
            "epubcfi(/6[reading-order]/2[ref-one]/4/2/1:9)",
            "epubcfi(/6[reading-order]/2[ref-one]!/4!/2/1:9)",
        )) {
            assertNull(raw, BookOrbitCfiResource.locate(BookOrbitCfi.parse(raw), publication))
        }
    }

    @Test
    fun `shifted OPF spine invalidates the original CFI`() {
        val original = requireNotNull(javaClass.getResource("/bookorbit/OPS/package.opf")).readText()
        val shifted = BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            original.replace("<spine", """<extra xmlns="urn:liseur:fixture"/><spine""").toByteArray(),
        )
        val cfi = BookOrbitCfi.parse("epubcfi(/6[reading-order]/2[ref-one]!/4/2/1:9)")
        assertNull(BookOrbitCfiResource.locate(cfi, shifted))
    }

    @Test
    fun `range endpoint indirections retain both positions in the selected resource`() {
        val cfi = BookOrbitCfi.parse(
            "epubcfi(/6[reading-order]/2[ref-one],!/4/2/1:9,!/4/2/1:15)",
        )
        val target = BookOrbitCfiResource.locate(cfi, publication)
        assertEquals("OPS/one.xhtml", target?.href)
        assertNotNull(target?.end)
    }
}
