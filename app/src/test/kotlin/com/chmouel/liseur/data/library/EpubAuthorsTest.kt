@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.xml.XmlParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EpubAuthorsTest {
    @Test
    fun `alternate scripts never replace the primary names`() {
        for (language in listOf("", "xml:lang=\"ar\"")) {
            assertEquals(listOf("Abu al-ʻAlaʼ al-Maʻarri"), authors("""
                <dc:creator id="author">Abu al-ʻAlaʼ al-Maʻarri</dc:creator>
                <meta property="alternate-script" refines="#author" $language>أبو العلاء المعري</meta>
                <meta property="alternate-script" refines="#author">Another name</meta>
            """))
        }
    }

    @Test
    fun `authors retain their order and original scripts`() {
        assertEquals(listOf("作者", "Second author"), authors("""
            <dc:creator>作者</dc:creator>
            <dc:creator> Second author </dc:creator>
            <dc:creator> </dc:creator>
        """, count = 2))
    }

    @Test
    fun `EPUB 2 and 3 author contributors are included but translators are not`() {
        assertEquals(listOf("Creator", "Legacy author", "Modern author"), authors("""
            <dc:creator>Creator</dc:creator>
            <dc:contributor opf:role="aut">Legacy author</dc:contributor>
            <dc:contributor id="modern">Modern author</dc:contributor>
            <meta property="role" refines="#modern" scheme="marc:relators">aut</meta>
            <dc:contributor id="translator">Translator</dc:contributor>
            <meta property="role" refines="#translator">trl</meta>
        """, count = 3))
    }

    @Test
    fun `legacy role takes precedence over refinements like Readium`() {
        assertEquals(emptyList<String>(), authors("""
            <dc:contributor id="person" opf:role="trl">Translator</dc:contributor>
            <meta property="role" refines="#person">aut</meta>
        """, count = 0))
    }

    @Test
    fun `namespace prefixes and role vocabulary aliases are supported`() {
        assertEquals(listOf("Author"), authors("""
            <different:contributor id="author">Author</different:contributor>
            <opf:meta property="pkg:role" refines="#author">aut</opf:meta>
        """, extraAttributes = """
            xmlns:different="http://purl.org/dc/elements/1.1/"
            prefix="pkg: http://idpf.org/epub/vocab/package/meta/#"
        """))
    }

    @Test
    fun `missing metadata and author count mismatches fall back`() {
        assertNull(authors("<dc:creator>Author</dc:creator>", count = 2))
        val document = XmlParser().parse("<package xmlns=\"http://www.idpf.org/2007/opf\"/>".byteInputStream())
        assertNull(primaryEpubAuthors(document, 0))
    }

    private fun authors(metadata: String, count: Int = 1, extraAttributes: String = ""): List<String>? {
        val document = XmlParser().parse("""
            <package xmlns="http://www.idpf.org/2007/opf" xml:lang="en-US"
              xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmlns:opf="http://www.idpf.org/2007/opf" $extraAttributes>
                <metadata>$metadata</metadata>
            </package>
        """.trimIndent().byteInputStream())
        return primaryEpubAuthors(document, count)
    }
}
