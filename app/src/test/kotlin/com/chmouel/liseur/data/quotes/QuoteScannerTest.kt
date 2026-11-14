package com.chmouel.liseur.data.quotes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lines are only ever taken from what was read, over a real EPUB opened by Readium. */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class QuoteScannerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val early = "Every house remembers the people who were happy in it."
    private val late = "Nothing grows in a garden that nobody is willing to wait for."

    @Test
    fun `a book read halfway is quoted from its first half only`() = runBlocking {
        val publication = openPublication(File(folder.root, "book.epub").also(::writeEpub))
        val half = QuoteScanner.walk(publication, 0.5)
        assertTrue(half.any { it.text == early })
        assertFalse(half.any { it.text == late })
        assertTrue(half.all { it.totalProgression <= 0.5 })

        val line = half.first { it.text == early }
        assertEquals("Morning", line.chapter)
        val locator = Locator.fromJSON(JSONObject(line.locatorJson))!!
        assertEquals(early, locator.text.highlight)
        assertTrue(locator.href.toString().endsWith("one.xhtml"))

        val whole = QuoteScanner.walk(publication, 1.0)
        assertTrue(whole.any { it.text == late })
        assertEquals("Evening", whole.first { it.text == late }.chapter)
    }

    @Test
    fun `nothing read, nothing quoted`() = runBlocking {
        val publication = openPublication(File(folder.root, "book.epub").also(::writeEpub))
        assertEquals(emptyList<FoundLine>(), QuoteScanner.walk(publication, 0.0))
    }

    @Test
    fun `a whole-book ceiling from elsewhere stops at the chapter this device recorded`() = runBlocking {
        // The reader is in the first chapter. A ceiling written by
        // another client is on its own scale and says 0.9 here; the walk
        // must still not reach the second chapter's line.
        val publication = openPublication(File(folder.root, "book.epub").also(::writeEpub))
        val here = Locator.fromJSON(
            JSONObject()
                .put("href", "OEBPS/one.xhtml")
                .put("type", "application/xhtml+xml")
                .put("locations", JSONObject().put("progression", 0.5)),
        )!!

        val lines = QuoteScanner.walk(publication, 0.9, until = here)

        assertTrue(lines.all { it.chapter == "Morning" })
        assertFalse(lines.any { it.text == late })
    }

    @Test
    fun `a chapter this book does not have leaves the ceiling to the fraction`() = runBlocking {
        val publication = openPublication(File(folder.root, "book.epub").also(::writeEpub))
        val elsewhere = Locator.fromJSON(
            JSONObject()
                .put("href", "OEBPS/somewhere-else.xhtml")
                .put("type", "application/xhtml+xml")
                .put("locations", JSONObject().put("progression", 0.1)),
        )!!

        assertTrue(QuoteScanner.walk(publication, 1.0, until = elsewhere).any { it.text == late })
    }

    private suspend fun openPublication(epub: File): Publication {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val http = DefaultHttpClient()
        val assets = AssetRetriever(context.contentResolver, http)
        val asset = assets.retrieve(epub.toUrl(isDirectory = false)).getOrNull()!!
        return PublicationOpener(DefaultPublicationParser(context, http, assets, pdfFactory = null))
            .open(asset, allowUserInteraction = false).getOrNull()!!
    }

    private fun page(vararg paragraphs: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>
        ${paragraphs.joinToString("\n") { "<p>$it</p>" }}
        </body></html>"""

    // Two chapters of the same length, so the first ends halfway through the book.
    private fun chapter(quote: String) = page(
        *Array(12) { "Mira walked down to the mill at the end of the lane, as she did on most days." },
        quote,
        *Array(12) { "Tobin carried the sacks in from the cart and set them by the door again." },
    )

    private fun writeEpub(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:5b1e2a0c-1111-4222-8333-444455556699</dc:identifier>
                    <dc:title>Quotes</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="one"/><itemref idref="two"/></spine>
                </package>""",
            )
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
                <nav epub:type="toc"><ol>
                  <li><a href="one.xhtml">Morning</a></li>
                  <li><a href="two.xhtml">Evening</a></li>
                </ol></nav></body></html>""",
            )
            put("OEBPS/one.xhtml", chapter(early))
            put("OEBPS/two.xhtml", chapter(late))
        }
    }
}
