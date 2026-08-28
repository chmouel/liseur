package com.chmouel.liseur.data.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser read as a server other than calibre-web would write to it.
 *
 * Everything here used to come out empty. `parseEntry` kept the entry
 * id only when it started `urn:uuid:`, which is calibre's habit and
 * nobody else's, so a catalog answering `<id>1</id>` — legal, common,
 * and what several servers actually do — produced a library with
 * nothing in it and no error to explain why.
 *
 * The calibre tests next door are the other half of this: the parser is
 * shared now, and a change made for a generic server that breaks
 * calibre-web browsing has no server in CI to catch it.
 */
class OpdsGenericFeedTest {

    private fun feed(entries: String, links: String = "") = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>A catalog</title>
          $links
          $entries
        </feed>
    """.trimIndent()

    private fun entry(id: String, links: String) = """
        <entry>
          <id>$id</id>
          <title>A book</title>
          $links
        </entry>
    """.trimIndent()

    private val acquisition =
        """<link rel="http://opds-spec.org/acquisition"
                 href="/get/1.epub" type="application/epub+zip"/>"""

    private fun idOf(id: String): String? =
        OpdsParser.parse(feed(entry(id, acquisition))).books.singleOrNull()?.entryId

    @Test
    fun `a numeric id is kept as it was written`() {
        assertEquals("1", idOf("1"))
    }

    @Test
    fun `a URL id is kept whole`() {
        assertEquals("https://books.example/entry/17", idOf("https://books.example/entry/17"))
    }

    @Test
    fun `an ISBN URN is kept whole`() {
        assertEquals("urn:isbn:9780441013593", idOf("urn:isbn:9780441013593"))
    }

    @Test
    fun `an arbitrary opaque id is kept whole`() {
        assertEquals("tag:example,2024:b/42", idOf("tag:example,2024:b/42"))
    }

    @Test
    fun `a calibre UUID still loses its prefix`() {
        // `books.url` is schema and calibre's rows are stored under the
        // bare UUID, so this one cannot start being kept whole.
        assertEquals("abc-123", idOf("urn:uuid:abc-123"))
    }

    @Test
    fun `an entry with no id at all is not a book`() {
        val xml = feed("<entry><title>Nameless</title>$acquisition</entry>")

        assertTrue(OpdsParser.parse(xml).books.isEmpty())
    }

    @Test
    fun `an open-access acquisition is a download like any other`() {
        val xml = feed(
            entry(
                "1",
                """<link rel="http://opds-spec.org/acquisition/open-access"
                         href="/free/1.epub" type="application/epub+zip"/>""",
            ),
        )

        assertEquals("/free/1.epub", OpdsParser.parse(xml).books.single().downloadHref)
    }

    @Test
    fun `a book offered for sale is not offered for download`() {
        // `buy`, `borrow`, `sample` and `subscribe` are acquisitions in
        // the specification's sense and files the reader does not have.
        val xml = feed(
            entry(
                "1",
                """<link rel="http://opds-spec.org/acquisition/buy"
                         href="/buy/1" type="application/epub+zip"/>""",
            ),
        )

        assertNull(OpdsParser.parse(xml).books.single().downloadHref)
    }

    @Test
    fun `several formats resolve to the one Readium can open`() {
        val xml = feed(
            entry(
                "1",
                """
                <link rel="http://opds-spec.org/acquisition" href="/get/1.pdf"
                      type="application/pdf"/>
                <link rel="http://opds-spec.org/acquisition" href="/get/1.cbz"
                      type="application/vnd.comicbook+zip"/>
                <link rel="http://opds-spec.org/acquisition" href="/get/1.epub"
                      type="application/epub+zip"/>
                <link rel="http://opds-spec.org/acquisition" href="/get/1.m4b"
                      type="audio/mp4"/>
                """.trimIndent(),
            ),
        )

        assertEquals("/get/1.epub", OpdsParser.parse(xml).books.single().downloadHref)
    }

    @Test
    fun `an entry with no readable format is listed without a download`() {
        // Dropping it would leave the reader hunting for a book the
        // server does show. Offering the PDF would promise a file that
        // will not open.
        val xml = feed(
            entry(
                "1",
                """<link rel="http://opds-spec.org/acquisition" href="/get/1.pdf"
                         type="application/pdf"/>""",
            ),
        )
        val book = OpdsParser.parse(xml).books.single()

        assertEquals("A book", book.title)
        assertNull(book.downloadHref)
    }

    @Test
    fun `a DRM-protected EPUB is not offered`() {
        val xml = feed(
            entry(
                "1",
                """<link rel="http://opds-spec.org/acquisition" href="/get/1.lcpl"
                         type="application/epub+zip;lcp"/>""",
            ),
        )

        assertNull(OpdsParser.parse(xml).books.single().downloadHref)
    }

    @Test
    fun `an acquisition that says nothing about its type is taken at its word`() {
        // Plenty of servers omit it, and refusing them all empties those
        // libraries.
        val xml = feed(entry("1", """<link rel="http://opds-spec.org/acquisition" href="/get/1"/>"""))

        assertEquals("/get/1", OpdsParser.parse(xml).books.single().downloadHref)
    }

    @Test
    fun `several relations on one link still name an acquisition`() {
        val xml = feed(
            entry(
                "1",
                """<link rel="alternate http://opds-spec.org/acquisition" href="/get/1.epub"
                         type="application/epub+zip"/>""",
            ),
        )

        assertEquals("/get/1.epub", OpdsParser.parse(xml).books.single().downloadHref)
    }

    @Test
    fun `a shelf is somewhere to walk, not a book`() {
        val xml = feed(
            """
            <entry>
              <id>nav-1</id>
              <title>Fantasy</title>
              <link rel="subsection" href="/opds/fantasy"
                    type="application/atom+xml;profile=opds-catalog"/>
            </entry>
            """.trimIndent(),
        )
        val page = OpdsParser.parse(xml)

        assertTrue(page.books.isEmpty())
        assertEquals("/opds/fantasy", page.navigation.single().href)
        assertEquals("Fantasy", page.navigation.single().title)
    }

    @Test
    fun `a book linking to its series is still a book`() {
        // Some servers give a publication a `collection` link to the
        // series it belongs to. Reading that as a shelf loses the book
        // and sends the walk round in a circle.
        val xml = feed(
            entry(
                "1",
                """
                <link rel="collection" href="/opds/series/dune"
                      type="application/atom+xml;profile=opds-catalog"/>
                $acquisition
                """.trimIndent(),
            ),
        )
        val page = OpdsParser.parse(xml)

        assertEquals("1", page.books.single().entryId)
        assertTrue(page.navigation.isEmpty())
    }

    @Test
    fun `the next page of a feed is read off the feed, not guessed`() {
        val xml = feed(
            entry("1", acquisition),
            links = """<link rel="next" href="/opds/all?page=2"
                             type="application/atom+xml;profile=opds-catalog"/>""",
        )

        assertEquals("/opds/all?page=2", OpdsParser.parse(xml).nextHref)
    }

    @Test
    fun `a feed's own base is carried out of the parser`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://cdn.example/opds/">
              <title>A catalog</title>
              ${entry("1", acquisition)}
            </feed>
        """.trimIndent()

        assertEquals("https://cdn.example/opds/", OpdsParser.parse(xml).xmlBase)
    }

    @Test
    fun `an entry may set a base of its own`() {
        val xml = feed(
            """
            <entry xml:base="https://files.example/">
              <id>1</id>
              <title>A book</title>
              $acquisition
            </entry>
            """.trimIndent(),
        )

        assertEquals("https://files.example/", OpdsParser.parse(xml).books.single().xmlBase)
    }
}
