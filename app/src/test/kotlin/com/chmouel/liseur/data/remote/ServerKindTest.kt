package com.chmouel.liseur.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spellings written into the database.
 *
 * `books.url` and `remote_server.kind` are as good as schema: a book's
 * URL is what reading positions and annotations hang off, and the kind
 * decides how a stored account is read back. Renaming either orphans
 * every row that mentions it, on every phone, silently.
 */
class ServerKindTest {

    @Test
    fun `stored names and URL prefixes are stable`() {
        assertEquals("calibre", ServerKind.CALIBRE.urlPrefix)
        assertEquals("komga", ServerKind.KOMGA.urlPrefix)
        assertEquals("liseur-sync", ServerKind.LISEUR_SYNC.urlPrefix)
        assertEquals(
            listOf("CALIBRE", "KOMGA", "LISEUR_SYNC"),
            ServerKind.entries.map { it.name },
        )
    }

    @Test
    fun `a book's remote id round-trips through its URL`() {
        assertEquals("liseur-sync:b-1", ServerKind.LISEUR_SYNC.remoteUrl("b-1"))
        assertEquals("b-1", ServerKind.LISEUR_SYNC.remoteId("liseur-sync:b-1"))
        assertEquals(null, ServerKind.LISEUR_SYNC.remoteId("komga:b-1"))
    }

    @Test
    fun `a row from before a kind existed still reads as calibre`() {
        assertEquals(ServerKind.CALIBRE, ServerKind.fromStored("SOMETHING_FROM_THE_FUTURE"))
        assertEquals(ServerKind.LISEUR_SYNC, ServerKind.fromStored("LISEUR_SYNC"))
    }
}
