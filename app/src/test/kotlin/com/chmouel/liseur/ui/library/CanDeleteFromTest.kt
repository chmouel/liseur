package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which connections carry the right to delete a book from the server.
 *
 * The asymmetry between the two kinds is deliberate and exactly the
 * sort of thing a tidy-up removes. liseur-sync asks the stored flag,
 * because its permission is a token scope the server grants (ADR-0025).
 * calibre-web must not, because it has no such flag and never had:
 * requiring one would silently take the action away from every
 * calibre-web server paired before the column existed, and nothing
 * re-runs setup on an upgrade.
 */
class CanDeleteFromTest {

    @Test
    fun `liseur-sync asks the capability`() {
        assertTrue(server(ServerKind.LISEUR_SYNC, canDelete = true).holdsDeletePermission())
        assertFalse(server(ServerKind.LISEUR_SYNC, canDelete = false).holdsDeletePermission())
    }

    /** The one that would regress. */
    @Test
    fun `calibre-web keeps the gate it had`() {
        assertTrue(server(ServerKind.CALIBRE, canDelete = false).holdsDeletePermission())
    }

    private fun server(kind: ServerKind, canDelete: Boolean): RemoteServer = RemoteServer(
        kind = kind,
        baseUrl = "https://example.invalid",
        username = null,
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = null,
        canDownload = true,
        canDelete = canDelete,
        addedAt = 0,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )
}
