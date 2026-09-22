package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xml.sax.InputSource

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class BookOrbitIncomingAnchorTest {
    @Test
    fun `incoming cold open never overrides a saved local position`() {
        assertTrue(BookOrbitIncomingAnchor.mayOfferOnOpen(null, null))
        assertFalse(BookOrbitIncomingAnchor.mayOfferOnOpen("saved locator", null))
        assertFalse(BookOrbitIncomingAnchor.mayOfferOnOpen(null, 0.0))
    }

    private val publication by lazy {
        BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            requireNotNull(javaClass.getResourceAsStream("/bookorbit/OPS/package.opf"))
                .use { it.readBytes() },
        )
    }

    private fun document() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder()
        .parse(InputSource(StringReader(
            requireNotNull(javaClass.getResource("/bookorbit/OPS/one.xhtml")).readText(),
        )))

    @Test
    fun `web reader range with empty start targets its parent passage`() {
        val target = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first],,/1:6)",
            publication,
            document(),
        )!!
        assertEquals("OPS/one.xhtml", target.href)
        assertEquals("body > p:nth-of-type(1)", target.anchor.cssSelector)
        assertEquals("Before", target.anchor.highlight)
        assertEquals("", target.anchor.before)
        assertEquals(" 😀 after café.", target.anchor.after)
        val base = requireNotNull(Locator.fromJSON(
            JSONObject("""{"href":"https://example.com/OPS/one.xhtml","type":"application/xhtml+xml","locations":{}}"""),
        ))
        val proposal = BookOrbitIncomingAnchor.mark(base, target)!!
        assertEquals("Before", ExactLocatorAnchor.anchorIn(proposal)?.highlight)
        val other = requireNotNull(Locator.fromJSON(
            JSONObject("""{"href":"https://example.com/OPS/other.xhtml","type":"application/xhtml+xml","locations":{}}"""),
        ))
        assertNull(BookOrbitIncomingAnchor.mark(other, target))
    }

    @Test
    fun `a point after a surrogate pair keeps browser UTF16 offsets`() {
        val target = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:10)",
            publication,
            document(),
        )!!
        assertEquals("after", target.anchor.highlight)
        assertEquals("Before 😀 ", target.anchor.before)
    }

    @Test
    fun `an offset inside a word restores the whole visible word`() {
        val target = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:2)",
            publication,
            document(),
        )!!
        assertEquals("Before", target.anchor.highlight)
        assertEquals("", target.anchor.before)
    }

    @Test
    fun `incoming cold open suppresses prelayout and verified arrival without writing`() {
        val target = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:10)",
            publication,
            document(),
        )!!
        val base = requireNotNull(Locator.fromJSON(
            JSONObject("""{"href":"OPS/one.xhtml","type":"application/xhtml+xml","locations":{}}"""),
        ))
        val incoming = BookOrbitIncomingAnchor.mark(base, target)!!
        val gate = OpeningRestoration(
            RestorePoint(incoming.href.toString(), null, exact = true),
            OpeningRestoration.DEFAULT_TIMEOUT_MS,
        )
        val atStart = RestorePoint(incoming.href.toString(), 0.0, exact = false)
        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.onEmission(atStart, false, 0))
        assertTrue(gate.isGated)
        assertEquals(OpeningRestorationVerdict.SUPPRESS, gate.onEmission(atStart, true, 200))
        assertFalse(gate.isGated)
    }

    @Test
    fun `wrong spine and wrong source document cannot become an exact restore`() {
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/4[ref-one]!/4/2[first]/1:10)",
            publication,
            document(),
        ))
        val other = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="first">Different edition</p></body></html>""",
        )))
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:10[😀 ,after])",
            publication,
            other,
        ))
    }

    @Test
    fun `repeated quote in one block cannot claim a unique exact passage`() {
        val repeated = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="first">${"word ".repeat(30)}</p></body></html>""",
        )))
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:50)",
            publication,
            repeated,
        ))
    }

    @Test
    fun `wrong edition or nonexistent passage has no exact target`() {
        val xml = document()
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[other]/1:0)",
            publication,
            xml,
        ))
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:999)",
            publication,
            xml,
        ))
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:100000)",
            publication,
            xml,
        ))
    }
}
