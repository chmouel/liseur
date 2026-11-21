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
        assertTrue(BookOrbitIncomingAnchor.mayOfferOnOpen("{}", null))
        assertFalse(BookOrbitIncomingAnchor.mayOfferOnOpen("saved locator", null))
        assertFalse(BookOrbitIncomingAnchor.mayOfferOnOpen("{}", 0.0))
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
        assertEquals(target.progression, proposal.locations.progression)
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
        val start = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[first]/1:0)", publication, document(),
        )!!
        assertTrue(start.progression!! < target.progression!!)
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

    @Test
    fun `element cfi resolves to the following visible text`() {
        fun parse(body: String) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(
            """<html xmlns="http://www.w3.org/1999/xhtml"><head/><body>$body</body></html>""",
        )))

        val doc = parse(
            """<a id="mark"/>
               <div>
                 <svg xmlns="http://www.w3.org/2000/svg"><title>Ignored</title></svg>
                 <p id="first">Open<i>ing</i> sentence.</p>
                 <p id="second">Next paragraph.</p>
               </div>""",
        )
        val fromMark = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[mark])",
            publication,
            doc,
        )!!
        assertEquals("body > div:nth-of-type(1) > p:nth-of-type(1)", fromMark.anchor.cssSelector)
        assertEquals("Opening", fromMark.anchor.highlight)
        assertEquals(" sentence.", fromMark.anchor.after)

        val afterFirst = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/4/4[first;s=a])",
            publication,
            doc,
        )!!
        assertEquals("body > div:nth-of-type(1) > p:nth-of-type(2)", afterFirst.anchor.cssSelector)
        assertEquals("Next", afterFirst.anchor.highlight)

        val textless = parse("""<div><svg xmlns="http://www.w3.org/2000/svg"><title>Ignored</title></svg></div>""")
        assertNull(BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2/2)",
            publication,
            textless,
        ))
    }

    @Test
    fun `element cfi skips hidden subtrees without joining their text to the visible word`() {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(
            """<html xmlns="http://www.w3.org/1999/xhtml"><head/><body><a id="mark"/><p hidden="hidden">Hidden</p><svg xmlns="http://www.w3.org/2000/svg"><defs><text>Metadata</text></defs></svg><p><script>prefix</script>Open<i>ing</i><span hidden="hidden">suffix</span> sentence.</p></body></html>""",
        )))
        val target = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[mark])", publication, doc,
        )!!
        assertEquals("body > p:nth-of-type(2)", target.anchor.cssSelector)
        assertEquals("Opening", target.anchor.highlight)
        assertEquals("prefix", target.anchor.before)
        assertEquals("suffix sentence.", target.anchor.after)
    }

    @Test
    fun `direct body text after an element does not join the preceding paragraph`() {
        for (previous in listOf("<p>Previous</p>", "Previous<p/>")) {
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(InputSource(StringReader(
                """<html xmlns="http://www.w3.org/1999/xhtml"><head/><body>$previous<a id="mark"/>Current sentence.</body></html>""",
            )))
            val target = BookOrbitIncomingAnchor.resolve(
                "epubcfi(/6[reading-order]/2[ref-one]!/4/4[mark])", publication, doc,
            )!!
            assertEquals("body", target.anchor.cssSelector)
            assertEquals("Current", target.anchor.highlight)
            assertEquals("Previous", target.anchor.before)
        }
    }

    @Test
    fun `inline illustrations and breaks separate the following visible word`() {
        for (separator in listOf(
            "<img id=\"plate\"/>",
            "<br id=\"plate\"/>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"plate\"><image href=\"plate.png\"/></svg>",
        )) {
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(InputSource(StringReader(
                """<html xmlns="http://www.w3.org/1999/xhtml"><head/><body><p>Before${separator}After starts here.</p></body></html>""",
            )))
            val target = BookOrbitIncomingAnchor.resolve(
                "epubcfi(/6[reading-order]/2[ref-one]!/4/2/2[plate])", publication, doc,
            )!!
            assertEquals("After", target.anchor.highlight)
            assertEquals("Before", target.anchor.before)
            assertEquals(" starts here.", target.anchor.after)
        }
    }

    @Test
    fun `tokens longer than 64 code points slice contiguously around the offset`() {
        val longToken = "一".repeat(20) + "😀" + (1..60).joinToString("") { "章%02d".format(it) }
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(InputSource(StringReader(
            """<html xmlns="http://www.w3.org/1999/xhtml">
                 <head/>
                 <body><p id="cjk">$longToken</p></body>
               </html>""",
        )))
        // Offset 21 falls on the low surrogate of 😀 (indices 20..21).
        val target = BookOrbitIncomingAnchor.resolve(
            "epubcfi(/6[reading-order]/2[ref-one]!/4/2[cjk]/1:21)",
            publication,
            doc,
        )!!
        val quote = target.anchor.before + target.anchor.highlight + target.anchor.after
        assertTrue(longToken.contains(quote))
        assertTrue(target.anchor.highlight.startsWith("😀章01"))
    }
}
