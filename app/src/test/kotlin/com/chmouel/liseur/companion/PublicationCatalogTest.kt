package com.chmouel.liseur.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
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

/**
 * The catalog over a real EPUB opened by Readium: chapters from the
 * contents, a file holding two chapters, a chapter spread over two files,
 * and a contents entry whose anchor is missing.
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class PublicationCatalogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun open(missingAnchor: Boolean = false): PublicationCatalog = runBlocking {
        val epub = File(folder.root, "book-$missingAnchor.epub").also { writeEpub(it, missingAnchor) }
        PublicationCatalog(openPublication(epub))
    }

    private fun texts(passages: List<Passage<*>>) = passages.joinToString("\n") { it.text }

    @Test
    fun `asking in chapter 5 reaches the book up to the selection and nothing from it on`() = runBlocking {
        val catalog = open()
        assertEquals(6, catalog.map.chapterCount)

        val all = catalog.passagesBefore(AskingScope(6, 6))
        val section2 = all.first { "FIVEB" in it.text }
        val scope = catalog.scopeOf(section2.place, translated = false)!!
        assertEquals(4, scope.lo)
        assertEquals(4, scope.until!!.resource)

        val sent = texts(catalog.passagesBefore(scope))
        for (token in listOf("ONE", "TWO", "THREE", "FOUR", "FIVEA")) assertTrue("$token missing", "Mira $token" in sent)
        for (token in listOf("FIVEB", "SIX")) assertFalse("$token leaked", token in sent)

        // Chapters alone, as recaps ask, still stop before chapter 5.
        assertFalse("FIVEA" in texts(catalog.passagesBefore(AskingScope(4, 6))))
    }

    @Test
    fun `a selection reaches the paragraphs before it in its own file and not its own`() = runBlocking {
        val catalog = open()
        val ledger = catalog.passagesBefore(AskingScope(6, 6)).first { "ledger" in it.text }
        val scope = catalog.scopeOf(ledger.place, translated = false)!!
        assertEquals(0, scope.lo)
        assertTrue(scope.hasEarlier)
        assertEquals("Mira ONE walks to the mill.", texts(catalog.passagesBefore(scope)))

        // A translated page, or a selector that cannot be found, starts at the file.
        assertTrue(catalog.passagesBefore(catalog.scopeOf(ledger.place, translated = true)!!).isEmpty())
        val unknown = ledger.place.copy(
            locations = ledger.place.locations.copy(
                otherLocations = ledger.place.locations.otherLocations + ("cssSelector" to "#nowhere"),
            ),
        )
        val lost = catalog.scopeOf(unknown, translated = false)!!
        assertEquals(ReadPoint(0, 0), lost.until)
        assertFalse(lost.hasEarlier)
    }

    @Test
    fun `two chapters in one file are told apart at the contents anchor`() = runBlocking {
        val catalog = open()
        val all = catalog.passagesBefore(AskingScope(6, 6))
        assertEquals(ChapterRange(1, 1), all.first { "TWO" in it.text }.range)
        assertEquals(ChapterRange(2, 2), all.first { "THREE" in it.text }.range)

        // Going back to chapter 3 narrows what can be reached, even with
        // later chapters already walked.
        val three = catalog.scopeOf(all.first { "THREE" in it.text }.place, translated = false)!!
        assertEquals(2, three.lo)
        val sent = texts(catalog.passagesBefore(three))
        assertTrue("Mira TWO" in sent)
        assertFalse("THREE" in sent)

        // A translated page cannot place the selection inside its file.
        val translated = catalog.scopeOf(all.first { "THREE" in it.text }.place, translated = true)!!
        assertEquals(1, translated.lo)
        val withheld = texts(catalog.passagesBefore(translated))
        assertTrue("Mira ONE" in withheld)
        assertFalse("TWO" in withheld)
    }

    @Test
    fun `the first paragraph of the book has nothing before it`() = runBlocking {
        val catalog = open()
        val first = catalog.passagesBefore(AskingScope(6, 6)).first { "ONE" in it.text }
        val scope = catalog.scopeOf(first.place, translated = false)!!
        assertEquals(0, scope.lo)
        assertTrue(catalog.passagesBefore(scope).isEmpty())
    }

    @Test
    fun `a missing anchor gives the whole file both chapters and withholds it from either`() = runBlocking {
        val catalog = open(missingAnchor = true)
        val all = catalog.passagesBefore(AskingScope(6, 6))
        assertEquals(ChapterRange(1, 2), all.first { "TWO" in it.text }.range)
        assertEquals(ChapterRange(1, 2), all.first { "THREE" in it.text }.range)

        val scope = catalog.scopeOf(all.first { "THREE" in it.text }.place, translated = false)!!
        assertEquals(1, scope.lo)
        assertFalse("TWO" in texts(catalog.passagesBefore(AskingScope(scope.lo, scope.count))))
        // The text before the selection is reachable whatever its chapter.
        val sent = texts(catalog.passagesBefore(scope))
        assertTrue("Mira ONE" in sent)
        assertTrue("Mira TWO" in sent)
        assertFalse("THREE" in sent)

        // From chapter 4 on, the whole file lies before and is reachable.
        val four = catalog.scopeOf(all.first { "FOUR" in it.text }.place, translated = false)!!
        assertTrue("Mira THREE" in texts(catalog.passagesBefore(four)))
    }

    @Test
    fun `refs stay the same when the book is opened again`() = runBlocking {
        val first = open().passagesBefore(AskingScope(6, 6)).map { it.ref to it.hash }
        val again = open().passagesBefore(AskingScope(6, 6)).map { it.ref to it.hash }
        assertEquals(first, again)
        assertEquals(first.size, first.map { it.first }.distinct().size)
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
        ${paragraphs.joinToString("\n")}
        </body></html>"""

    private fun writeEpub(target: File, missingAnchor: Boolean) {
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
            val files = listOf("one", "two", "four", "five-a", "five-b", "six")
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:5b1e2a0c-1111-4222-8333-444455556667</dc:identifier>
                    <dc:title>Chapters</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>
                    ${files.joinToString("\n") { """<item id="$it" href="$it.xhtml" media-type="application/xhtml+xml"/>""" }}
                  </manifest>
                  <spine>
                    ${files.joinToString("\n") { """<itemref idref="$it"/>""" }}
                  </spine>
                </package>""",
            )
            val anchor = if (missingAnchor) "nowhere" else "c3"
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
                <nav epub:type="toc"><ol>
                  <li><a href="one.xhtml">One</a></li>
                  <li><a href="two.xhtml">Two</a></li>
                  <li><a href="two.xhtml#$anchor">Three</a></li>
                  <li><a href="four.xhtml">Four</a></li>
                  <li><a href="five-a.xhtml">Five</a><ol><li><a href="five-b.xhtml">Section 2</a></li></ol></li>
                  <li><a href="six.xhtml">Six</a></li>
                </ol></nav></body></html>""",
            )
            put("OEBPS/one.xhtml", page("<p>Mira ONE walks to the mill.</p>", "<p>Mira ONE keeps the ledger.</p>"))
            put(
                "OEBPS/two.xhtml",
                page(
                    "<p>Mira TWO meets Tobin.</p>",
                    "<h1 id=\"c3\">Three</h1>",
                    "<p>Mira THREE crosses the river.</p>",
                ),
            )
            put("OEBPS/four.xhtml", page("<p>Mira FOUR reads a letter.</p>"))
            put("OEBPS/five-a.xhtml", page("<p>Mira FIVEA learns the secret.</p>"))
            put("OEBPS/five-b.xhtml", page("<p>Mira FIVEB keeps it.</p>"))
            put("OEBPS/six.xhtml", page("<p>Mira SIX leaves.</p>"))
        }
    }
}
