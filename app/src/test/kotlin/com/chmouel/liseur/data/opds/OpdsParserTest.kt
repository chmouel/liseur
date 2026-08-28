package com.chmouel.liseur.data.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException

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

        assertEquals("3121b620-0edc-4178-b435-8ef66e069f9b", book.entryId)
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
        refusedAsDoctype(
            """
            <?xml version="1.0"?>
            <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>&secret;</title></entry>
            </feed>
            """.trimIndent(),
        )
    }

    /**
     * A comment ends at `-->`, not at the first `>` inside it. A scan
     * that stops at the latter carries on reading the comment's text as
     * markup, takes the `<a` in it for the root element, and calls the
     * prolog finished before the DOCTYPE on the next line. That is how
     * a feed hides one in plain sight.
     */
    @Test
    fun `a doctype behind a comment carrying a bracket is still refused`() {
        refusedAsDoctype(
            """
            <!-- > <a -->
            <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>&secret;</title></entry>
            </feed>
            """.trimIndent(),
        )
    }

    /**
     * The same trick through a processing instruction, whose body may
     * hold anything but `?>` and so may hold both a `>` and a `<`.
     */
    @Test
    fun `a doctype behind a processing instruction carrying a bracket is still refused`() {
        refusedAsDoctype(
            """
            <?liseur > <a ?>
            <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>&secret;</title></entry>
            </feed>
            """.trimIndent(),
        )
    }

    /**
     * CDATA does not belong in a prolog, so this feed is malformed
     * whatever we do. It is skipped to `]]>` all the same, because
     * nothing opening `<` gets to end early.
     */
    @Test
    fun `a doctype behind a cdata section carrying a bracket is still refused`() {
        refusedAsDoctype(
            """
            <![CDATA[ > <a ]]>
            <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>&secret;</title></entry>
            </feed>
            """.trimIndent(),
        )
    }

    /**
     * A prolog we cannot read to the end is a prolog we cannot vouch
     * for. Such a feed will not parse either, so refusing it costs
     * nothing and leaves the scan with no way to accept what it failed
     * to make sense of.
     */
    @Test
    fun `a comment that never closes is refused`() {
        refusedAsMalformed(
            """
            <!-- <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/hostname">]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>x</title></entry>
            </feed>
            """.trimIndent(),
        )
    }

    /**
     * XML wants whitespace after the name, so this declares nothing. It
     * is refused for being malformed rather than for declaring a
     * document type, which is a different thing to tell the user.
     */
    @Test
    fun `a doctype lookalike with no space after the name is refused as malformed`() {
        refusedAsMalformed(
            """
            <!DOCTYPEfoo>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:1</id><title>x</title></entry>
            </feed>
            """.trimIndent(),
        )
    }

    /**
     * Skipping a comment whole is what keeps the check from firing on a
     * feed that declares nothing. A server admin's note about
     * hand-written HTML is prose, and a scan that searched the prolog's
     * text instead of walking it would refuse this feed outright.
     */
    @Test
    fun `a comment that quotes a doctype is not a feed that declares one`() {
        val page = OpdsParser.parse(
            """
            <!-- the admin's note on writing <!DOCTYPE html> by hand -->
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:abc</id><title>Moby Dick</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.entryId })
    }

    @Test
    fun `a processing instruction that quotes a doctype is not a feed that declares one`() {
        val page = OpdsParser.parse(
            """
            <?liseur note="<!DOCTYPE html>" ?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:abc</id><title>Moby Dick</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.entryId })
    }

    /** The adversarial characters, with nothing adversarial behind them. */
    @Test
    fun `a comment carrying a bracket does not stop an honest feed parsing`() {
        val page = OpdsParser.parse(
            """
            <!-- > <a -->
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:abc</id><title>Moby Dick</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.entryId })
    }

    @Test
    fun `a processing instruction carrying a bracket does not stop an honest feed parsing`() {
        val page = OpdsParser.parse(
            """
            <?liseur > <a ?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:abc</id><title>Moby Dick</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.entryId })
    }

    /**
     * An empty comment and a bodyless instruction pin where the search
     * for a terminator starts. Begin it one character too early and
     * `<!---->` closes on its own opening dashes; begin it too late and
     * neither closes at all.
     */
    @Test
    fun `an empty comment and a bodyless instruction are read to their ends`() {
        val page = OpdsParser.parse(
            """
            <!----><?p?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:uuid:abc</id><title>Moby Dick</title></entry>
            </feed>
            """.trimIndent(),
        )

        assertEquals(listOf("abc"), page.books.map { it.entryId })
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

        assertEquals(listOf("abc"), page.books.map { it.entryId })
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

        assertEquals(listOf("abc"), page.books.map { it.entryId })
    }

    private fun refusedAsDoctype(feed: String) =
        refused(feed, "the feed declares a document type, which OPDS never needs")

    private fun refusedAsMalformed(feed: String) =
        refused(feed, "the feed's prolog is not well-formed XML")

    /**
     * Asserts the refusal came from the parser's own scan of the prolog.
     *
     * A `SAXException` on its own proves nothing. Xerces honours
     * `disallow-doctype-decl` on the JVM, so even a scan that missed the
     * DOCTYPE entirely ends in one, thrown by the factory switch this
     * check exists precisely not to depend on, and on Android possibly
     * not thrown at all. The message is what tells the two layers apart.
     */
    private fun refused(feed: String, message: String) {
        try {
            OpdsParser.parse(feed)
        } catch (e: SAXException) {
            assertEquals(message, e.message)
            return
        }
        fail("the parser accepted a feed it should have refused")
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
    fun `an unnumbered series is still a series`() {
        val book = entryWithContent("SERIES: Dune<br/>")

        assertEquals("Dune", book.seriesName)
        assertNull(book.seriesIndex)
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
