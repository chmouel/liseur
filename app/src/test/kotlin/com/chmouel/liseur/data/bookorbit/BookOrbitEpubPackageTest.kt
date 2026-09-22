package com.chmouel.liseur.data.bookorbit

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookOrbitEpubPackageTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `reads epub 3 package paths and non-linear spine items`() {
        val epub = zip(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to
                """<package xmlns="http://www.idpf.org/2007/opf">
                   <manifest>
                     <item id="cover" href="images/cover.xhtml" media-type="application/xhtml+xml"/>
                     <item id="one" href="text/chapter%201.xhtml#place" media-type="application/xhtml+xml"/>
                     <item id="font" href="https://example.invalid/font.woff"/>
                   </manifest>
                   <spine><itemref idref="cover" linear="no"/><itemref idref="one"/></spine>
                   </package>""",
            "OPS/images/big.bin" to "x".repeat(1024),
        )

        val parsed = BookOrbitEpubPackage.parse(epub)

        assertEquals("OPS/package.opf", parsed.packagePath)
        assertEquals("OPS/text/chapter 1.xhtml", parsed.manifest.getValue("one").href)
        assertFalse("font" in parsed.manifest)
        assertFalse(parsed.spine[0].linear)
        assertTrue(parsed.spine[1].linear)
    }

    @Test
    fun `reads prefixed epub 2 package documents`() {
        val parsed = BookOrbitEpubPackage.parsePackage(
            "OEBPS/content.opf",
            """
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf">
              <opf:manifest><opf:item id="chapter" href="chapter.xhtml"/></opf:manifest>
              <opf:spine toc="ncx"><opf:itemref idref="chapter"/></opf:spine>
            </opf:package>
            """.trimIndent().toByteArray(),
        )

        assertEquals(listOf("OEBPS/chapter.xhtml"), parsed.spine.map { it.href })
    }

    @Test
    fun `ignores foreign namespace manifest and spine elements`() {
        val parsed = BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:x="urn:other">
               <manifest>
                 <x:item id="decoy" href="decoy.xhtml"/>
                 <item id="chapter" href="real.xhtml"/>
               </manifest>
               <x:spine><x:itemref idref="decoy"/></x:spine>
               <spine><x:itemref idref="decoy"/><itemref idref="chapter"/></spine>
               </package>""".toByteArray(),
        )

        assertEquals(setOf("chapter"), parsed.manifest.keys)
        assertEquals(listOf("OPS/real.xhtml"), parsed.spine.map { it.href })
    }

    @Test
    fun `rejects foreign namespace package and manifest`() {
        listOf(
            """<x:package xmlns:x="urn:other"><x:manifest/><x:spine/></x:package>""",
            """<package xmlns="http://www.idpf.org/2007/opf" xmlns:x="urn:other">
               <x:manifest><x:item id="decoy" href="decoy.xhtml"/></x:manifest>
               <spine><itemref idref="decoy"/></spine></package>""",
        ).forEach { opf ->
            assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
                BookOrbitEpubPackage.parsePackage("OPS/package.opf", opf.toByteArray())
            }
        }
    }

    @Test
    fun `chooses the first opf rootfile among several renditions`() {
        val path = BookOrbitEpubPackage.parseContainer(
            """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
               <rootfile full-path="alt/book.pdf" media-type="application/pdf"/>
               <rootfile full-path="OPS/main.opf" media-type="application/oebps-package+xml"/>
               <rootfile full-path="OPS/other.opf" media-type="application/oebps-package+xml"/>
               </rootfiles></container>""".toByteArray(),
        )

        assertEquals("OPS/main.opf", path)
    }

    @Test
    fun `ignores foreign namespace container elements before the real rootfile`() {
        val path = BookOrbitEpubPackage.parseContainer(
            """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                  xmlns:x="urn:other"><rootfiles>
               <x:rootfile full-path="OPS/decoy.opf" media-type="application/oebps-package+xml"/>
               <rootfile full-path="OPS/real.opf" media-type="application/oebps-package+xml"/>
               </rootfiles></container>""".toByteArray(),
        )

        assertEquals("OPS/real.opf", path)
    }

    @Test
    fun `accepts prefixed ocf elements but not foreign rootfiles wrappers`() {
        val path = BookOrbitEpubPackage.parseContainer(
            """<c:container xmlns:c="urn:oasis:names:tc:opendocument:xmlns:container"
                  xmlns:x="urn:other">
               <x:rootfiles><x:rootfile full-path="OPS/decoy.opf"
                   media-type="application/oebps-package+xml"/></x:rootfiles>
               <c:rootfiles><c:rootfile full-path="OPS/real.opf"
                   media-type="application/oebps-package+xml"/></c:rootfiles>
               </c:container>""".toByteArray(),
        )

        assertEquals("OPS/real.opf", path)
    }

    @Test
    fun `rejects duplicate manifest ids and missing spine items`() {
        listOf(
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="a.xhtml"/><item id="one" href="b.xhtml"/></manifest>
               <spine><itemref idref="one"/></spine></package>""",
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="one.xhtml"/></manifest>
               <spine><itemref idref="other"/></spine></package>""",
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="/one.xhtml"/></manifest>
               <spine><itemref idref="one"/></spine></package>""",
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="../../one.xhtml"/></manifest>
               <spine><itemref idref="one"/></spine></package>""",
        ).forEach { opf ->
            assertThrows(opf, BookOrbitEpubPackage.ParseException::class.java) {
                BookOrbitEpubPackage.parsePackage("OPS/package.opf", opf.toByteArray())
            }
        }
    }

    @Test
    fun `does not expand external entities`() {
        val secret = folder.newFile("secret.txt").apply { writeText("leaked") }
        val error = assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
            BookOrbitEpubPackage.parsePackage(
                "OPS/package.opf",
                """<!DOCTYPE package [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
                   <package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="a&xxe;.xhtml"/></manifest>
                   <spine><itemref idref="one"/></spine></package>""".toByteArray(),
            )
        }
        assertTrue(error.message!!.contains("document type"))
    }

    @Test
    fun `accepts bare document types for package and container`() {
        val opf = """<!DOCTYPE package>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
            <manifest><item id="one" href="one.xhtml"/></manifest>
            <spine><itemref idref="one"/></spine></package>"""
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
            val parsed = BookOrbitEpubPackage.parsePackage("OPS/package.opf", opf.toByteArray(charset))
            assertEquals(listOf("OPS/one.xhtml"), parsed.spine.map { it.href })
            assertEquals(
                "OPS/package.opf",
                BookOrbitEpubPackage.parseContainer(
                    ("<!DOCTYPE container>" + container("OPS/package.opf")).toByteArray(charset),
                ),
            )
        }
    }

    @Test
    fun `rejects external identifiers and internal declarations before dom parsing`() {
        listOf(
            """<!DOCTYPE container SYSTEM "file:///not-a-book.dtd">""",
            """<!DOCTYPE container PUBLIC "-//test//DTD" "file:///not-a-book.dtd">""",
            """<!DOCTYPE container [<!ENTITY local "replacement">]>""",
            """<!DOCTYPE container [<!ENTITY % external SYSTEM "file:///not-a-book.dtd">%external;]>""",
        ).forEach { declaration ->
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
                val error = assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
                    BookOrbitEpubPackage.parseContainer(
                        (declaration + container("OPS/package.opf")).toByteArray(charset),
                    )
                }
                assertTrue(error.message!!.contains("document type"))
            }
        }
    }

    @Test
    fun `rejects malformed package and container instead of repairing them`() {
        val badPackage = """<package xmlns="http://www.idpf.org/2007/opf">
            <manifest><item id="one" href="one.xhtml"/></manifest>
            <spine><itemref idref="one"/></spine>""".toByteArray()
        val badContainer = container("OPS/package.opf").removeSuffix("</container>").toByteArray()

        assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
            BookOrbitEpubPackage.parsePackage("OPS/package.opf", badPackage)
        }
        assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
            BookOrbitEpubPackage.parseContainer(badContainer)
        }
    }

    @Test
    fun `refuses an oversized package document`() {
        val epub = zip(
            "META-INF/container.xml" to container("package.opf"),
            "package.opf" to "<package>" + "x".repeat(5 * 1024 * 1024),
        )

        val error = assertThrows(BookOrbitEpubPackage.ParseException::class.java) { BookOrbitEpubPackage.parse(epub) }

        assertTrue(error.message!!.contains("too large"))
    }

    @Test
    fun `byte array entry points enforce the same xml size limit`() {
        val padding = " ".repeat(4 * 1024 * 1024)
        val oversizedPackage = """<package xmlns="http://www.idpf.org/2007/opf">
            <manifest/><spine/>$padding</package>""".toByteArray()
        val oversizedContainer = (container("OPS/package.opf") + padding).toByteArray()
        val packageError = assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
            BookOrbitEpubPackage.parsePackage("OPS/package.opf", oversizedPackage)
        }
        val containerError = assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
            BookOrbitEpubPackage.parseContainer(oversizedContainer)
        }
        assertTrue(packageError.message!!.contains("too large"))
        assertTrue(containerError.message!!.contains("too large"))
    }

    @Test
    fun `decodes escapes beside non-bmp characters`() {
        val parsed = BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="😀%20caf%C3%A9.xhtml"/></manifest>
               <spine><itemref idref="one"/></spine></package>""".toByteArray(),
        )

        assertEquals("OPS/😀 café.xhtml", parsed.manifest.getValue("one").href)
    }

    @Test
    fun `refuses escapes that are not utf-8`() {
        assertThrows(BookOrbitEpubPackage.ParseException::class.java) {
            BookOrbitEpubPackage.parsePackage(
                "OPS/package.opf",
                """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="one" href="caf%E9.xhtml"/></manifest>
                   <spine><itemref idref="one"/></spine></package>""".toByteArray(),
            )
        }
    }

    private fun container(path: String) =
        """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
           <rootfiles><rootfile full-path="$path" media-type="application/oebps-package+xml"/></rootfiles>
           </container>"""

    private fun zip(vararg entries: Pair<String, String>): File =
        folder.newFile("book.epub").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.trimIndent().toByteArray())
                    zip.closeEntry()
                }
            }
        }
}
