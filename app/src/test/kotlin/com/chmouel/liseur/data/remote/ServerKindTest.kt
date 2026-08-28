package com.chmouel.liseur.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("grimmory", ServerKind.GRIMMORY.urlPrefix)
        assertEquals(
            listOf("CALIBRE", "KOMGA", "LISEUR_SYNC", "GRIMMORY"),
            ServerKind.entries.map { it.name },
        )
    }

    @Test
    fun `a book's remote id round-trips through its URL`() {
        assertEquals("liseur-sync:b-1", ServerKind.LISEUR_SYNC.remoteUrl("b-1"))
        assertEquals("b-1", ServerKind.LISEUR_SYNC.remoteId("liseur-sync:b-1"))
        assertEquals(null, ServerKind.LISEUR_SYNC.remoteId("komga:b-1"))
        assertEquals("grimmory:4207", ServerKind.GRIMMORY.remoteUrl("4207"))
        assertEquals("4207", ServerKind.GRIMMORY.remoteId("grimmory:4207"))
    }

    @Test
    fun `a grimmory book is known to have come from a server`() {
        // Everything downstream -- the download prompt, removal, the
        // upload offer -- reads a book's origin off this.
        assertTrue(ServerKind.isRemoteUrl("grimmory:4207"))
        assertFalse(ServerKind.isRemoteUrl("content://local/file.epub"))
    }

    @Test
    fun `only the kinds that sign with the typed password keep it`() {
        // The regression for an account that connects and then reports
        // lost credentials on its first refresh: liseur-sync also holds
        // a Basic, and deliberately throws the password away.
        assertTrue(ServerKind.CALIBRE.signsWithStoredPassword)
        assertTrue(ServerKind.GRIMMORY.signsWithStoredPassword)
        assertFalse(ServerKind.KOMGA.signsWithStoredPassword)
        assertFalse(ServerKind.LISEUR_SYNC.signsWithStoredPassword)
    }

    @Test
    fun `a row from before a kind existed still reads as calibre`() {
        assertEquals(ServerKind.CALIBRE, ServerKind.fromStored("SOMETHING_FROM_THE_FUTURE"))
        assertEquals(ServerKind.LISEUR_SYNC, ServerKind.fromStored("LISEUR_SYNC"))
    }
}
