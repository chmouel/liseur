package com.chmouel.liseur.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.translate.PageSentence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * The earlier text a page sentence goes with, over a real EPUB opened by
 * Readium: a walk started in the middle of a chapter finds each sentence
 * where a walk from the chapter's start does.
 */
@Config(sdk = [35], application = Application::class)
@RunWith(RobolectricTestRunner::class)
class TranslatedPagesPrecedingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private suspend fun walk(publication: Publication, from: org.readium.r2.shared.publication.Locator): List<PageSentence> {
        val sentences = TranslatedPages.sentences(publication, from, "en")
        return generateSequence { runBlocking { sentences.next() } }.toList()
    }

    @Test
    fun `a walk started mid-chapter gets the sentences before each one, and none after`() = runBlocking {
        val publication = openPublication(File(folder.root, "book.epub").also(::writeEpub))
        val start = publication.locatorFromLink(publication.readingOrder.first())!!
        val whole = walk(publication, start)
        val chapter = whole.filter { it.href == whole.first().href }
        assertTrue(chapter.size >= 6)

        // The reader starts from the third paragraph, as from a selection there.
        val third = chapter.first { "Tobin answers" in it.text }
        val from = start.copy(
            locations = start.locations.copy(otherLocations = mapOf("cssSelector" to third.selector!!)),
        )
        val mid = walk(publication, from).filter { it.href == third.href }
        assertEquals(chapter.dropWhile { it != third }, mid)

        val preceding = TranslatedPages.preceding(publication, "en")
        for (sentence in mid) {
            val at = chapter.indexOf(sentence)
            val earlier = preceding.before(sentence, 16)
            assertEquals(sentence.text, chapter.subList(maxOf(0, at - 16), at), earlier)
        }
        // The second "Yes." is not taken for the first.
        val yeses = chapter.filter { it.text == "Yes." }
        assertEquals(2, yeses.size)
        assertEquals(chapter.indexOf(yeses[1]), preceding.before(yeses[1], 100)!!.size)

        // The next chapter starts with nothing before it.
        val next = whole.first { it.href != chapter.first().href }
        assertEquals(emptyList<PageSentence>(), preceding.before(next, 8))
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
            val files = listOf("one", "two")
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" xml:lang="en">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:uuid:5b1e2a0c-1111-4222-8333-444455556668</dc:identifier>
                    <dc:title>Context</dc:title>
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
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body>
                <nav epub:type="toc"><ol>
                  <li><a href="one.xhtml">One</a></li>
                  <li><a href="two.xhtml">Two</a></li>
                </ol></nav></body></html>""",
            )
            put(
                "OEBPS/one.xhtml",
                page(
                    "<p>Mira walks to the mill.</p>",
                    "<p>\"Is it done?\" she asks.</p>",
                    "<p>Yes.</p>",
                    "<p>Tobin answers her. He looks away.</p>",
                    "<p>Yes.</p>",
                    "<p>The word hangs there. <em>Nobody</em> moves.</p>",
                ),
            )
            put("OEBPS/two.xhtml", page("<p>Morning comes. Mira leaves.</p>"))
        }
    }
}
