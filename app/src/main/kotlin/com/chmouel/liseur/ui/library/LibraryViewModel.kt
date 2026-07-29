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
import com.chmouel.liseur.data.calibre.CalibreCatalogRepository
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.calibre.ServerDeleteResult
import com.chmouel.liseur.data.calibre.CatalogStatus
import com.chmouel.liseur.data.calibre.CalibreAccountRepository
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookReadAt
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.FinishedState
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.domain.LibrarySort
import com.chmouel.liseur.domain.matchesLibrarySearch
import com.chmouel.liseur.domain.arrangedBy
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.chmouel.liseur.data.db.CalibreServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
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
}

data class ContinueReading(val book: Book, val progression: Double?)

data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
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
    private val catalog: CalibreCatalogRepository,
    private val positionSync: PositionSyncCoordinator,
    private val downloads: BookDownloadRepository,
    private val account: CalibreAccountRepository,
    private val appSettings: AppSettingsRepository,
    private val progressDao: ReadingProgressDao,
) : ViewModel() {

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

    private val _refreshing = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    private val _isSearchActive = MutableStateFlow(false)
    private var lastScanAt = 0L

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
                _refreshing,
                appSettings.settings,
                progressDao.observeReadAt(),
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
            val server = baseValues[4] as CalibreServer?
            val refreshing = baseValues[5] as Boolean
            val settings = baseValues[6] as AppSettings
            @Suppress("UNCHECKED_CAST")
            val readAtList = baseValues[7] as List<BookReadAt>
            val readAt = readAtList.associate { it.bookUrl to it.updatedAt }

            val sortedBooks = books.arrangedBy(
                settings.librarySort,
                settings.librarySortReversed,
                readAt,
            )

            val filteredBooks = sortedBooks
                .filter { book ->
                    when (filter) {
                        LibraryFilter.ALL -> true
                        LibraryFilter.DOWNLOADED -> book.openableUrl != null || book.downloadState == DownloadState.DOWNLOADED
                        LibraryFilter.UNREAD -> !book.finished
                    }
                }
                .filter { book ->
                    matchesLibrarySearch(query, book.displayTitle, book.displayAuthor)
                }

            LibraryUiState(
                loading = false,
                books = filteredBooks,
                continueReading = recent,
                catalogStatus = catalogStatus,
                downloads = running,
                canDownload = server?.canDownload != false,
                refreshing = refreshing || catalogStatus is CatalogStatus.Refreshing,
                sort = settings.librarySort,
                sortReversed = settings.librarySortReversed,
                searchQuery = query,
                filter = filter,
                isSearchActive = searchActive,
                libraryIsEmpty = books.isEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: LibraryFilter) {
        _filter.value = filter
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
        }
    }

    init {
        refresh()
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

    fun refreshCatalog() {
        viewModelScope.launch { catalog.refresh() }
    }

    /**
     * Pull-to-refresh: look again at the folders, the server's books, and
     * where you got to.
     *
     * The last of those used to be missing, which made the gesture look
     * broken: pulling down brought new books but left a book you had read
     * on another device sitting at the old page.
     */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                lastScanAt = requestedAt
                library.rescanAll()
                catalog.refresh()
                runCatching { positionSync.request(SyncScope.Full, requestedAt) }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Coming back to the library after a while, quietly pick up books added
     * or deleted elsewhere. Debounced, because returning from the reader is
     * the most common way to land here and rescanning every time would spin
     * the disk for nothing.
     */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastScanAt < RESCAN_DEBOUNCE_MS) return
        lastScanAt = System.currentTimeMillis()
        viewModelScope.launch { library.rescanAll() }
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
            val credentials = account.credentials()
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

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { library.addFolder(treeUri) }
    }

    /** Index a single picked file; the reader can open it right away. */
    fun importBook(uri: Uri) {
        viewModelScope.launch { library.importBook(uri) }
    }

    companion object {
        private const val RESCAN_DEBOUNCE_MS = 60_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                LibraryViewModel(
                    library = container.libraryRepository,
                    finishedState = container.finishedState,
                    catalog = container.calibreCatalog,
                    positionSync = container.positionSync,
                    downloads = container.bookDownloads,
                    account = container.calibreAccount,
                    appSettings = container.appSettings,
                    progressDao = container.database.readingProgressDao(),
                )
            }
        }
    }
}
