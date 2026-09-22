package com.chmouel.liseur.data.bookorbit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chmouel.liseur.data.remote.RemoteHttpFailure
import com.chmouel.liseur.data.remote.SyncFailure

/**
 * Reading BookOrbit's book DTOs.
 *
 * The payloads are trimmed captures from a real server, including the
 * parts this app must never mistake for a book: the cover image and the
 * OPF sidecar are files on the same record as the EPUB.
 */
class BookOrbitBooksTest {

    private fun card(json: String): BookOrbitCard =
        BookOrbitBooks.parseCard(JSONObject(json))!!

    @Test
    fun `a card is read as the shelf needs it`() {
        val parsed = card(
            """
            {"id":113,"title":"La Horde du Contrevent","authors":["Damasio,Alain"],
             "files":[{"id":352,"format":"epub","role":"primary","sizeBytes":1925282}],
             "readingProgress":3.15641,
             "readStatus":{"status":"reading","source":"auto","startedAt":"2026-09-20T00:00:00.000Z"},
             "seriesId":null,"seriesName":null,"seriesIndex":null,
             "addedAt":"2026-09-20T18:41:06.146Z","updatedAt":"2026-09-20T18:41:45.761Z",
             "pageCount":null,"language":"fr","publisher":"AlexandriZ",
             "isbn13":"9782070342266","hasCover":true}
            """.trimIndent(),
        )

        assertEquals(113L, parsed.bookId)
        assertEquals("La Horde du Contrevent", parsed.title)
        assertEquals("Damasio,Alain", parsed.author)
        assertEquals(3.15641, parsed.percentage!!, 1e-9)
        assertEquals("reading", parsed.readStatus!!.status)
        assertEquals("fr", parsed.language)
        assertTrue(parsed.hasCover)
    }

    @Test
    fun `the cover and the sidecar are never the book`() {
        // The live capture orders them exactly this way: the JPEG first.
        val parsed = card(
            """
            {"id":113,"title":"A book","authors":[],
             "files":[{"id":351,"format":"jpg","role":"cover","sizeBytes":52679},
                      {"id":352,"format":"epub","role":"primary","sizeBytes":1925282},
                      {"id":353,"format":"opf","role":"metadata","sizeBytes":2304}]}
            """.trimIndent(),
        )

        assertEquals(352L, BookOrbitBooks.chosenFile(parsed)!!.id)
    }

    @Test
    fun `a primary epub wins over one that is merely first`() {
        val parsed = card(
            """
            {"id":1,"title":"Two editions","authors":[],
             "files":[{"id":10,"format":"epub","role":"secondary","sizeBytes":1},
                      {"id":11,"format":"epub","role":"primary","sizeBytes":2}]}
            """.trimIndent(),
        )

        assertEquals(11L, BookOrbitBooks.chosenFile(parsed)!!.id)
    }

    @Test
    fun `a book with no epub has no file to read`() {
        val parsed = card(
            """
            {"id":9,"title":"An audiobook","authors":[],
             "files":[{"id":1,"format":"m4b","role":"primary","sizeBytes":100}]}
            """.trimIndent(),
        )

        assertNull(BookOrbitBooks.chosenFile(parsed))
    }

    @Test
    fun `a book with no title is still something the shelf can name`() {
        assertEquals("#7", card("""{"id":7,"authors":[]}""").title)
    }

    /**
     * BookOrbit has spelled the series index as a number and as a string
     * at different points, and its own detail type calls it a string.
     */
    @Test
    fun `a series index is read however it is spelled`() {
        assertEquals(3.0, card("""{"id":1,"title":"t","seriesIndex":3}""").seriesIndex!!, 1e-9)
        assertEquals(3.5, card("""{"id":1,"title":"t","seriesIndex":"3.5"}""").seriesIndex!!, 1e-9)
        assertNull(card("""{"id":1,"title":"t","seriesIndex":"Annual"}""").seriesIndex)
    }

    @Test
    fun `a page carries its size and total`() {
        val page = BookOrbitBooks.parsePage(
            JSONObject(
                """
                {"items":[{"id":1,"title":"a","authors":[]}],"total":110,"page":0,"size":1}
                """.trimIndent(),
            ),
        )

        assertEquals(110, page.total)
        assertEquals(0, page.page)
        assertEquals(1, page.cards.size)
    }

    @Test
    fun `a card with no id is dropped rather than guessed at`() {
        assertNull(BookOrbitBooks.parseCard(JSONObject("""{"title":"no id"}""")))
    }

    @Test
    fun `a response without the page envelope is a failed catalog`() {
        val failure = runCatching {
            BookOrbitBooks.parsePage(JSONObject("""{"error":"temporary failure"}"""))
        }.exceptionOrNull()

        assertTrue(failure is RemoteHttpFailure)
        assertEquals(SyncFailure.Malformed, (failure as RemoteHttpFailure).reason)
    }

    @Test
    fun `an empty shelf is valid only with a complete envelope`() {
        val page = BookOrbitBooks.parsePage(
            JSONObject("""{"items":[],"total":0,"page":0,"size":200}"""),
        )

        assertTrue(page.cards.isEmpty())
        assertEquals(0, page.total)
    }
}
