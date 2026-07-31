package com.chmouel.liseur.domain

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
}
