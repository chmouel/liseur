package com.chmouel.liseur.ui.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.remote.RemoteCatalogRepository
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.remote.ServerDeleteResult
import com.chmouel.liseur.data.remote.CatalogStatus
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.RemoteRouter
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.SeriesOrderDao
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
import com.chmouel.liseur.domain.movedItem
import com.chmouel.liseur.domain.renumbered
import com.chmouel.liseur.domain.seriesKey
import com.chmouel.liseur.domain.survivesLibrarySearch
import com.chmouel.liseur.domain.worthShowing
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.chmouel.liseur.domain.FilterGroup
import com.chmouel.liseur.domain.LibraryFilterOption
import com.chmouel.liseur.domain.LibraryFilters
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import java.io.IOException
import kotlinx.coroutines.launch

data class ContinueReading(val book: Book, val progression: Double?)

/**
 * A shelf mid-rearrangement.
 *
 * [original] is what it looked like when the mode opened, and is what
 * decides whether Done has anything to write: opening the mode and
 * closing it again must not renumber a series.
 */
data class SeriesReorder(
    val key: String,
    val order: List<String>,
    val original: List<String>,
    val saving: Boolean = false,
)

/** What a [Notice] is about. */
enum class NoticeKind {
    /** The shelf moved under a draft, so nothing was written. */
    SeriesChangedWhileReordering,
}

/**
 * Something the reader is told once.
 *
 * The [id] is what makes it a distinct message rather than a distinct
 * kind of message: the same thing failing twice has to be shown twice,
 * and a `StateFlow` set to a value equal to the one it holds emits
 * nothing at all.
 */
data class Notice(val kind: NoticeKind, val id: Long)

/**
 * The one message waiting to be shown, held above the screens.
 *
 * Reorder mode can end by the series screen going away underneath it,
 * which is the one moment its own snackbar host cannot show anything.
 * Kept here instead, so whichever screen is mounted picks it up in the
 * host it already has, and one dismissed mid-message hands it on.
 *
 * Acknowledgement is by id rather than by clearing the field. A
 * coroutine cancelled by the branch changing underneath it would
 * otherwise clear a message nobody read, and a consumer that gets there
 * late would wipe a newer one it never showed.
 */
class Notices {
    private val _current = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> = _current
    private var nextId = 1L

    fun raise(kind: NoticeKind) {
        _current.value = Notice(kind, nextId++)
    }

    fun shown(id: Long) {
        _current.update { if (it?.id == id) null else it }
    }
}

data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    /**
     * The library gathered into series, for the series view. Worked out
     * whether or not that view is showing, because the chip offering it
     * only appears when there is something behind it.
     */
    val series: List<SeriesShelf> = emptyList(),
    /**
     * The series the grid draws, which is [series] narrowed by the
     * filters as well. Held apart from [series] because that list is
     * also what an open series screen looks itself up in, and a shelf
     * that stops matching a filter under the reader's finger must not
     * take the screen they are standing on with it.
     */
    val shelfSeries: List<SeriesShelf> = emptyList(),
    val continueReading: ContinueReading? = null,
    val catalogStatus: CatalogStatus = CatalogStatus.Idle,
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val canDownload: Boolean = true,
    /**
     * Whether the connected server lets this account delete books from
     * it. Only calibre-web does: Komga has no such affordance, and a
     * liseur-sync book is a file in a folder the server only reads.
     */
    val canDeleteFromServer: Boolean = false,
    /** Whether a shared liseur-sync series claim can be cleared. */
    val canResetSharedSeries: Boolean = false,
    val refreshing: Boolean = false,
    val sort: LibrarySort = LibrarySort.Default,
    val sortReversed: Boolean = false,
    val searchQuery: String = "",
    val filters: LibraryFilters = LibraryFilters.None,
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
     * Whether the shelf holds a finished book, which by default is a
     * book it is not showing. What lets an empty-looking shelf say where
     * its books have gone instead of implying they are lost.
     */
    val hasFinished: Boolean = false,
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
    /**
     * Every series name in the library, for filing a book into one by
     * hand. Taken from the whole shelf rather than from what a search
     * left showing: the series a book is being moved into has nothing to
     * do with the words typed to find the book.
     */
    val seriesNames: List<String> = emptyList(),
    /**
     * The keys of the series big enough to be shown as series.
     *
     * A card carries a *#3* and a *The Expanse · #3* under its title
     * because the book is one of several; on the only book calling
     * itself a series — which is most of a calibre library, where every
     * standalone is its own series of one — both are noise. The rule is
     * about the size of a group, so it cannot be answered from the book,
     * and the whole grouping is in hand here.
     */
    val shownSeries: Set<String> = emptySet(),
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
    private val router: RemoteRouter,
    private val appSettings: AppSettingsRepository,
    private val progressDao: ReadingProgressDao,
    private val bookDao: BookDao,
    private val seriesOrderDao: SeriesOrderDao,
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
            _isSearchActive,
        ) { baseValues, query, searchActive ->
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

            val onTheShelf = books.filter { !it.archived }
            val allShelves = onTheShelf.groupedIntoSeries(progressions)

            // A shelf of one is not a series, it is one book wearing a
            // series card. Only the *showing* is narrowed, though: the
            // grouping still knows about them, because a series created
            // by hand on its first book has to stay offerable to the
            // second one. Hiding it from the assign dialog too would
            // make the rule enforce itself, permanently.
            val shelves = allShelves.worthShowing()
            // Keyed by series rather than by book, so that a volume kept
            // out of the grouping — an archived one — still wears its
            // number when the archive brings it back up.
            val shownSeries = shelves.mapTo(mutableSetOf()) { it.key }

            // An option whose control is no longer on screen cannot be
            // used to widen the shelf it narrowed, so it stops counting.
            // Only for the showing, though: what the reader chose stays
            // written down, and reconnecting a server hands it back
            // rather than making them ask for it twice.
            val stored = settings.libraryFilters
            val filters = LibraryFilters(
                options = stored.options.filterTo(mutableSetOf()) { option ->
                    when (option) {
                        LibraryFilterOption.DOWNLOADED,
                        LibraryFilterOption.NOT_DOWNLOADED,
                        -> server != null
                        // Restoring the last archived book empties the
                        // view you are standing in, and takes the way
                        // back to it away with it.
                        LibraryFilterOption.ARCHIVED -> books.any { it.archived }
                        else -> true
                    }
                },
                // The grouping is built from the shelf, so there are no
                // series in the archive to group: an archived book is out
                // of its series card as much as it is out of the shelf.
                groupBySeries = stored.groupBySeries &&
                    shelves.isNotEmpty() &&
                    LibraryFilterOption.ARCHIVED !in stored.options,
            )

            val sortedBooks = books.arrangedBy(
                settings.librarySort,
                settings.librarySortReversed,
                readAt,
            )

            val filteredBooks = sortedBooks
                .filter { filters.accepts(it, progressions[it.url]) }
                // Whether a book's series is big enough to be shown is a
                // property of the group, which [LibraryFilters.accepts]
                // cannot see from one book. It is applied here, where
                // the grouping is already in hand.
                .filter { !filters.groupBySeries || seriesKey(it.seriesName) in shownSeries }
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

            val arrangedSeries = filteredSeries.arrangedBy(
                settings.librarySort,
                settings.librarySortReversed,
                readAt,
            )

            LibraryUiState(
                loading = false,
                books = filteredBooks,
                series = arrangedSeries,
                // A series stands for its volumes, so it survives a
                // narrowing as long as one of them does: asking for
                // unread books should leave a part-read series on the
                // shelf rather than hide the volume that has not been
                // started.
                shelfSeries = arrangedSeries.filter { shelf ->
                    shelf.volumes.any { filters.accepts(it.book, progressions[it.book.url]) }
                },
                continueReading = recent,
                catalogStatus = catalogStatus,
                downloads = running,
                canDownload = server?.canDownload != false,
                canDeleteFromServer = server != null &&
                    router.deleterFor(server.kind) != null,
                canResetSharedSeries = server?.kind == ServerKind.LISEUR_SYNC && server.canAdmin,
                refreshing = refreshing || catalogStatus is CatalogStatus.Refreshing,
                sort = settings.librarySort,
                sortReversed = settings.librarySortReversed,
                searchQuery = query,
                filters = filters,
                isSearchActive = searchActive,
                libraryIsEmpty = books.none { !it.archived },
                hasArchived = books.any { it.archived },
                hasFinished = books.any { !it.archived && it.finished },
                hasServer = server != null,
                hasSeries = shelves.isNotEmpty(),
                seriesNames = allShelves.map { it.name },
                shownSeries = shownSeries,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Changes the filters as they are *stored*, not as they are shown.
     *
     * The two differ whenever an option has been quietly ignored — a
     * Downloaded filter with no server behind it — and editing the shown
     * copy would throw that choice away on the next unrelated tap.
     */
    private fun editFilters(edit: (LibraryFilters) -> LibraryFilters) {
        viewModelScope.launch {
            appSettings.editLibraryFilters(edit)
        }
    }

    fun toggleFilter(option: LibraryFilterOption) {
        editFilters { it.toggle(option) }
    }

    fun setGroupBySeries(grouped: Boolean) {
        editFilters { it.copy(groupBySeries = grouped) }
    }

    fun clearFilters() {
        // The archive is a place rather than a narrowing, so Clear
        // widens the shelf you are standing on instead of walking you
        // off it without asking.
        editFilters { stored ->
            LibraryFilters(
                options = stored.options.filterTo(mutableSetOf()) {
                    it.group == FilterGroup.PLACE
                },
            )
        }
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
        // The archive is a place, and taking the last book out of it
        // shuts the door. Written down rather than merely ignored: a
        // narrowing is worth keeping until it can be used again, but
        // being put back in a room you were shown out of months ago is
        // only ever a surprise.
        viewModelScope.launch {
            combine(library.books, appSettings.settings) { books, settings ->
                settings.libraryFilters.archived && books.none { it.archived }
            }.distinctUntilChanged().collect { stranded ->
                if (stranded) {
                    editFilters { it.copy(options = it.options - LibraryFilterOption.ARCHIVED) }
                }
            }
        }
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

    /**
     * Deletes the book from the server itself.
     *
     * Only offered where the server has the notion and the account the
     * right: calibre-web by login, liseur-sync by manage scope. Komga
     * never reaches here — the action is not drawn for it.
     */
    fun deleteFromServer(book: Book) {
        viewModelScope.launch {
            val server = account.current()
            val deleter = server?.let { router.deleterFor(it.kind) }
            val result = if (server == null || deleter == null) {
                ServerDeleteResult.Failed(null)
            } else {
                downloads.deleteFromServer(book, deleter, server)
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
        val seriesId = shelf.volumes.firstNotNullOfOrNull { it.book.shelfSeriesId } ?: return
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

    /**
     * Files a book into a series by hand, or out of every series when
     * [name] is blank.
     *
     * Out of every series is a decision and is stored as one: a book
     * taken off a shelf must not be put back on it by the next catalog
     * refresh, which is exactly what a cleared field would let happen.
     */
    fun setBookSeries(book: Book, name: String?, index: Double?) {
        viewModelScope.launch {
            val clean = name?.trim()?.takeIf { it.isNotEmpty() }
            bookDao.setSeriesOverride(book.url, clean, index)
            pushSeriesClaim(book, clean, index)
        }
    }

    /** Gives the book back to whatever the server or the file said. */
    fun resetBookSeries(book: Book) {
        viewModelScope.launch {
            bookDao.clearSeriesOverride(book.url)
            resetSeriesClaim(book)
        }
    }

    private suspend fun pushSeriesClaim(book: Book, name: String?, index: Double?) {
        val server = account.current()?.takeIf {
            it.kind == ServerKind.LISEUR_SYNC && it.canManageLibrary
        } ?: return
        val credentials = server.credentials ?: return
        val claims = router.seriesClaimsFor(server.kind) ?: return
        try {
            val layers = claims.setPersonalSeries(server.baseUrl, credentials, book, name, index)
            bookDao.setRemoteSeriesId(
                book.url,
                layers?.personal?.firstOrNull()?.id,
            )
        } catch (e: IOException) {
            // The local row is the offline cache, and leaving it in
            // place is what keeps the reader's edit on screen. It is not
            // queued: nothing re-pushes it, so a claim made offline
            // reaches the server only when the reader edits again, and
            // a catalog refresh whose `updated_at` is newer will take it
            // back. There is no outbox in this app to hang it on —
            // positions carry their own pending state rather than a
            // general one — and inventing one for series alone would be
            // the wrong place to start.
            Log.i(TAG, "Could not push the series claim", e)
        }
    }

    private suspend fun resetSeriesClaim(book: Book) {
        val server = account.current()?.takeIf {
            it.kind == ServerKind.LISEUR_SYNC && it.canManageLibrary
        } ?: return
        val credentials = server.credentials ?: return
        val claims = router.seriesClaimsFor(server.kind) ?: return
        try {
            if (!book.seriesOverridden && book.catalogSeriesSource == "shared" && server.canAdmin) {
                claims.resetSharedSeries(server.baseUrl, credentials, book)
                catalog.refresh()
            } else {
                claims.resetPersonalSeries(server.baseUrl, credentials, book)
                catalog.refresh()
            }
        } catch (e: IOException) {
            Log.i(TAG, "Could not reset the series claim", e)
        }
    }

    /**
     * The shelf being put into order, while it is being put into order.
     *
     * Null except in reorder mode. It holds the draft rather than the
     * database because nothing is written until Done, so a half-finished
     * rearrangement costs nothing.
     */
    private val _reorder = MutableStateFlow<SeriesReorder?>(null)
    val reorder: StateFlow<SeriesReorder?> = _reorder

    private val notices = Notices()
    val notice: StateFlow<Notice?> = notices.current

    fun startReorder(shelf: SeriesShelf) {
        _reorder.value = SeriesReorder(
            key = shelf.key,
            order = shelf.volumes.map { it.book.url },
            original = shelf.volumes.map { it.book.url },
        )
    }

    fun cancelReorder() {
        _reorder.value = null
    }

    /**
     * The shelf being reordered stopped existing.
     *
     * A rename or the last volume leaving takes the screen down with the
     * draft on it; saying so is the difference between a refusal and a
     * disappearance.
     */
    fun seriesWentAway() {
        if (_reorder.value == null) return
        _reorder.value = null
        notices.raise(NoticeKind.SeriesChangedWhileReordering)
    }

    fun moveVolume(from: Int, to: Int) {
        _reorder.update { it?.copy(order = it.order.movedItem(from, to)) }
    }

    /**
     * Writes the drafted order, if the shelf still holds the books it
     * was drafted from.
     *
     * The membership is re-checked inside the write's own transaction,
     * not here: between reading a flow and committing, a sync worker can
     * add a volume, and the numbering would then be one book short of
     * the shelf it claims to number.
     */
    fun commitReorder() {
        val draft = _reorder.value ?: return
        if (draft.saving) return
        val numbering = renumbered(draft.order, draft.original)
        if (numbering.isEmpty()) {
            _reorder.value = null
            return
        }
        _reorder.value = draft.copy(saving = true)
        viewModelScope.launch {
            val committed = seriesOrderDao.renumber(draft.key, numbering.map { (url, _) -> url })
            if (committed) {
                pushSeriesOrder(numbering.map { (url, _) -> url })
                _reorder.value = null
            } else {
                // Not a guess at what the reader meant for books they
                // never saw: the draft is dropped and the shelf is
                // offered again as it now stands.
                _reorder.value = null
                notices.raise(NoticeKind.SeriesChangedWhileReordering)
            }
        }
    }

    /**
     * Gives a shelf's numbering back to the catalog and the files.
     *
     * Named for what it does. Hand-typed numbers share a column with
     * dragged ones and cannot be told apart, so they go too, and the
     * confirmation says so.
     */
    fun clearCustomVolumeNumbers(shelf: SeriesShelf) {
        viewModelScope.launch {
            val cleared = seriesOrderDao.clearOrder(shelf.key, shelf.volumes.map { it.book.url })
            if (!cleared) notices.raise(NoticeKind.SeriesChangedWhileReordering)
        }
    }

    private suspend fun pushSeriesOrder(urlsInOrder: List<String>) {
        val server = account.current()?.takeIf {
            it.kind == ServerKind.LISEUR_SYNC && it.canManageLibrary
        } ?: return
        val credentials = server.credentials ?: return
        val claims = router.seriesClaimsFor(server.kind) ?: return
        val byUrl = bookDao.getByUrls(urlsInOrder).associateBy { it.url }
        val books = urlsInOrder.mapNotNull(byUrl::get)
        if (books.size != urlsInOrder.size) return
        try {
            claims.reorderPersonalSeries(server.baseUrl, credentials, books)
        } catch (e: IOException) {
            Log.i(TAG, "Could not push the series order", e)
        }
    }

    /** Marks a message as shown. */
    fun noticeShown(id: Long) = notices.shown(id)

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { library.addFolder(treeUri) }
    }

    /** Index a single picked file; the reader can open it right away. */
    fun importBook(uri: Uri) {
        viewModelScope.launch { library.importBook(uri) }
    }

    companion object {
        private const val TAG = "LibraryViewModel"

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
                    router = container.remoteRouter,
                    appSettings = container.appSettings,
                    progressDao = container.database.readingProgressDao(),
                    bookDao = container.database.bookDao(),
                    seriesOrderDao = container.database.seriesOrderDao(),
                    seriesExtras = container.seriesExtras,
                )
            }
        }
    }
}
