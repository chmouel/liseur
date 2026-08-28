package com.chmouel.liseur.domain

import com.chmouel.liseur.data.db.KosyncPeer
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether opening the app is worth a round trip.
 *
 * Android throws a process away without asking and builds a new one on
 * the way back in, so anything remembered in memory says "we have never
 * synced" far more often than it is true. What was written down at the
 * end of the last completed run is the only honest answer.
 */
class ForegroundSyncPolicyTest {

    private fun server(
        kind: ServerKind = ServerKind.KOMGA,
        koboToken: String? = null,
        syncedAt: Long?,
    ) = RemoteServer(
        kind = kind,
        baseUrl = "https://books.example",
        username = "reader",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = "u1",
        userId = null,
        koboTokenCipher = koboToken,
        canDownload = true,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = syncedAt,
        syncToken = null,
    )

    private val now = 10L * 60 * 60 * 1000

    @Test
    fun `with no server there is nobody to ask`() {
        assertEquals(false, shouldSyncOnForeground(null, now))
    }

    /** calibre-web without a Kobo token cannot exchange positions at all. */
    @Test
    fun `a server that cannot sync is not asked`() {
        val server = server(kind = ServerKind.CALIBRE, koboToken = null, syncedAt = null)
        assertEquals(false, shouldSyncOnForeground(server, now))
    }

    @Test
    fun `a server that has never synced is asked straight away`() {
        assertEquals(true, shouldSyncOnForeground(server(syncedAt = null), now))
    }

    @Test
    fun `a sync from minutes ago still stands`() {
        val server = server(syncedAt = now - 5 * 60 * 1000)
        assertEquals(false, shouldSyncOnForeground(server, now))
    }

    @Test
    fun `a sync from yesterday does not`() {
        val server = server(syncedAt = now - 24 * 60 * 60 * 1000)
        assertEquals(true, shouldSyncOnForeground(server, now))
    }

    @Test
    fun `the window is the whole hour`() {
        assertEquals(
            false,
            shouldSyncOnForeground(server(syncedAt = now - FOREGROUND_SYNC_FRESH_FOR_MS + 1), now),
        )
        assertEquals(
            true,
            shouldSyncOnForeground(server(syncedAt = now - FOREGROUND_SYNC_FRESH_FOR_MS), now),
        )
    }

    /**
     * A clock put back leaves a stamp in the future. Waiting for it to
     * come round again would mean not syncing for as long as the clock
     * was wrong, which could be years.
     */
    @Test
    fun `a time in the future is not treated as fresh`() {
        val server = server(syncedAt = now + 30L * 24 * 60 * 60 * 1000)
        assertEquals(true, shouldSyncOnForeground(server, now))
    }

    /** A liseur-sync server syncs over the same account it catalogs with. */
    @Test
    fun `a liseur-sync server is asked like any other`() {
        assertEquals(
            true,
            shouldSyncOnForeground(server(kind = ServerKind.LISEUR_SYNC, syncedAt = null), now),
        )
        assertEquals(
            false,
            shouldSyncOnForeground(
                server(kind = ServerKind.LISEUR_SYNC, syncedAt = now - 5 * 60 * 1000),
                now,
            ),
        )
    }

    private fun kosync(syncedAt: Long?) = KosyncPeer(
        baseUrl = "https://books.example/api/koreader",
        username = "reader",
        keyCipher = "sealed",
        addedAt = 0L,
        positionSyncedAt = syncedAt,
    )

    /**
     * The kosync partner is exactly what a Grimmory account pairs with,
     * and Grimmory itself can never sync — so the partner is asked about
     * on its own terms rather than through `canSync`.
     */
    @Test
    fun `a kosync partner makes sync due even when the server cannot sync itself`() {
        val grimmory = server(kind = ServerKind.GRIMMORY, koboToken = null, syncedAt = null)
        assertEquals(false, shouldSyncOnForeground(grimmory, now))
        assertEquals(
            true,
            shouldSyncOnForeground(grimmory, now, kosync = kosync(syncedAt = null)),
        )
    }

    @Test
    fun `a kosync partner that synced minutes ago is left alone`() {
        assertEquals(
            false,
            shouldSyncOnForeground(null, now, kosync = kosync(syncedAt = now - 5 * 60 * 1000)),
        )
    }

    @Test
    fun `either partner being stale is enough to ask`() {
        val fresh = server(syncedAt = now - 5 * 60 * 1000)
        assertEquals(
            true,
            shouldSyncOnForeground(fresh, now, kosync = kosync(syncedAt = now - 2 * 60 * 60 * 1000)),
        )
    }
}
