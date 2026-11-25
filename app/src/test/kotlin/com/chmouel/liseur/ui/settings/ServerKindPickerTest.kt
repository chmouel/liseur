package com.chmouel.liseur.ui.settings

import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SyncAbility
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerKindPickerTest {

    @Test
    fun `syncLineRes keeps exact vs progression vs custom distinction`() {
        ServerKind.entries.forEach { kind ->
            val expected = when (kind) {
                ServerKind.CUSTOM -> R.string.server_sync_custom
                else -> when (kind.syncAbility) {
                    SyncAbility.EXACT -> R.string.server_sync_exact
                    SyncAbility.PROGRESSION -> R.string.server_sync_progression
                    SyncAbility.NONE -> R.string.server_sync_none
                }
            }
            assertEquals(expected, kind.syncLineRes())
        }
    }

    @Test
    fun `custom keeps its own sync copy instead of generic none`() {
        assertEquals(SyncAbility.NONE, ServerKind.CUSTOM.syncAbility)
        assertEquals(R.string.server_sync_custom, ServerKind.CUSTOM.syncLineRes())
    }
}
