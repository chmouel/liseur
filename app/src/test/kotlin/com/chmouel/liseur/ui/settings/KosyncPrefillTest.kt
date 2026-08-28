package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Grimmory's kosync mount is offered before it is asked for — and only
 * then. The prefill must never overwrite what a reader typed, never
 * disturb an existing pairing, and never speak for another kind of
 * server, whose kosync mount (if any) is not at `/api/koreader`.
 */
class KosyncPrefillTest {

    @Test
    fun `a fresh grimmory connection offers its koreader mount`() {
        assertEquals(
            "https://books.example/api/koreader",
            kosyncPrefillUrl(server(ServerKind.GRIMMORY), peer = null, currentUrl = ""),
        )
    }

    @Test
    fun `a trailing slash on the server does not double up`() {
        assertEquals(
            "https://books.example/api/koreader",
            kosyncPrefillUrl(
                server(ServerKind.GRIMMORY, baseUrl = "https://books.example/"),
                peer = null,
                currentUrl = "",
            ),
        )
    }

    @Test
    fun `nothing is offered for the kinds that sync on their own`() {
        for (kind in listOf(ServerKind.CALIBRE, ServerKind.KOMGA, ServerKind.LISEUR_SYNC)) {
            assertNull(kosyncPrefillUrl(server(kind), peer = null, currentUrl = ""))
        }
        assertNull(kosyncPrefillUrl(null, peer = null, currentUrl = ""))
    }

    @Test
    fun `what the reader typed is never overwritten`() {
        assertNull(
            kosyncPrefillUrl(
                server(ServerKind.GRIMMORY),
                peer = null,
                currentUrl = "https://sync.example",
            ),
        )
    }

    @Test
    fun `an existing pairing is never disturbed`() {
        val peer = KosyncPeer(
            baseUrl = "https://sync.example",
            username = "ada",
            keyCipher = "sealed",
            addedAt = 0L,
        )
        assertNull(kosyncPrefillUrl(server(ServerKind.GRIMMORY), peer = peer, currentUrl = ""))
    }

    private fun server(
        kind: ServerKind,
        baseUrl: String = "https://books.example",
    ) = RemoteServer(
        kind = kind,
        baseUrl = baseUrl,
        username = "ada",
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
}
