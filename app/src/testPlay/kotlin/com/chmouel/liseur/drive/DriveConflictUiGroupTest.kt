package com.chmouel.liseur.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveConflictUiGroupTest {
    private fun account(
        state: String = DriveAccount.STATE_ACTIVE,
        lastError: String? = null,
    ) = DriveAccount(
        permissionId = "perm-1",
        email = "reader@example.com",
        state = state,
        connectedAt = 1000L,
        lastSuccessAt = 2000L,
        lastError = lastError,
    )

    @Test
    fun `resolveDriveUiConnectionState maps all Drive UI states explicitly`() {
        assertEquals(
            DriveUiConnectionState.DISCONNECTED,
            resolveDriveUiConnectionState(DriveSettingsUiState()),
        )
        assertEquals(
            DriveUiConnectionState.CONNECTING,
            resolveDriveUiConnectionState(DriveSettingsUiState(busy = true)),
        )
        assertEquals(
            DriveUiConnectionState.ACTIVE,
            resolveDriveUiConnectionState(DriveSettingsUiState(account = account())),
        )
        assertEquals(
            DriveUiConnectionState.SYNCING,
            resolveDriveUiConnectionState(DriveSettingsUiState(account = account(), syncingNow = true)),
        )
        assertEquals(
            DriveUiConnectionState.PAUSED,
            resolveDriveUiConnectionState(
                DriveSettingsUiState(account = account(state = DriveAccount.STATE_PAUSED)),
            ),
        )
        assertEquals(
            DriveUiConnectionState.ERROR,
            resolveDriveUiConnectionState(
                DriveSettingsUiState(account = account(state = DriveAccount.STATE_NEEDS_RECONNECT)),
            ),
        )
        assertEquals(
            DriveUiConnectionState.ERROR,
            resolveDriveUiConnectionState(
                DriveSettingsUiState(account = account(), errorMessage = "Sync failed"),
            ),
        )
        assertEquals(
            DriveUiConnectionState.PAUSED,
            resolveDriveUiConnectionState(
                DriveSettingsUiState(
                    account = account(
                        state = DriveAccount.STATE_PAUSED,
                        lastError = "paused intentionally",
                    ),
                ),
            ),
        )
    }

    private fun conflict(domain: String, kind: String) = DriveConflict(
        permissionId = "p",
        conflictId = "c-$domain-$kind",
        domain = domain,
        recordKey = "k",
        kind = kind,
        localFp = "l",
        remoteFp = "r",
        remoteDeviceId = "d",
        remoteHlc = "h",
        state = DriveConflict.STATE_OPEN,
        openedAt = 0L,
    )

    private fun group(domain: String, vararg kinds: String): DriveConflictUiGroup {
        val members = kinds.map { conflict(domain, it) }
        val kind = if (DriveConflict.KIND_DELETE_EDIT in kinds) {
            DriveConflict.KIND_DELETE_EDIT
        } else {
            DriveConflict.KIND_EDIT_EDIT
        }
        return DriveConflictUiGroup("g", domain, kind, "t", null, "", "", false, false, members)
    }

    @Test
    fun `mixed delete-edit series shelf keeps device-order choices`() {
        val g = group(DriveWireCodec.DOMAIN_SERIES, DriveConflict.KIND_EDIT_EDIT, DriveConflict.KIND_DELETE_EDIT)
        assertFalse(g.offersKeepEditedOrDelete)
    }

    @Test
    fun `non-series delete-edit keeps edited-or-delete choices`() {
        for (d in listOf(DriveWireCodec.DOMAIN_ANNOTATION, DriveWireCodec.DOMAIN_METADATA, DriveWireCodec.DOMAIN_EVALUATION)) {
            assertTrue(group(d, DriveConflict.KIND_DELETE_EDIT).offersKeepEditedOrDelete)
            assertFalse(group(d, DriveConflict.KIND_EDIT_EDIT).offersKeepEditedOrDelete)
        }
        assertEquals(DriveConflict.KIND_DELETE_EDIT, group(DriveWireCodec.DOMAIN_SERIES, DriveConflict.KIND_DELETE_EDIT).kind)
    }
}
