package com.chmouel.liseur.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.chmouel.liseur.data.db.RemoteServer

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
        assertEquals("bookorbit", ServerKind.BOOKORBIT.urlPrefix)
        assertEquals("custom", ServerKind.CUSTOM.urlPrefix)
        assertEquals(
            listOf("CALIBRE", "KOMGA", "LISEUR_SYNC", "BOOKORBIT", "CUSTOM"),
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
    fun `a custom book is known to have come from a server`() {
        // Everything downstream -- the download prompt, removal, the
        // upload offer -- reads a book's origin off this.
        assertTrue(ServerKind.isRemoteUrl("custom:catalog:4207"))
        assertFalse(ServerKind.isRemoteUrl("content://local/file.epub"))
    }

    @Test
    fun `only the kinds that sign with the typed password keep it`() {
        // The regression for an account that connects and then reports
        // lost credentials on its first refresh: liseur-sync also holds
        // a Basic, and deliberately throws the password away.
        assertTrue(ServerKind.CALIBRE.signsWithStoredPassword)
        assertTrue(ServerKind.CUSTOM.signsWithStoredPassword)
        assertFalse(ServerKind.KOMGA.signsWithStoredPassword)
        assertFalse(ServerKind.LISEUR_SYNC.signsWithStoredPassword)
        assertFalse(ServerKind.BOOKORBIT.signsWithStoredPassword)
    }

    @Test
    fun `a row from before a kind existed still reads as calibre`() {
        assertEquals(ServerKind.CALIBRE, ServerKind.fromStored("SOMETHING_FROM_THE_FUTURE"))
        assertEquals(ServerKind.LISEUR_SYNC, ServerKind.fromStored("LISEUR_SYNC"))
    }

    @Test
    fun `every kind says what it can do with a reading position`() {
        // What the server picker shows before an account exists.
        assertEquals(SyncAbility.PROGRESSION, ServerKind.CALIBRE.syncAbility)
        assertEquals(SyncAbility.EXACT, ServerKind.KOMGA.syncAbility)
        assertEquals(SyncAbility.EXACT, ServerKind.LISEUR_SYNC.syncAbility)
        assertEquals(SyncAbility.EXACT, ServerKind.BOOKORBIT.syncAbility)
        assertEquals(SyncAbility.NONE, ServerKind.CUSTOM.syncAbility)
    }

    @Test
    fun `BookOrbit requires a renewable or current session for account sync`() {
        val account = fullyCredentialed(ServerKind.BOOKORBIT)
        assertTrue(account.canSync)
        assertTrue(account.copy(orbitAccessCipher = null).canSync)
        assertTrue(account.copy(orbitRefreshCipher = null).canSync)
        assertFalse(account.copy(orbitAccessCipher = null, orbitRefreshCipher = null).canSync)
    }

    @Test
    fun `the picker's claim matches what a settled account can actually sync`() {
        // The invariant a fifth kind has to satisfy: promising a sync in
        // the picker and refusing one on the connected screen is the
        // same bug twice, and only this pins them together. Asked of an
        // account holding every secret, because the calibre-web Kobo
        // token is a question about the account, not about the kind.
        ServerKind.entries.forEach { kind ->
            assertEquals(
                "${kind.name} promises ${kind.syncAbility} but canSync disagrees",
                kind.syncAbility != SyncAbility.NONE,
                fullyCredentialed(kind).canSync,
            )
        }
    }

    /**
     * The pairing exists for a server that catalogs books without
     * carrying a place in them. Where the server syncs natively, a
     * second source for the same book is a disagreement the reader can
     * neither see nor resolve — so the two answers are pinned against
     * each other here rather than drifting apart in a `when` somewhere.
     */
    @Test
    fun `only a kind that cannot sync natively may host a kosync pairing`() {
        ServerKind.entries.forEach { kind ->
            if (kind.hostsKosyncPeer) {
                assertEquals(
                    "${kind.name} hosts a kosync pairing yet syncs natively",
                    SyncAbility.NONE,
                    kind.syncAbility,
                )
            }
        }
        assertEquals(true, ServerKind.CUSTOM.hostsKosyncPeer)
        listOf(ServerKind.CALIBRE, ServerKind.KOMGA, ServerKind.LISEUR_SYNC, ServerKind.BOOKORBIT).forEach {
            assertEquals("${it.name} must not host a kosync pairing", false, it.hostsKosyncPeer)
        }
    }

    /**
     * `RemoteUrl.resolve` throws an absolute href's host away and
     * rewrites it onto the configured base, which is right for a
     * reverse-proxied calibre-web and catastrophic for a catalog written
     * by somebody else: it would silently retarget a link at whatever
     * host the reader happens to be connected to.
     */
    @Test
    fun `a link an arbitrary catalog wrote is left where it points`() {
        assertEquals(true, ServerKind.CUSTOM.linksAreAbsolute)
        listOf(ServerKind.CALIBRE, ServerKind.KOMGA, ServerKind.LISEUR_SYNC, ServerKind.BOOKORBIT).forEach {
            assertEquals("${it.name} re-roots its links", false, it.linksAreAbsolute)
        }
    }

    @Test
    fun `a custom book's URL round-trips like any other`() {
        assertEquals("custom:ab12cd:1", ServerKind.CUSTOM.remoteUrl("ab12cd:1"))
        assertEquals("ab12cd:1", ServerKind.CUSTOM.remoteId("custom:ab12cd:1"))
        assertNull(ServerKind.CUSTOM.remoteId("komga:b-1"))
    }

    /** An account of [kind] that has finished every setup step it has. */
    private fun fullyCredentialed(kind: ServerKind) = RemoteServer(
        kind = kind,
        baseUrl = "https://books.example.com",
        username = "reader",
        passwordCipher = "cipher",
        apiKeyCipher = "cipher",
        accountId = "1",
        userId = 1,
        koboTokenCipher = "cipher",
        canDownload = true,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
        liseurTokenCipher = "cipher",
        orbitAccessCipher = "cipher",
        orbitRefreshCipher = "cipher",
    )
}
