package com.chmouel.liseur.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.remote.PeerPositionSync
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What a flavor-provided backend already knows about sending books up, in the
 * terms the library's upload offer is decided in.
 *
 * Book URLs rather than books, because the shelf already holds the books and
 * the rules about which of them may go up (archived, hidden, owned elsewhere)
 * live on the main side. [queued], [held] and [withheld] answer the three
 * questions the main side cannot: what this backend is already sending, what it
 * already has, and what it must not send again on its own.
 *
 * [selectionSettled] is false until the reader has answered this account's
 * initial selection, which the backend answers before anything goes up by
 * itself (ADR-0078). [offerSince] is when they last settled it, either by
 * connecting or by confirming the picker; books added before it are left to
 * the backend's own selection.
 */
data class LibraryUploadTarget(
    val accountKey: String,
    val selectionSettled: Boolean = true,
    val offerSince: Long = 0L,
    val queued: Set<String> = emptySet(),
    val held: Set<String> = emptySet(),
    /** Books this backend deleted or failed on, which only a new request may send. */
    val withheld: Set<String> = emptySet(),
)

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

    /** What this backend knows about EPUB uploads for the active account; null while unsupported or inactive. */
    val uploadTarget: Flow<LibraryUploadTarget?> get() = flowOf(null)

    /**
     * Queues [books] under [accountKey]; answers the book URLs newly queued, so a
     * caller acknowledges only the books that really went up for sending.
     *
     * [expectedOfferSince] carries the cutoff the caller decided under, so a
     * backend can drop the write when the reader has answered its selection
     * since; a per-book request made by the reader passes null to ignore it.
     */
    suspend fun enqueueUploads(accountKey: String, books: List<Book>, expectedOfferSince: Long? = null): Set<String> =
        emptySet()

    /** Whether [book]'s file can be fetched again from this backend's active account. */
    suspend fun canDownloadAgain(book: Book): Boolean = false

    /** Enqueues an on-demand download for [book] if owned by this backend; returns its WorkManager id. */
    suspend fun enqueueDownload(book: Book): UUID? = null

    /** Cancels an in-flight download for [book] if owned by this backend. */
    suspend fun cancelDownload(book: Book) = Unit

    /** Whether [book] can be deleted from the active external synced library. */
    suspend fun canDeleteFromSyncedLibrary(book: Book): Boolean = false

    /**
     * Deletes [book] from the active external synced library (writes a durable
     * library tombstone and trashes/removes any uploaded remote file; removes
     * remote-only stubs while preserving annotations, or unlinks local files).
     */
    suspend fun deleteFromSyncedLibrary(book: Book): Boolean = false

    /**
     * Position-sync partner for [com.chmouel.liseur.data.remote.CompositePositionSync].
     * Answers `NotSynced` / `NotApplicable` while inactive; null when unsupported.
     */
    val positionPeer: PeerPositionSync?

    /** Whether this backend appears first in the Book server type picker. */
    val supportsBookServerOption: Boolean get() = false

    val settingsFocusKey: String? get() = null

    /** Short connected-account summary shown on the Settings > Book server row when active. */
    @Composable
    fun connectedSummary(): String? = null

    /** Optional flavor-provided entry in Settings > Library. */
    @Composable
    fun SettingsEntry(onOpen: () -> Unit) = Unit

    /** Optional flavor-provided section inside Book server settings. */
    @Composable
    fun SettingsSection() = Unit

    /** Server-type selector card shown when this backend is selected in Book server settings. */
    @Composable
    fun BookServerSelectedRow(enabled: Boolean, onClick: () -> Unit) = Unit

    /** First option row rendered in the Book server type picker sheet. */
    @Composable
    fun BookServerPickerRow(selected: Boolean, onPick: () -> Unit) = Unit

    /** Action on a connected main server that opens this backend's controls to switch to it. */
    @Composable
    fun BookServerSwitchAction(onClick: () -> Unit) = Unit

    /** Optional hint for the initial empty-library "Connect a library" card. */
    @Composable
    fun welcomeConnectHint(): String? = null

    /** Optional provider logo for the initial empty-library "Connect a library" card. */
    @Composable
    fun WelcomeConnectIcon(modifier: Modifier) = Unit

    /** Optional flavor-provided action row inside a book's Manage section. */
    @Composable
    fun BookManageAction(book: Book, onDone: () -> Unit) = Unit

    /** No-op backend for builds without an external library-sync provider. */
    object None : LibrarySyncBackend {
        override val active: Flow<Boolean> = flowOf(false)
        override suspend fun isActive(): Boolean = false
        override suspend fun deactivate() = Unit
        override fun resume() = Unit
        override val positionPeer: PeerPositionSync? = null
    }
}
