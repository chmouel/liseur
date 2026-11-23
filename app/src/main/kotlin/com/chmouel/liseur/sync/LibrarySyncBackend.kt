package com.chmouel.liseur.sync

import androidx.compose.runtime.Composable
import com.chmouel.liseur.data.remote.PeerPositionSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Flavor-registered automatic library-sync backend outside `ServerKind`.
 *
 * Mutually exclusive with the main `RemoteServer` (`id = 1`) automatic sync
 * connection; saved browse-only catalogs (`id > 1`) and kosync pairings stay
 * independent.
 */
interface LibrarySyncBackend {
    /** True while this backend is the active automatic library-sync source. */
    val active: Flow<Boolean>

    /** Whether this backend is currently active. */
    suspend fun isActive(): Boolean

    /** Stops background workers and marks this backend paused, preserving all local state. */
    suspend fun deactivate()

    /** Called on application start to reschedule background work when active. */
    fun resume()

    /**
     * Position-sync partner for [com.chmouel.liseur.data.remote.CompositePositionSync].
     * Answers `NotSynced` / `NotApplicable` while inactive; null when unsupported.
     */
    val positionPeer: PeerPositionSync?

    /** Optional flavor-provided entry in sync settings. */
    @Composable
    fun SettingsEntry(onOpen: () -> Unit) = Unit

    /** No-op backend for builds without an external library-sync provider. */
    object None : LibrarySyncBackend {
        override val active: Flow<Boolean> = flowOf(false)
        override suspend fun isActive(): Boolean = false
        override suspend fun deactivate() = Unit
        override fun resume() = Unit
        override val positionPeer: PeerPositionSync? = null
    }
}
