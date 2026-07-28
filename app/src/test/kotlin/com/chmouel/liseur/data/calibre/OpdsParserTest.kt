package com.chmouel.liseur.data.calibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
