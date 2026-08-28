package com.chmouel.liseur.data.komga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Komga's book DTO, against a payload captured from a real server.
 *
 * Written from what a running Komga actually sent rather than from the
 * schema: the schema says a field exists, not what turns up in it.
 */
class KomgaBooksTest {

    @Test
    fun `a real book page is read`() {
        val page = KomgaBooks.parsePage(JSONObject(REAL_PAGE))

        assertTrue(page.last)
        assertEquals(1, page.books.size)

        val book = page.books.single().book
        assertEquals("0R57273D2Z517", book.remoteId)
        assertEquals("Guide de conversation Italien pour les Nuls, 4e édition", book.title)
        assertEquals("Francesca ONOFRI", book.author)
        assertEquals("/api/v1/books/0R57273D2Z517/file", book.downloadHref)
        assertEquals("/api/v1/books/0R57273D2Z517/thumbnail", book.coverHref)
        assertEquals(537508L, book.sizeBytes)
        assertEquals(179, book.pageCount)
        // 2026-07-30T07:33:03Z
        assertEquals(1785396783000L, book.updatedAt)
    }

    @Test
    fun `a book carries the series it belongs to`() {
        val book = KomgaBooks.parsePage(JSONObject(REAL_PAGE)).books.single().book

        assertEquals("0R57273CEZ4HD", book.seriesId)
        assertEquals(1.0, book.seriesIndex!!, 0.0)
    }

    @Test
    fun `the sort number is what places a volume, not the shown one`() {
        // Komga orders by numberSort; `number` is a label and `number`
        // on the DTO itself is only where the file sits in its folder.
        val book = bookWith(
            """"seriesTitle": "Sandman", "number": 99,
               "metadata": {"title": "Annual", "number": "Annual 2023", "numberSort": 4.5}""",
        )

        assertEquals("Sandman", book.seriesName)
        assertEquals(4.5, book.seriesIndex!!, 0.0)
    }

    @Test
    fun `a label that is not a number leaves the volume unplaced`() {
        val book = bookWith(
            """"seriesTitle": "Sandman",
               "metadata": {"title": "Annual", "number": "Annual 2023"}""",
        )

        assertEquals("Sandman", book.seriesName)
        assertNull(book.seriesIndex)
    }

    @Test
    fun `a shown number is used when there is no sort number`() {
        val book = bookWith(
            """"seriesTitle": "Sandman", "metadata": {"title": "Two", "number": "2"}""",
        )

        assertEquals(2.0, book.seriesIndex!!, 0.0)
    }

    @Test
    fun `a one-shot is not a series of one`() {
        val book = bookWith(
            """"seriesId": "s-1", "seriesTitle": "Ulysses", "oneshot": true,
               "metadata": {"title": "Ulysses", "numberSort": 1}""",
        )

        assertNull(book.seriesName)
        assertNull(book.seriesId)
        assertNull(book.seriesIndex)
    }

    @Test
    fun `a server that says nothing about a series is not made to`() {
        val book = bookWith(""""metadata": {"title": "Alone"}""")

        assertNull(book.seriesName)
        assertNull(book.seriesId)
        assertNull(book.seriesIndex)
    }

    private fun bookWith(fields: String) = KomgaBooks.parsePage(
        JSONObject(
            """{"content":[{"id":"b1","name":"file.epub",$fields}],"last":true}""",
        ),
    ).books.single().book

    @Test
    fun `a book nobody has opened has no progress`() {
        assertNull(KomgaBooks.parsePage(JSONObject(REAL_PAGE)).books.single().progress)
    }

    @Test
    fun `reading progress is read as the server writes it`() {
        val progress = KomgaBooks.parseProgress(
            JSONObject(
                """
                {"page":179,"completed":true,"readDate":"2026-07-30T08:07:58Z",
                 "created":"2026-07-30T08:07:58Z","lastModified":"2026-07-30T08:07:58Z",
                 "deviceId":"phone-1","deviceName":"Liseur"}
                """.trimIndent(),
            ),
        )

        assertEquals(179, progress.page)
        assertTrue(progress.completed)
        assertEquals("phone-1", progress.deviceId)
        assertEquals(1785398878000L, progress.readDate)
    }

    @Test
    fun `the writer is credited rather than whoever else worked on the book`() {
        val book = KomgaBooks.parseBook(
            JSONObject(
                """
                {"id":"b1","name":"file.epub","metadata":{"title":"Ulysses","authors":[
                   {"name":"A Cover Artist","role":"cover"},
                   {"name":"James Joyce","role":"writer"}]}}
                """.trimIndent(),
            ),
        )!!.book

        assertEquals("James Joyce", book.author)
    }

    @Test
    fun `a book with no metadata title falls back to its file name`() {
        val book = KomgaBooks.parseBook(JSONObject("""{"id":"b1","name":"file.epub"}"""))!!.book

        assertEquals("file.epub", book.title)
        assertNull(book.author)
        assertNull(book.pageCount)
        assertNull(book.sizeBytes)
    }

    private companion object {
        /** Trimmed from a real `POST /api/v1/books/list` answer. */
        val REAL_PAGE = """
        {
          "content": [
            {
              "id": "0R57273D2Z517",
              "seriesId": "0R57273CEZ4HD",
              "libraryId": "0R572732PZ2KD",
              "name": "Guide de conversation Italien pour les Nul - Francesca ONOFRI",
              "url": "/data/Ebooks/Calibre/x.epub",
              "number": 1,
              "created": "2026-07-30T07:31:50Z",
              "lastModified": "2026-07-30T07:33:03Z",
              "fileLastModified": "2026-07-18T07:28:41Z",
              "sizeBytes": 537508,
              "size": "524.9 KiB",
              "media": {
                "status": "READY",
                "mediaType": "application/epub+zip",
                "pagesCount": 179,
                "epubDivinaCompatible": false,
                "epubIsKepub": false,
                "mediaProfile": "EPUB"
              },
              "metadata": {
                "title": "Guide de conversation Italien pour les Nuls, 4e édition",
                "number": "1",
                "releaseDate": "2020-11-15",
                "authors": [{"name": "Francesca ONOFRI", "role": "writer"}],
                "tags": [],
                "links": []
              },
              "readProgress": null,
              "deleted": false,
              "oneshot": false
            }
          ],
          "totalElements": 69,
          "totalPages": 1,
          "last": true,
          "size": 200,
          "number": 0,
          "numberOfElements": 1
        }
        """.trimIndent()
    }
}
