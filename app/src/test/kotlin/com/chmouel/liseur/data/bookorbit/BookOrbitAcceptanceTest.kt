package com.chmouel.liseur.data.bookorbit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookOrbitAcceptanceTest {
    @Test
    fun `captured BookOrbit Foliate output retains endpoints and package assertions`() {
        val corpus = JSONArray(resource("foliate-cfis.json").decodeToString())
        for (index in 0 until corpus.length()) {
            val fixture = corpus.getJSONObject(index)
            val raw = fixture.getString("cfi")
            val parsed = BookOrbitCfi.parse(raw)
            val root = when (parsed) {
                is BookOrbitCfi.Point -> {
                    assertEquals(fixture.getInt("start"), parsed.path.offset!!.character)
                    parsed.path
                }
                is BookOrbitCfi.Range -> {
                    assertEquals(fixture.getInt("start"), parsed.start.offset!!.character)
                    assertEquals(fixture.getInt("end"), parsed.end.offset!!.character)
                    parsed.parent
                }
            }
            assertEquals(BookOrbitCfi.Component.Step(6, "reading-order"), root.components[0])
            assertEquals(BookOrbitCfi.Component.Step(2, "ref-one"), root.components[1])
            assertEquals(raw, parsed.serialize())
            assertEquals(parsed, BookOrbitCfi.parse(parsed.serialize()))
        }
    }

    @Test
    fun `spine indices use actual element positions not a synthetic six`() {
        val opf = resource("OPS/package.opf").decodeToString()
        val normal = BookOrbitEpubPackage.parsePackage("OPS/package.opf", opf.toByteArray())
        val reordered = opf.replace(
            "<spine", """<extra xmlns="urn:liseur:fixture"/><spine""",
        ).replace(
            """<itemref id="ref-extra"""", """<extra xmlns="urn:liseur:fixture"/><itemref id="ref-extra"""",
        )
        val shifted = BookOrbitEpubPackage.parsePackage("OPS/package.opf", reordered.toByteArray())
        assertEquals(6, normal.spineStep.index)
        assertEquals(8, shifted.spineStep.index)
        assertEquals(2, shifted.spine[0].step.index)
        assertEquals(6, shifted.spine[1].step.index)
        assertFalse(shifted.spine[1].linear)
        assertEquals("OPS/extra.xhtml", shifted.spine[1].href)
    }

    @Test
    fun `server package facts are only a cross-check and cannot replace local spine`() {
        val local = BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            """<package xmlns="http://www.idpf.org/2007/opf">
              <manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="one"/></spine></package>""".toByteArray(),
        )
        val info = BookOrbitEpubInfo.parse(JSONObject(BookOrbitCfiRepositoryTest.INFO))
        assertTrue(info.agreesWith(local))
        assertFalse(info.copy(packagePath = "different.opf").agreesWith(local))
        assertFalse(info.copy(spine = listOf(info.spine.single().copy(href = "other.xhtml"))).agreesWith(local))
        assertEquals(4, local.spineStep.index)
    }

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/bookorbit/$path")).use { it.readBytes() }
}
