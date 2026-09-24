package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitCfi
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiDom
import com.chmouel.liseur.data.bookorbit.BookOrbitCfiResource
import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Text
import org.xml.sax.InputSource

class BookOrbitViewportCfiTest {
    private val publication by lazy {
        BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            requireNotNull(javaClass.getResourceAsStream("/bookorbit/OPS/package.opf"))
                .use { it.readBytes() },
        )
    }

    @Test
    fun `WebView encoded object is decoded before using its viewport point`() {
        val objectJson = """{"path":"/4/2/1:3","text":"hello","word":"lo"}"""
        val encoded = org.json.JSONObject.quote(objectJson)
        assertEquals("/4/2/1:3", BookOrbitViewportCfi.parseJavascriptResult(encoded)?.getString("path"))
        assertEquals("/4/2/1:3", BookOrbitViewportCfi.parseJavascriptResult(objectJson)?.getString("path"))
        assertNull(BookOrbitViewportCfi.parseJavascriptResult("null"))
        assertNull(BookOrbitViewportCfi.parseJavascriptResult("\"malformed\""))
    }

    @Test
    fun `viewport path is prefixed with selected spine identity`() {
        val raw = requireNotNull(BookOrbitViewportCfi.assemble(
            publication, "OPS/one.xhtml", "/4/2[first]/1:9",
        ))
        assertEquals("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)", raw)
        val resource = requireNotNull(BookOrbitCfiResource.locate(BookOrbitCfi.parse(raw), publication))
        assertEquals("OPS/one.xhtml", resource.href)
        val xhtml = requireNotNull(javaClass.getResource("/bookorbit/OPS/one.xhtml")).readText()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xhtml)))
        assertEquals(9, BookOrbitCfiDom.resolve(resource, document)?.start?.offset)
    }

    @Test
    fun `escaped authored ids and href encoding survive without approximation`() {
        val raw = requireNotNull(BookOrbitViewportCfi.assemble(
            publication, "OPS/one.xhtml", "/4/4[escaped^,^;^=^[^]^^]/1:3",
        ))
        assertEquals("epubcfi(/6[reading-order]/2[ref-one]!/4/4[escaped^,^;^=^[^]^^]/1:3)", raw)
        val xhtml = requireNotNull(javaClass.getResource("/bookorbit/OPS/one.xhtml")).readText()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xhtml)))
        val resource = requireNotNull(BookOrbitCfiResource.locate(BookOrbitCfi.parse(raw), publication))
        assertEquals(3, BookOrbitCfiDom.resolve(resource, document)?.start?.offset)
    }

    @Test
    fun `candidate must resolve to the same original text across split nodes`() {
        val xhtml = requireNotNull(javaClass.getResource("/bookorbit/OPS/one.xhtml")).readText()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xhtml)))
        val text = "Before 😀 after café."
        val path = "/4/2[first]/1:7"
        val expected = BookOrbitViewportCfi.assemble(publication, "OPS/one.xhtml", path)
        assertEquals(expected, BookOrbitViewportCfi.verify(
            publication, "OPS/one.xhtml", path, text, "😀", document,
        ))
        (document.getElementsByTagName("p").item(0).firstChild as Text).splitText(4)
        assertEquals(expected, BookOrbitViewportCfi.verify(
            publication, "OPS/one.xhtml", path, text, "😀", document,
        ))
        assertNull(BookOrbitViewportCfi.verify(
            publication, "OPS/one.xhtml", path, "Injected passage", "😀", document,
        ))
        assertNull(BookOrbitViewportCfi.verify(
            publication, "OPS/one.xhtml", "/4/2[first]/1:999", text, "😀", document,
        ))
        assertNull(BookOrbitViewportCfi.verify(
            publication, "OPS/one.xhtml", path, text, "Other", document,
        ))
    }

    @Test
    fun `no CFI is constructed for another resource or malformed local point`() {
        assertNull(BookOrbitViewportCfi.assemble(publication, "OPS/missing.xhtml", "/4/2[first]/1:9"))
        val duplicate = publication.copy(spine = publication.spine + publication.spine.first())
        assertNull(BookOrbitViewportCfi.assemble(duplicate, "OPS/one.xhtml", "/4/2[first]/1:9"))
        for (path in listOf("", "/4/2", "/4/2!/1:3", "/4/2/1:3,/4/4/1:3", "/4/2/1:bad")) {
            assertNull(path, BookOrbitViewportCfi.assemble(publication, "OPS/one.xhtml", path))
        }
    }

    private fun candidate(before: String, word: String) = BookOrbitViewportCfi.Candidate(
        com.chmouel.liseur.data.bookorbit.BookOrbitCfiContext(
            com.chmouel.liseur.data.bookorbit.BookOrbitRequestContext("account", 0, "https://orbit.example"),
            "book", 1, 2, 1,
        ),
        "OPS/one.xhtml", "epubcfi(/6/2!/4/1:0)", before, word,
    )

    private fun anchor(before: String, highlight: String) =
        ViewportTextAnchor("body > p", before, highlight, "")

    @Test
    fun `a candidate token matches the anchor word it contains`() {
        val before = "x".repeat(19) + " bon pour un "
        assertTrue(BookOrbitViewportCfi.startsAt(candidate(before, "Madrid,"), anchor(before, "Madrid")))
        assertTrue(BookOrbitViewportCfi.startsAt(
            candidate(before, "Rio-Madrid,"), anchor((before + "Rio-").takeLast(32), "Madrid"),
        ))
        assertTrue(BookOrbitViewportCfi.startsAt(candidate("dit : ", "«Bonjour"), anchor("dit : «", "Bonjour")))
    }

    @Test
    fun `a candidate elsewhere in the text does not match the anchor`() {
        assertFalse(BookOrbitViewportCfi.startsAt(candidate("un ", "Rio-Madrid,"), anchor("autre Rio-", "Madrid")))
        assertFalse(BookOrbitViewportCfi.startsAt(candidate("un ", "Madrid"), anchor("un ", "Paris")))
        assertFalse(BookOrbitViewportCfi.startsAt(candidate("un ", "Rio"), anchor("un Rio ", "Madrid")))
    }

    @Test
    fun `a capture of the page being left is asked again until the new page answers`() = runTest {
        val answers = ArrayDeque(listOf("left", null, "arrived", "later"))
        var asked = 0
        val found = BookOrbitViewportCfi.firstMatching(
            attempts = 4, retryDelayMs = 200, stillWanted = { true },
            capture = { asked++; answers.removeFirst() },
            matches = { it == "arrived" },
        )
        assertEquals("arrived", found)
        assertEquals(3, asked)
    }

    @Test
    fun `a capture stops once the reader has moved on or the tries run out`() = runTest {
        var asked = 0
        assertNull(
            BookOrbitViewportCfi.firstMatching(
                attempts = 4, retryDelayMs = 200, stillWanted = { asked < 2 },
                capture = { asked++; "left" }, matches = { it == "arrived" },
            ),
        )
        assertEquals(2, asked)
        asked = 0
        assertNull(
            BookOrbitViewportCfi.firstMatching(
                attempts = 3, retryDelayMs = 200, stillWanted = { true },
                capture = { asked++; "left" }, matches = { it == "arrived" },
            ),
        )
        assertEquals(3, asked)
    }
}
