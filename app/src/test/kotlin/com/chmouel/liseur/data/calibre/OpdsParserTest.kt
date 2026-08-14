package com.chmouel.liseur.data.calibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsParserTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <title>Calibre-Web</title>
          <link rel="self" href="/opds/books/letter/00?"/>
          <link rel="next" title="Next" href="/opds/books/letter/00?offset=60"
                type="application/atom+xml;profile=opds-catalog"/>
          <entry>
            <title>Le vieil homme et la mer</title>
            <id>urn:uuid:3121b620-0edc-4178-b435-8ef66e069f9b</id>
            <updated>2026-07-26T10:26:49+00:00</updated>
            <author><name>Ernest Hemingway</name></author>
            <link rel="http://opds-spec.org/image" href="/opds/cover/74"/>
            <link rel="http://opds-spec.org/image/thumbnail" href="/opds/cover/74"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/74/kepub/"
                  length="169069" title="KEPUB" type="application/epub+zip"/>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/74/epub/"
                  length="156172" title="EPUB" type="application/epub+zip"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `reads a book out of the feed`() {
        val book = OpdsParser.parse(feed).books.single()

        assertEquals("3121b620-0edc-4178-b435-8ef66e069f9b", book.uuid)
        assertEquals("Le vieil homme et la mer", book.title)
        assertEquals("Ernest Hemingway", book.author)
        assertEquals(74, book.bookId)
        assertEquals("/opds/cover/74", book.coverHref)
        assertTrue(book.updatedAt!! > 0)
    }

    @Test
    fun `prefers the plain epub over the kepub`() {
        // Both are offered once the server has kepubify set up, and the
        // plain EPUB is the smaller download.
        val book = OpdsParser.parse(feed).books.single()

        assertEquals("/opds/download/74/epub/", book.downloadHref)
        assertEquals(156172L, book.sizeBytes)
    }

    @Test
    fun `follows the link to the next page`() {
        assertEquals("/opds/books/letter/00?offset=60", OpdsParser.parse(feed).nextHref)
    }

    @Test
    fun `stops when there is no next page`() {
        val lastPage = feed.replace(Regex("""<link rel="next".*?/>""", RegexOption.DOT_MATCHES_ALL), "")
        assertNull(OpdsParser.parse(lastPage).nextHref)
    }

    @Test
    fun `skips a navigation entry that is not a book`() {
        val navigation = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Authors</title>
                <id>/opds/author</id>
                <link rel="subsection" href="/opds/author"/>
              </entry>
            </feed>
        """.trimIndent()

        assertTrue(OpdsParser.parse(navigation).books.isEmpty())
    }

    @Test
    fun `keeps a book that has no download link so it can still be seen`() {
        val noDownload = feed.replace("http://opds-spec.org/acquisition", "related")
        val book = OpdsParser.parse(noDownload).books.single()

        assertNull(book.downloadHref)
        assertEquals(74, book.bookId)
    }

    /**
     * A feed is not trusted just because an account is connected to the
     * server that sent it. A DOCTYPE is the way in: an entity declared
     * there can name a local file, whose contents would then be parsed
     * into the document, or a host on whatever network the phone is on,
     * which we would go and fetch. OPDS needs none of it, so none of it
     * is allowed.
     */
    @Test
    fun `a feed that declares entities is refused rather than resolved`() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>&secret;</title></entry>
            </feed>
        """.trimIndent()

        try {
            OpdsParser.parse(hostile)
            fail("the parser accepted a document that declared an external entity")
        } catch (e: org.xml.sax.SAXException) {
            // Refused outright, which upstream turns into "your server
            // answered with something unexpected".
        }
    }

    @Test
    fun `an ordinary feed still parses`() {
        val page = OpdsParser.parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:abc</id><title>Moby Dick</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.uuid })
    }

    /** Only the prolog is looked at, so a book about XML still shows up. */
    @Test
    fun `a title that merely mentions a doctype is not refused`() {
        val page = OpdsParser.parse(
            """
            <?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>urn:uuid:abc</id>
                <title>Writing &lt;!DOCTYPE html&gt; by hand</title>
              </entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.uuid })
    }

    /**
     * calibre-web writes the series into the human-readable block, as a
     * line of prose between the rating and the tags, exactly as
     * `cps/templates/feed.xml` lays it out.
     */
    private fun entryWithContent(content: String) = OpdsParser.parse(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <title>The Great Hunt</title>
            <id>urn:uuid:aaaabbbb-0000-1111-2222-333344445555</id>
            <link rel="http://opds-spec.org/acquisition" href="/opds/download/74/epub/"
                  length="1" type="application/epub+zip"/>
            <content type="xhtml">
              <div xmlns="http://www.w3.org/1999/xhtml">CONTENT</div>
            </content>
          </entry>
        </feed>
        """.trimIndent().replace("CONTENT", content),
    ).books.single()

    @Test
    fun `the series line names the series and places the book in it`() {
        val book = entryWithContent(
            """RATING: 4<br/>SERIES: Wheel of Time [2.00]<br/>TAGS: fantasy<br/>""",
        )

        assertEquals("Wheel of Time", book.seriesName)
        assertEquals(2.0, book.seriesIndex!!, 0.0)
    }

    @Test
    fun `a padded whole number is still a whole number`() {
        assertEquals(1.0, entryWithContent("SERIES: Dune [1.00]<br/>").seriesIndex!!, 0.0)
    }

    @Test
    fun `a novella between two volumes keeps its half`() {
        assertEquals(7.5, entryWithContent("SERIES: Dune [7.50]<br/>").seriesIndex!!, 0.0)
    }

    @Test
    fun `a comma is a decimal point on a French server`() {
        assertEquals(7.5, entryWithContent("SERIES: Dune [7,50]<br/>").seriesIndex!!, 0.0)
    }

    @Test
    fun `a series whose own name has brackets keeps them`() {
        val book = entryWithContent("SERIES: Foundation [Expanded] [3.00]<br/>")

        assertEquals("Foundation [Expanded]", book.seriesName)
        assertEquals(3.0, book.seriesIndex!!, 0.0)
    }

    @Test
    fun `a book in no series says so`() {
        val book = entryWithContent("RATING: 4<br/>TAGS: fantasy<br/>")

        assertNull(book.seriesName)
        assertNull(book.seriesIndex)
    }

    @Test
    fun `an entry with no content block is not an error`() {
        assertNull(OpdsParser.parse(feed).books.single().seriesName)
    }

    @Test
    fun `a custom column that merely mentions a series is not one`() {
        // Custom columns are written to the same block in the same
        // shape, so the label has to be the real one.
        val book = entryWithContent("MY SERIES OF NOTE: Wheel of Time<br/>")

        assertNull(book.seriesName)
    }

    @Test
    fun `a description quoting a series line is not read as one`() {
        // The description is a paragraph inside the same block; the
        // prose lines are not.
        val book = entryWithContent(
            """SERIES: Wheel of Time [2.00]<br/><p>SERIES: Some Other Thing [9.00]</p>""",
        )

        assertEquals("Wheel of Time", book.seriesName)
        assertEquals(2.0, book.seriesIndex!!, 0.0)
    }

    @Test
    fun `only the description mentioning a series leaves the book out of one`() {
        val book = entryWithContent("""<p>SERIES: Some Other Thing [9.00]</p>""")

        assertNull(book.seriesName)
    }
}
