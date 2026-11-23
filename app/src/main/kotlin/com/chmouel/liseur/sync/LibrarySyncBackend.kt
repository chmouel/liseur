package com.chmouel.liseur.sync

import androidx.compose.runtime.Composable
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.PeerPositionSync
import java.util.UUID
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

    /** Whether bringing the app to the foreground should trigger a full sync for this backend. */
    suspend fun shouldSyncOnForeground(now: Long): Boolean = false

    /** Whether [book] is a remote catalog entry owned by this external sync backend. */
    fun ownsBook(book: Book): Boolean = false

    /** Enqueues an on-demand download for [book] if owned by this backend; returns its WorkManager id. */
    suspend fun enqueueDownload(book: Book): UUID? = null

    /** Cancels an in-flight download for [book] if owned by this backend. */
    suspend fun cancelDownload(book: Book) = Unit

    /**
     * Position-sync partner for [com.chmouel.liseur.data.remote.CompositePositionSync].
     * Answers `NotSynced` / `NotApplicable` while inactive; null when unsupported.
     */
    val positionPeer: PeerPositionSync?

    /** Optional flavor-provided entry in Settings > Library. */
    @Composable
    fun SettingsEntry(onOpen: () -> Unit) = Unit

    /** Optional flavor-provided section on the Services page. */
    @Composable
    fun SettingsSection() = Unit

    /** No-op backend for builds without an external library-sync provider. */
    object None : LibrarySyncBackend {
        override val active: Flow<Boolean> = flowOf(false)
        override suspend fun isActive(): Boolean = false
        override suspend fun deactivate() = Unit
        override fun resume() = Unit
        override val positionPeer: PeerPositionSync? = null
    }
}
