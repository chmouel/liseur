package com.chmouel.liseur.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.remote.RemoteCredentials
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.calibre.ServerDeleteResult
import com.chmouel.liseur.data.remote.CatalogStatus
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.BookProgression
import com.chmouel.liseur.data.db.BookReadAt
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.domain.LibrarySort
import com.chmouel.liseur.data.remote.SeriesExtrasRepository
import com.chmouel.liseur.domain.SeriesExtras
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.groupedIntoSeries
import com.chmouel.liseur.domain.matchesLibrarySearch
import com.chmouel.liseur.domain.survivesLibrarySearch
import com.chmouel.liseur.domain.arrangedBy
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import com.chmouel.liseur.data.db.RemoteServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import kotlinx.coroutines.launch

enum class LibraryFilter {
    ALL,
    DOWNLOADED,
    UNREAD,

    /**
     * The series, as series rather than as loose books.
     *
     * A view like [ARCHIVED] rather than a narrowing like the rest: what
     * it shows is not a shorter shelf but a different kind of thing on
     * it, one card per series instead of one per book.
     */
    SERIES,

    /**
     * The books archived. Its own view, reached from the overflow menu
     * rather than from a chip beside the others, because everything else
     * means "of the books on the shelf", and these are not on it.
     */
    ARCHIVED,
    ;

    /**
     * Whether a book belongs in this view.
     *
     * Archived books are out of every other view, not merely absent from
     * a view of their own: the whole point of archiving one is not to
     * meet it again while looking for something else.
     */
    fun accepts(book: Book): Boolean = when (this) {
        ARCHIVED -> book.archived
        ALL -> !book.archived
        DOWNLOADED -> !book.archived &&
            (book.openableUrl != null || book.downloadState == DownloadState.DOWNLOADED)
        UNREAD -> !book.archived && !book.finished
        SERIES -> !book.archived && !book.seriesName.isNullOrBlank()
    }
}

data class ContinueReading(val book: Book, val progression: Double?)

data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    /**
     * The library gathered into series, for the series view. Worked out
     * whether or not that view is showing, because the chip offering it
     * only appears when there is something behind it.
     */
    val series: List<SeriesShelf> = emptyList(),
    val continueReading: ContinueReading? = null,
    val catalogStatus: CatalogStatus = CatalogStatus.Idle,
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val canDownload: Boolean = true,
    val refreshing: Boolean = false,
    val sort: LibrarySort = LibrarySort.Default,
    val sortReversed: Boolean = false,
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val isSearchActive: Boolean = false,
    /**
     * Whether the shelf itself is bare, as opposed to a search or a
     * filter having hidden everything on it. The two need saying very
     * differently: one wants books adding, the other wants the search
     * changing.
     */
    val libraryIsEmpty: Boolean = true,
    /** Whether anything is archived, so the way to it is only offered when there is one. */
    val hasArchived: Boolean = false,
    /**
     * Whether a book server is connected.
     *
     * Without one there is nothing to have not downloaded yet: every
     * book in the library is a file already on the device, so a
     * Downloaded filter would only ever say "all of them".
     */
    val hasServer: Boolean = false,
    /** Whether any book knows what series it is in, so the chip is worth offering. */
    val hasSeries: Boolean = false,
)

/**
 * A deletion the user asked for that did not happen, and where it was
 * meant to happen, so the library can explain the right thing.
 */
data class DeleteFailure(val book: Book, val onServer: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val library: LocalLibraryRepository,
    private val finishedState: FinishedState,
    private val catalog: RemoteCatalogRepository,
    private val positionSync: PositionSyncCoordinator,
    private val downloads: BookDownloadRepository,
    private val account: RemoteAccountRepository,
    private val appSettings: AppSettingsRepository,
    private val progressDao: ReadingProgressDao,
    private val bookDao: BookDao,
    private val seriesExtras: SeriesExtrasRepository,
) : ViewModel() {

    /**
     * What the server adds to the series being looked at, if anything
     * and if there is a server that has anything to add.
     *
     * Null until it arrives and null for ever on calibre-web, which is
     * why the screen is built to want nothing from it.
     */
    private val _openSeriesExtras = MutableStateFlow<SeriesExtras?>(null)
    val openSeriesExtras: StateFlow<SeriesExtras?> = _openSeriesExtras

    /**
     * The book someone tapped to read while it was still on the server.
     * Tapping means "read this", so once the file lands the reader opens by
     * itself; downloads started any other way never do this.
     */
    private val awaitingOpen = MutableStateFlow<String?>(null)

    /** Books that have finished downloading and were asked for by name. */
    val openRequests: Flow<Book> = awaitingOpen.flatMapLatest { url ->
        if (url == null) {
            emptyFlow()
        } else {
            library.books
                .mapNotNull { books -> books.firstOrNull { it.url == url } }
                .filter { it.openableUrl != null }
                .take(1)
        }
    }

    /** Downloads that were meant to be read right away and could not be. */
    val failedOpens: Flow<Book> = awaitingOpen.flatMapLatest { url ->
        if (url == null) {
            emptyFlow()
        } else {
            library.books
                .mapNotNull { books -> books.firstOrNull { it.url == url } }
                .filter { it.downloadState == DownloadState.FAILED }
                .take(1)
        }
    }

    private val _deleteFailures = MutableSharedFlow<DeleteFailure>(extraBufferCapacity = 1)

    /** Deletions that did not happen, so the library can say so. */
    val deleteFailures: Flow<DeleteFailure> = _deleteFailures

    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    private val _isSearchActive = MutableStateFlow(false)

    private val refresher = LibraryRefresh(
        scope = viewModelScope,
        scanFolders = { library.rescanAll() },
        refreshCatalog = { catalog.refresh() },
        syncPositions = { requestedAt, snapshot ->
            positionSync.request(SyncScope.Full, requestedAt, snapshot)
        },
    )

    private val continueReading = library.mostRecent.flatMapLatest { book ->
        if (book == null) {
            flowOf(null)
        } else {
            progressDao.observeTotalProgression(book.url)
                .map { ContinueReading(book, it) }
        }
    }

    val state: StateFlow<LibraryUiState> =
        combine(
            combine(
                library.books,
                continueReading,
                catalog.status,
                downloads.progress,
                account.server,
                refresher.refreshing,
                appSettings.settings,
                progressDao.observeReadAt(),
                progressDao.observeProgressions(),
            ) { values -> values },
            _searchQuery,
            _filter,
            _isSearchActive,
        ) { baseValues, query, filter, searchActive ->
            @Suppress("UNCHECKED_CAST")
            val books = baseValues[0] as List<Book>
            val recent = baseValues[1] as ContinueReading?
            val catalogStatus = baseValues[2] as CatalogStatus
            @Suppress("UNCHECKED_CAST")
            val running = baseValues[3] as Map<String, DownloadProgress>
            val server = baseValues[4] as RemoteServer?
            val refreshing = baseValues[5] as Boolean
            val settings = baseValues[6] as AppSettings
            @Suppress("UNCHECKED_CAST")
            val readAtList = baseValues[7] as List<BookReadAt>
            val readAt = readAtList.associate { it.bookUrl to it.updatedAt }
            @Suppress("UNCHECKED_CAST")
            val progressionList = baseValues[8] as List<BookProgression>
            val progressions = progressionList
                .mapNotNull { row -> row.totalProgression?.let { row.bookUrl to it } }
                .toMap()

            // Disconnecting a server takes its filter away with it,
            // rather than leaving the shelf narrowed by a chip that is
            // no longer on screen to widen it again.
            val onTheShelf = books.filter { !it.archived }
            val shelves = onTheShelf.groupedIntoSeries(progressions)

            // Disconnecting a server takes its filter away with it, and
            // so does the last series leaving the library: a chip that
            // is no longer on screen cannot be used to widen the shelf
            // it narrowed.
            val effectiveFilter = when {
                server == null && filter == LibraryFilter.DOWNLOADED -> LibraryFilter.ALL
                shelves.isEmpty() && filter == LibraryFilter.SERIES -> LibraryFilter.ALL
                // Restoring the last archived book empties the view you
                // are standing in, and takes the menu entry that leads
                // back to it away with it.
                books.none { it.archived } && filter == LibraryFilter.ARCHIVED ->
                    LibraryFilter.ALL
                else -> filter
            }

            val sortedBooks = books.arrangedBy(
                settings.librarySort,
                settings.librarySortReversed,
                readAt,
            )

            val filteredBooks = sortedBooks
                .filter { effectiveFilter.accepts(it) }
                .filter { book ->
                    survivesLibrarySearch(
                        query,
                        searchActive,
                        book.displayTitle,
                        book.displayAuthor,
                        book.seriesName,
                        book.seriesIndex,
                    )
                }

            // A series answers to its own name and to the names of the
            // books in it, so searching for a title still finds the
            // series that title is part of.
            val filteredSeries = shelves.filter { shelf ->
                !searchActive || matchesLibrarySearch(query, shelf.name, shelf.author) ||
                    shelf.volumes.any {
                        matchesLibrarySearch(
                            query,
                            it.book.displayTitle,
                            it.book.displayAuthor,
                            shelf.name,
                            it.book.seriesIndex,
                        )
                    }
            }

            LibraryUiState(
                loading = false,
                books = filteredBooks,
                series = filteredSeries.arrangedBy(
                    settings.librarySort,
                    settings.librarySortReversed,
                    readAt,
                ),
                continueReading = recent,
                catalogStatus = catalogStatus,
                downloads = running,
                canDownload = server?.canDownload != false,
                refreshing = refreshing || catalogStatus is CatalogStatus.Refreshing,
                sort = settings.librarySort,
                sortReversed = settings.librarySortReversed,
                searchQuery = query,
                filter = effectiveFilter,
                isSearchActive = searchActive,
                libraryIsEmpty = books.none { !it.archived },
                hasArchived = books.any { it.archived },
                hasServer = server != null,
                hasSeries = shelves.isNotEmpty(),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: LibraryFilter) {
        _filter.value = filter
    }

    fun setSearchActive(active: Boolean) {
        // The query is deliberately kept when search closes: reopening
        // offers it back, selected, so it can be reused or typed over.
        // The clear button in the bar is what empties it.
        _isSearchActive.value = active
    }

    init {
        // Whatever is already known is in the database and about to be on
        // screen. All a fresh start owes the reader is a look at the
        // folders on the device; the server is asked when they ask.
        refresher.scanQuietly()
        // Books indexed before the library knew what a series was will
        // never be looked at again on their own. This fills them in
        // behind the shelf, which is already drawn and does not wait.
        viewModelScope.launch { library.backfillSeries() }
    }

    /**
     * Picks an order. Asking for the order the library is already in
     * turns it round, which is what a second tap on the same thing in a
     * menu is nearly always meant to do.
     */
    fun setSort(sort: LibrarySort) {
        viewModelScope.launch {
            val current = state.value
            if (current.sort == sort) {
                appSettings.setLibrarySortReversed(!current.sortReversed)
            } else {
                appSettings.setLibrarySort(sort)
                appSettings.setLibrarySortReversed(false)
            }
        }
    }

    /** Reads the library back to front. */
    fun toggleSortDirection() {
        viewModelScope.launch {
            appSettings.setLibrarySortReversed(!state.value.sortReversed)
        }
    }

    /**
     * Pull-to-refresh: look again at the folders, the server's books, and
     * where you got to.
     */
    fun refreshAll() {
        refresher.all()
    }

    /**
     * Coming back to the library after a while, quietly pick up books
     * added or deleted elsewhere.
     */
    fun refreshIfStale() {
        refresher.scanIfStale()
    }

    fun download(book: Book) {
        viewModelScope.launch { downloads.enqueue(book) }
    }

    /** Fetch a book and open it as soon as it is here. */
    fun downloadAndOpen(book: Book) {
        awaitingOpen.value = book.url
        viewModelScope.launch { downloads.enqueue(book) }
    }

    /** The book arrived, or the reader's attention went elsewhere. */
    fun forgetPendingOpen() {
        awaitingOpen.value = null
    }

    fun cancelDownload(book: Book) {
        if (awaitingOpen.value == book.url) awaitingOpen.value = null
        viewModelScope.launch { downloads.cancel(book) }
    }

    fun removeDownload(book: Book) {
        viewModelScope.launch { downloads.removeDownload(book) }
    }

    /** Deletes a local book's file and drops it from the library. */
    fun deleteLocalBook(book: Book) {
        viewModelScope.launch {
            if (!downloads.deleteLocalBook(book)) {
                _deleteFailures.emit(DeleteFailure(book, onServer = false))
            }
        }
    }

    /** Deletes the book from calibre-web itself. */
    fun deleteFromServer(book: Book) {
        viewModelScope.launch {
            val server = account.current()
            // Only calibre-web offers this: on Komga, deleting a file is
            // an administrator's job and the action stays hidden.
            val credentials = account.credentials() as? RemoteCredentials.Basic
            val result = if (server == null || credentials == null) {
                ServerDeleteResult.Failed(null)
            } else {
                downloads.deleteFromServer(book, server.baseUrl, credentials)
            }
            if (result !is ServerDeleteResult.Deleted) {
                _deleteFailures.emit(DeleteFailure(book, onServer = true))
            }
        }
    }

    fun setFinished(book: Book, finished: Boolean) {
        viewModelScope.launch { finishedState.setFinished(book.url, finished) }
    }

    /**
     * Puts a book away, or brings it back. Nothing is deleted and nothing
     * stops syncing: the book is simply not on the shelf.
     */
    fun setArchived(book: Book, archived: Boolean) {
        viewModelScope.launch {
            bookDao.setArchived(book.url, if (archived) System.currentTimeMillis() else null)
        }
    }

    /**
     * A series was opened. Ask whoever holds it what else it knows,
     * having first forgotten the last series' answer so a slow reply
     * cannot arrive under the wrong name.
     *
     * The ask for the last series is cancelled outright rather than left
     * to land late: opening one series, going back, and opening another
     * would otherwise let the first server reply overwrite the second,
     * and a summary of the wrong series is worse than none.
     */
    fun openSeries(shelf: SeriesShelf) {
        seriesExtrasJob?.cancel()
        _openSeriesExtras.value = null
        openSeriesId = null
        val seriesId = shelf.volumes.firstNotNullOfOrNull { it.book.seriesId } ?: return
        openSeriesId = seriesId
        seriesExtrasJob = viewModelScope.launch {
            val extras = try {
                seriesExtras.extras(seriesId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (openSeriesId == seriesId) _openSeriesExtras.value = extras
        }
    }

    private var seriesExtrasJob: Job? = null
    private var openSeriesId: String? = null

    /** Fetches every volume of a series that is not on the device yet. */
    fun downloadMissing(shelf: SeriesShelf) {
        viewModelScope.launch {
            shelf.missing.forEach { downloads.enqueue(it.book) }
        }
    }

    /** Marks the whole series read, for a series read before Liseur held it. */
    fun setSeriesFinished(shelf: SeriesShelf, finished: Boolean) {
        viewModelScope.launch {
            shelf.volumes.filter { it.finished != finished }
                .forEach { finishedState.setFinished(it.book.url, finished) }
        }
    }

    /** Puts every volume away at once; nothing is deleted, as ever. */
    fun setSeriesArchived(shelf: SeriesShelf, archived: Boolean) {
        viewModelScope.launch {
            val at = if (archived) System.currentTimeMillis() else null
            shelf.volumes.filter { it.book.archived != archived }
                .forEach { bookDao.setArchived(it.book.url, at) }
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { library.addFolder(treeUri) }
    }

    /** Index a single picked file; the reader can open it right away. */
    fun importBook(uri: Uri) {
        viewModelScope.launch { library.importBook(uri) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                LibraryViewModel(
                    library = container.libraryRepository,
                    finishedState = container.finishedState,
                    catalog = container.remoteCatalog,
                    positionSync = container.positionSync,
                    downloads = container.bookDownloads,
                    account = container.remoteAccount,
                    appSettings = container.appSettings,
                    progressDao = container.database.readingProgressDao(),
                    bookDao = container.database.bookDao(),
                    seriesExtras = container.seriesExtras,
                )
            }
        }
    }
}
