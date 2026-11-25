package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConnectionStateTest {

    @Test
    fun `resolveServerUiConnectionState maps all connection states explicitly`() {
        assertEquals(
            ServerUiConnectionState.DISCONNECTED,
            resolveServerUiConnectionState(
                server = null,
                syncStatus = PositionSyncStatus.Idle,
                busy = false,
                paired = false,
            ),
        )
        assertEquals(
            ServerUiConnectionState.CONNECTING,
            resolveServerUiConnectionState(
                server = null,
                syncStatus = PositionSyncStatus.Idle,
                busy = true,
                paired = false,
            ),
        )
        assertEquals(
            ServerUiConnectionState.SYNCING,
            resolveServerUiConnectionState(
                server = server(),
                syncStatus = PositionSyncStatus.Syncing,
                busy = false,
                paired = false,
            ),
        )
        assertEquals(
            ServerUiConnectionState.ERROR,
            resolveServerUiConnectionState(
                server = server(),
                syncStatus = PositionSyncStatus.Failed(SyncFailure.Offline),
                busy = false,
                paired = false,
            ),
        )
        assertEquals(
            ServerUiConnectionState.PAUSED,
            resolveServerUiConnectionState(
                server = server(),
                syncStatus = PositionSyncStatus.Unavailable,
                busy = false,
                paired = false,
            ),
        )
        assertEquals(
            ServerUiConnectionState.PAUSED,
            resolveServerUiConnectionState(
                server = server(kind = ServerKind.CALIBRE, koboToken = null),
                syncStatus = PositionSyncStatus.Idle,
                busy = false,
                paired = false,
            ),
        )
        assertEquals(
            ServerUiConnectionState.ACTIVE,
            resolveServerUiConnectionState(
                server = server(kind = ServerKind.CUSTOM, koboToken = null),
                syncStatus = PositionSyncStatus.Idle,
                busy = false,
                paired = true,
            ),
        )
    }

    @Test
    fun `serverConnectionBadge keeps state to label mapping explicit`() {
        assertEquals(
            R.string.server_status_disconnected to ConnectionStatusTone.PAUSED,
            serverConnectionBadge(ServerUiConnectionState.DISCONNECTED),
        )
        assertEquals(
            R.string.server_status_syncing to ConnectionStatusTone.SYNCING,
            serverConnectionBadge(ServerUiConnectionState.CONNECTING),
        )
        assertEquals(
            R.string.server_status_active to ConnectionStatusTone.ACTIVE,
            serverConnectionBadge(ServerUiConnectionState.ACTIVE),
        )
        assertEquals(
            R.string.server_status_syncing to ConnectionStatusTone.SYNCING,
            serverConnectionBadge(ServerUiConnectionState.SYNCING),
        )
        assertEquals(
            R.string.server_status_paused to ConnectionStatusTone.PAUSED,
            serverConnectionBadge(ServerUiConnectionState.PAUSED),
        )
        assertEquals(
            R.string.server_status_error to ConnectionStatusTone.ERROR,
            serverConnectionBadge(ServerUiConnectionState.ERROR),
        )
    }

    @Test
    fun `lastSyncedAtMillis preserves prior successful sync while syncing`() {
        assertEquals(
            1_700_000_000_000L,
            lastSyncedAtMillis(PositionSyncStatus.Syncing, storedSyncedAt = 1_700_000_000_000L),
        )
        assertEquals(
            null,
            lastSyncedAtMillis(PositionSyncStatus.Syncing, storedSyncedAt = null),
        )
        assertEquals(
            1_700_000_050_000L,
            lastSyncedAtMillis(PositionSyncStatus.Synced(1_700_000_050_000L), storedSyncedAt = 1_700_000_000_000L),
        )
    }

    private fun server(
        kind: ServerKind = ServerKind.CALIBRE,
        koboToken: String? = "cipher",
    ) = RemoteServer(
        kind = kind,
        baseUrl = "https://books.example.com",
        catalogUrl = "https://books.example.com/opds",
        username = "reader",
        passwordCipher = null,
        apiKeyCipher = null,
        accountId = null,
        userId = null,
        koboTokenCipher = koboToken,
        canDownload = true,
        addedAt = 0L,
        catalogSyncedAt = null,
        positionSyncedAt = null,
        syncToken = null,
    )
}
