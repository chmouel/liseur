package com.chmouel.liseur.data.bookorbit

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource

class BookOrbitCfiDomTest {
    private val publication by lazy {
        BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            requireNotNull(javaClass.getResourceAsStream("/bookorbit/OPS/package.opf")).use { it.readBytes() },
        )
    }

    private fun document(): Document {
        val xhtml = requireNotNull(javaClass.getResource("/bookorbit/OPS/one.xhtml")).readText()
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xhtml)))
    }

    private fun target(cfi: String): BookOrbitCfiResource =
        requireNotNull(BookOrbitCfiResource.locate(BookOrbitCfi.parse(cfi), publication))

    @Test
    fun `Foliate corpus lands on both exact text endpoints`() {
        val corpus = JSONArray(requireNotNull(javaClass.getResource("/bookorbit/foliate-cfis.json")).readText())
        val document = document()
        for (i in 0 until corpus.length()) {
            val fixture = corpus.getJSONObject(i)
            val range = requireNotNull(BookOrbitCfiDom.resolve(target(fixture.getString("cfi")), document))
            assertEquals(fixture.getInt("start"), range.start.offset)
            assertEquals(fixture.getInt("end"), range.end.offset)
            assertEquals(
                if (fixture.getString("name") == "escaped-id") "escaped,;=[]^" else "first",
                range.start.node.parentNode.attributes.getNamedItem("id").nodeValue,
            )
        }
    }

    @Test
    fun `split text nodes retain UTF16 character offsets`() {
        val document = document()
        val first = document.getElementById("first") ?: document.getElementsByTagName("p").item(0)
        val text = first.firstChild
        text.nodeValue = text.nodeValue.substring(5)
        first.insertBefore(document.createTextNode("Before"), text)
        val resolved = requireNotNull(BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)"), document,
        ))
        assertEquals(text, resolved.start.node)
        assertEquals(3, resolved.start.offset)
    }

    @Test
    fun `injected wrapper can be flattened without shifting EPUB steps`() {
        val document = document()
        val first = document.getElementsByTagName("p").item(0)
        val text = first.firstChild
        first.removeChild(text)
        val wrapper = document.createElement("span")
        wrapper.setAttribute("data-reader-wrapper", "true")
        wrapper.appendChild(text)
        first.appendChild(wrapper)
        val target = target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)")
        assertNull(BookOrbitCfiDom.resolve(target, document))
        assertEquals(
            9,
            BookOrbitCfiDom.resolve(target, document) {
                it.getAttribute("data-reader-wrapper") == "true"
            }?.start?.offset,
        )
    }

    @Test
    fun `an injected wrapper around an EPUB element preserves its original CFI`() {
        val document = document()
        val body = document.getElementsByTagName("body").item(0)
        val first = document.getElementsByTagName("p").item(0)
        val wrapper = document.createElement("div")
        wrapper.setAttribute("data-reader-wrapper", "true")
        body.replaceChild(wrapper, first)
        wrapper.appendChild(first)
        val cfi = "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)"
        assertNull(BookOrbitCfiDom.resolve(target(cfi), document))
        val skip: (org.w3c.dom.Element) -> Boolean = { it.getAttribute("data-reader-wrapper") == "true" }
        assertEquals(9, BookOrbitCfiDom.resolve(target(cfi), document, skip)?.start?.offset)
        assertEquals(cfi, BookOrbitCfiDom.capture(
            publication, "OPS/one.xhtml", document, BookOrbitCfiDom.Position(first.firstChild, 9, null),
            skipWrapper = skip,
        ))
    }

    @Test
    fun `invalid offset assertion and reversed range remain unresolved`() {
        val document = document()
        for (cfi in listOf(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:100)",
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[other]/1:9)",
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9[wrong,after])",
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first],/1:15,/1:9)",
        )) {
            assertNull(cfi, BookOrbitCfiDom.resolve(target(cfi), document))
        }
        assertNotNull(BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)"), document,
        ))
    }

    @Test
    fun `side bias survives DOM verification for later navigation`() {
        val document = document()
        val offsetBias = BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9[;s=a])"), document,
        )
        assertEquals(BookOrbitCfi.SideBias.AFTER, offsetBias?.start?.sideBias)
        val stepBias = BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1[;s=b]:9)"), document,
        )
        assertEquals(BookOrbitCfi.SideBias.BEFORE, stepBias?.start?.sideBias)
        assertNull(BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4[;s=b]/2[first]/1:9)"), document,
        ))
    }

    @Test
    fun `text assertions validate the UTF16 position`() {
        val document = document()
        assertNotNull(BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9[😀, after])"),
            document,
        ))
        assertNull(BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9[wrong, after])"),
            document,
        ))
    }

    @Test
    fun `XML ID assertions resolve but conflicting IDs do not`() {
        val xhtml = requireNotNull(javaClass.getResource("/bookorbit/OPS/one.xhtml")).readText()
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(xhtml.replace(
                """id="first"""", """xml:id="first"""",
            ))))
        val cfi = target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)")
        assertNotNull(BookOrbitCfiDom.resolve(cfi, document))
            val paragraph = document.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "p").item(0)
                as org.w3c.dom.Element
            paragraph.setAttribute("id", "other")
        assertNull(BookOrbitCfiDom.resolve(cfi, document))
    }

    @Test
    fun `outgoing point and range resolve back to both text locations`() {
        val document = document()
        val paragraphs = document.getElementsByTagName("p")
        val first = BookOrbitCfiDom.Position(paragraphs.item(0).firstChild, 9, null)
        val second = BookOrbitCfiDom.Position(paragraphs.item(1).firstChild, 7, null)
        val point = requireNotNull(BookOrbitCfiDom.capture(publication, "OPS/one.xhtml", document, first))
        assertEquals("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9)", point)
        val range = requireNotNull(BookOrbitCfiDom.capture(
            publication, "OPS/one.xhtml", document, first, second,
        ))
        assertEquals(BookOrbitCfiDom.Range(first, second), BookOrbitCfiDom.resolve(target(range), document))
        assertEquals(range, BookOrbitCfi.parse(range).serialize())
        assertNull(BookOrbitCfiDom.capture(publication, "OPS/other.xhtml", document, first))
    }

    @Test
    fun `web reader range restores an element start and text end`() {
        val document = document()
        val range = requireNotNull(BookOrbitCfiDom.resolve(
            target("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first],,/1:9)"),
            document,
        ))
        assertEquals(document.getElementsByTagName("p").item(0), range.start.node)
        assertEquals(0, range.start.offset)
        assertEquals(document.getElementsByTagName("p").item(0).firstChild, range.end.node)
        assertEquals(9, range.end.offset)
    }

    @Test
    fun `outgoing capture adjusts split UTF16 text and escapes asserted IDs`() {
        val document = document()
        val paragraphs = document.getElementsByTagName("p")
        val first = paragraphs.item(0)
        val tail = first.firstChild
        tail.nodeValue = tail.nodeValue.substring(5)
        first.insertBefore(document.createTextNode("Before"), tail)
        val split = BookOrbitCfiDom.Position(tail, 3, BookOrbitCfi.SideBias.AFTER)
        val captured = requireNotNull(BookOrbitCfiDom.capture(publication, "OPS/one.xhtml", document, split))
        assertEquals("epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:9[;s=a])", captured)
        val other = BookOrbitCfiDom.Position(paragraphs.item(1).firstChild, 3, null)
        assertEquals(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/4[escaped^,^;^=^[^]^^]/1:3)",
            BookOrbitCfiDom.capture(publication, "OPS/one.xhtml", document, other),
        )
    }

    @Test
    fun `unknown or detached DOM nodes never produce a CFI`() {
        val document = document()
        val position = BookOrbitCfiDom.Position(document.createTextNode("detached"), 1, null)
        assertNull(BookOrbitCfiDom.capture(publication, "OPS/one.xhtml", document, position))
        val foreign = BookOrbitCfiDom.Position(document().getElementsByTagName("p").item(0).firstChild, 1, null)
        assertNull(BookOrbitCfiDom.capture(publication, "OPS/one.xhtml", document, foreign))
    }
}
