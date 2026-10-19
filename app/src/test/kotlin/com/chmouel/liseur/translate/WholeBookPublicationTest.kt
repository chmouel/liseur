package com.chmouel.liseur.translate

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.util.toAbsoluteUrl
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WholeBookPublicationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun epub(href: String, embedded: String? = "chapter.xhtml", auxiliary: String = ""): File {
        val file = folder.newFile("book-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            entry("EPUB/package.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">test</dc:identifier><dc:title>Test</dc:title><dc:language>en</dc:language>
                  </metadata>
                  <manifest><item id="chapter" href="$href" media-type="application/xhtml+xml"/>$auxiliary</manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent())
            if (embedded != null) entry("EPUB/$embedded", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>A sentence.</p></body></html>""")
        }
        return file
    }

    @Test
    fun `embedded chapters including encoded paths remain readable`(): Unit = runBlocking {
        val opener = TranslationPublicationOpener(ApplicationProvider.getApplicationContext<Context>())
        for ((href, entry) in listOf("chapter.xhtml" to "chapter.xhtml", "chapter%20one.xhtml" to "chapter one.xhtml")) {
            opener.withPublication(Uri.fromFile(epub(href, entry)).toAbsoluteUrl()!!) { publication ->
                assertTrue(publication.translationSentences("en").next() != null)
            }
        }
    }

    @Test
    fun `external and missing chapters never reach the network even when auxiliary resources enable fallback`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val opener = TranslationPublicationOpener(ApplicationProvider.getApplicationContext<Context>())
            val remote = server.url("/chapter.xhtml").toString()
            for (href in listOf(remote, "//127.0.0.1:${server.port}/chapter.xhtml", "missing.xhtml", "../missing.xhtml", "%2e%2e/missing.xhtml")) {
                val auxiliary = """<item id="external" href="$remote" media-type="application/xhtml+xml"/>"""
                try {
                    opener.withPublication(Uri.fromFile(epub(href, embedded = null, auxiliary)).toAbsoluteUrl()!!) { publication ->
                        publication.translationSentences("en")
                    }
                    error("A nonembedded chapter was accepted: $href")
                } catch (_: IOException) {
                    assertEquals(0, server.requestCount)
                } catch (_: TranslationError.Unsupported) {
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }
}
