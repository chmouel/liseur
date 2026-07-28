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
import com.chmouel.liseur.data.calibre.CatalogStatus
import com.chmouel.liseur.data.calibre.CalibreAccountRepository
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.LocalLibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.chmouel.liseur.data.db.CalibreServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContinueReading(val book: Book, val progression: Double?)

data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    val continueReading: ContinueReading? = null,
    val catalogStatus: CatalogStatus = CatalogStatus.Idle,
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val canDownload: Boolean = true,
    val refreshing: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val library: LocalLibraryRepository,
    private val catalog: CalibreCatalogRepository,
    private val downloads: BookDownloadRepository,
    private val account: CalibreAccountRepository,
    progressDao: ReadingProgressDao,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
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
            library.books,
            continueReading,
            catalog.status,
            downloads.progress,
            account.server,
            _refreshing,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val books = values[0] as List<Book>
            val recent = values[1] as ContinueReading?
            val catalogStatus = values[2] as CatalogStatus
            @Suppress("UNCHECKED_CAST")
            val running = values[3] as Map<String, DownloadProgress>
            val server = values[4] as CalibreServer?
            val refreshing = values[5] as Boolean
            LibraryUiState(
                loading = false,
                books = books,
                continueReading = recent,
                catalogStatus = catalogStatus,
                downloads = running,
                canDownload = server?.canDownload != false,
                refreshing = refreshing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        refresh()
    }

    fun refreshCatalog() {
        viewModelScope.launch { catalog.refresh() }
    }

    /** Pull-to-refresh: look again at both the folders and the server. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                lastScanAt = System.currentTimeMillis()
                library.rescanAll()
                catalog.refresh()
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

    fun cancelDownload(book: Book) {
        viewModelScope.launch { downloads.cancel(book) }
    }

    fun removeDownload(book: Book) {
        viewModelScope.launch { downloads.removeDownload(book) }
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
                    catalog = container.calibreCatalog,
                    downloads = container.bookDownloads,
                    account = container.calibreAccount,
                    progressDao = container.database.readingProgressDao(),
                )
            }
        }
    }
}
