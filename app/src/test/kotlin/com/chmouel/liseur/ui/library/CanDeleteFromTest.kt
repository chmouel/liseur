package com.chmouel.liseur.ui.library

import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.remote.BookDeleter
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.ServerDeleteResult
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

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

    /**
     * A liseur-sync connection older than the permission is the case
     * worth explaining rather than hiding: the fix is a reconnect, and
     * an absent button says none of that.
     */
    @Test
    fun `only a missing scope asks for a reconnect`() {
        assertTrue(needsReconnect(ServerKind.LISEUR_SYNC, canDelete = false))
        assertFalse(needsReconnect(ServerKind.LISEUR_SYNC, canDelete = true))
        assertFalse(needsReconnect(ServerKind.CALIBRE, canDelete = false))
    }

    /**
     * BookOrbit's permission is an administrator's grant reported at
     * sign-in. Without it the reader is simply not allowed, and a
     * reconnect would change nothing, so none is suggested.
     */
    @Test
    fun `BookOrbit asks the permission it signed in with`() {
        val deleter = object : BookDeleter {
            override suspend fun delete(
                baseUrl: String,
                credentials: RemoteCredentials,
                book: Book,
                forgetReading: Boolean,
            ) = ServerDeleteResult.Deleted
        }
        val dao = Proxy.newProxyInstance(
            RemoteServerDao::class.java.classLoader, arrayOf(RemoteServerDao::class.java),
        ) { _, _, _ -> error("not asked") } as RemoteServerDao
        val router = RemoteRouter(
            dao, emptyMap(), emptyMap(), emptyMap(), deleters = mapOf(ServerKind.BOOKORBIT to deleter),
        )

        assertTrue(canDeleteFrom(server(ServerKind.BOOKORBIT, canDelete = true), router))
        assertFalse(canDeleteFrom(server(ServerKind.BOOKORBIT, canDelete = false), router))
        assertFalse(deleteNeedsReconnect(server(ServerKind.BOOKORBIT, canDelete = false), router))
    }

    private fun needsReconnect(kind: ServerKind, canDelete: Boolean): Boolean =
        !server(kind, canDelete).holdsDeletePermission()

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
