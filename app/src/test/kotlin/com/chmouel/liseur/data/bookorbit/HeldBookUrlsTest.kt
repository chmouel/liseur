package com.chmouel.liseur.data.bookorbit

import com.chmouel.liseur.data.db.Book
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which URL a BookOrbit binding is written under.
 *
 * A book downloaded from a saved catalog keeps its `browse:` URL when
 * the same account becomes the main library, so its binding has to be
 * written there too, or position sync skips it until the next refresh.
 */
class HeldBookUrlsTest {

    @Test
    fun `a promotable saved catalog row lends its URL`() {
        val rows = listOf(book("browse:2:bookorbit:r1", "r1", browseServerId = 2L))

        assertEquals(mapOf("r1" to "browse:2:bookorbit:r1"), heldBookUrls(rows))
    }

    @Test
    fun `a main row wins over a saved catalog row`() {
        val rows = listOf(
            book("browse:2:bookorbit:r1", "r1", browseServerId = 2L),
            book("file:///books/r1.epub", "r1"),
        )

        assertEquals(mapOf("r1" to "file:///books/r1.epub"), heldBookUrls(rows))
    }

    @Test
    fun `duplicates of the same kind are left alone`() {
        val rows = listOf(
            book("file:///books/a.epub", "r1"),
            book("file:///books/b.epub", "r1"),
            book("browse:2:bookorbit:r2", "r2", browseServerId = 2L),
            book("browse:3:bookorbit:r2", "r2", browseServerId = 3L),
        )

        assertEquals(emptyMap<String, String>(), heldBookUrls(rows))
    }

    private fun book(url: String, remoteUuid: String, browseServerId: Long? = null) = Book(
        url = url,
        title = url,
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0L,
        lastOpenedAt = null,
        remoteUuid = remoteUuid,
        browseServerId = browseServerId,
    )
}
