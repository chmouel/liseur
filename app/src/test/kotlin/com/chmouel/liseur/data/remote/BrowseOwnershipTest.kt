package com.chmouel.liseur.data.remote

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which books a saved catalog treats as already in the library, so it
 * neither offers them again nor adds a second copy.
 */
class BrowseOwnershipTest {

    private val browse = server(id = 2L)
    private val sibling = server(id = 3L)
    private val stranger = server(id = 4L, username = "someone-else")

    @Test
    fun `saved catalogs signed in to the same account are siblings`() {
        assertEquals(setOf(2L, 3L), sameAccount(browse, listOf(browse, sibling, stranger)))
    }

    @Test
    fun `a catalog is its own sibling even before it is listed`() {
        assertEquals(setOf(2L), sameAccount(browse, emptyList()))
    }

    @Test
    fun `a book from a same-account catalog is already held`() {
        val siblings = sameAccount(browse, listOf(browse, sibling, stranger))

        assertTrue(book(browseServerId = 2L).belongsToBrowse(browse, null, siblings))
        assertTrue(book(browseServerId = 3L).belongsToBrowse(browse, null, siblings))
        assertFalse(book(browseServerId = 4L).belongsToBrowse(browse, null, siblings))
    }

    @Test
    fun `a book from the main connection is held only for the same account`() {
        val siblings = setOf(2L)

        assertTrue(book().belongsToBrowse(browse, server(id = RemoteServer.SINGLE_ID), siblings))
        assertFalse(
            book().belongsToBrowse(browse, server(id = RemoteServer.SINGLE_ID, username = "other"), siblings),
        )
        assertFalse(book(remoteUuid = null).belongsToBrowse(browse, server(id = RemoteServer.SINGLE_ID), siblings))
    }

    @Test
    fun `removing a catalog hands its books to a same-account catalog only`() {
        assertEquals(sibling, survivingSibling(browse, listOf(browse, sibling, stranger)))
        assertEquals(null, survivingSibling(browse, listOf(browse, stranger)))
    }

    @Test
    fun `a moved cover is tagged for its new catalog alone`() {
        val tagged = browseCoverUrl("https://books.example.com/cover/1", browse)
        val moved = rescopedBrowseCoverUrl(tagged, sibling)

        assertEquals(browseCoverUrl("https://books.example.com/cover/1", sibling), moved)
        assertEquals(
            browseCoverUrl("https://books.example.com/cover/1#page=2", sibling),
            rescopedBrowseCoverUrl(browseCoverUrl("https://books.example.com/cover/1#page=2", browse), sibling),
        )
    }

    private fun server(id: Long, username: String = "me") = RemoteServer(
        id = id,
        kind = ServerKind.CALIBRE,
        baseUrl = "https://books.example.com",
        username = username,
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )

    private fun book(browseServerId: Long? = null, remoteUuid: String? = "r1") = Book(
        url = "book:$browseServerId",
        title = "Book",
        author = null,
        coverPath = null,
        source = null,
        addedAt = 0L,
        lastOpenedAt = null,
        remoteUuid = remoteUuid,
        browseServerId = browseServerId,
    )
}
