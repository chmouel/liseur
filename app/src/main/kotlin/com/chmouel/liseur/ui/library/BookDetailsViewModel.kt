package com.chmouel.liseur.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.BookFingerprintRow
import com.chmouel.liseur.data.db.BookMetadataDao
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.db.RemoteServerDao
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.folderDisplayName
import com.chmouel.liseur.data.remote.BookProvenance
import com.chmouel.liseur.data.remote.DownloadFormat
import com.chmouel.liseur.data.remote.DownloadFormats
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.resolveMetadata
import com.chmouel.liseur.domain.validPageCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

sealed interface BookDetailsUiState {
    data object Loading : BookDetailsUiState

    /** The book left the library while the screen was open. */
    data object Gone : BookDetailsUiState

    data class Ready(
        val details: BookDetails,
    ) : BookDetailsUiState
}

data class BookDetails(
    val book: Book,
    val metadata: MetadataSnapshot,
    val provenance: BookProvenance,
    /** The watched folder's name, once the provider has answered. */
    val folderName: String?,
    val progression: Double?,
    val override: FinishedOverride = FinishedOverride.NONE,
    val annotations: Int,
    val fingerprint: BookFingerprintRow?,
    /** The formats the catalog offers, when it offers a choice. */
    val formats: List<DownloadFormat> = emptyList(),
    /** The one a download would fetch now. */
    val chosenFormat: DownloadFormat? = null,
)

/**
 * The details screen's state for one book at a time, observed rather
 * than handed over, so a refresh while the screen is open shows up on
 * it and a book that goes away closes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailsViewModel(
    private val bookDao: BookDao,
    private val metadataDao: BookMetadataDao,
    private val progressDao: ReadingProgressDao,
    private val annotationDao: BookAnnotationDao,
    private val identityDao: WorkIdentityDao,
    private val remoteServerDao: RemoteServerDao,
    private val folderName: suspend (String) -> String?,
) : ViewModel() {
    fun details(bookUrl: String): StateFlow<BookDetailsUiState> {
        val book = bookDao.observeByUrl(bookUrl)
        val folder =
            book
                .map { it?.source }
                .distinctUntilChanged()
                .transformLatest { source ->
                    emit(null)
                    if (source != null) emit(folderName(source))
                }
        val metadata =
            metadataDao.observe(bookUrl).map { rows ->
                rows
                    .mapNotNull { row -> MetadataSource.of(row.source)?.let { it to row.toSnapshot() } }
                    .toMap()
                    .let(::resolveMetadata)
                    ?: MetadataSnapshot()
            }
        val formats =
            book
                .map { it?.let { current -> (current.browseServerId ?: RemoteServer.SINGLE_ID) to current.downloadFormats } }
                .distinctUntilChanged()
                .flatMapLatest { owner ->
                    val list = DownloadFormats.decode(owner?.second)
                    if (owner == null || list.isEmpty()) {
                        flowOf(list to null)
                    } else {
                        remoteServerDao.observe(owner.first).map { server ->
                            list to DownloadFormats.pick(list, server?.preferredFormat)
                        }
                    }
                }
        val reading =
            combine(
                progressDao.observe(bookUrl),
                annotationDao.observeMarkCount(bookUrl),
                identityDao.observeFingerprint(bookUrl),
                ::Triple,
            )
        val place = combine(folder, formats, ::Pair)
        return combine(book, metadata, place, reading) { current, resolved, (folderName, offered), (progress, notes, print) ->
            if (current == null) {
                BookDetailsUiState.Gone
            } else {
                BookDetailsUiState.Ready(
                    BookDetails(
                        book = current,
                        // The count a server listed with the book, until
                        // a catalog refresh stores it with the rest.
                        metadata =
                            resolved.copy(
                                pageCount = resolved.pageCount ?: validPageCount(current.remotePageCount),
                            ),
                        provenance = BookProvenance.of(current.url, current.source),
                        folderName = folderName,
                        progression = progress?.totalProgression,
                        override = progress?.override ?: FinishedOverride.NONE,
                        annotations = notes,
                        fingerprint = currentFingerprint(current, print),
                        formats = offered.first,
                        chosenFormat = offered.second,
                    ),
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            BookDetailsUiState.Loading,
        )
    }

    /** Remembers [format] for every book from [book]'s catalog. */
    fun chooseFormat(book: Book, format: DownloadFormat) {
        viewModelScope.launch {
            remoteServerDao.setPreferredFormat(book.browseServerId ?: RemoteServer.SINGLE_ID, format.key)
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = checkNotNull(this[APPLICATION_KEY])
                    val database = application.container.database
                    BookDetailsViewModel(
                        bookDao = database.bookDao(),
                        metadataDao = database.bookMetadataDao(),
                        progressDao = database.readingProgressDao(),
                        annotationDao = database.annotationDao(),
                        identityDao = database.workIdentityDao(),
                        remoteServerDao = database.remoteServerDao(),
                        folderName = { folderDisplayName(application, it) },
                    )
                }
            }
    }
}

/**
 * The hashes worth showing: only for a file on this device, and only
 * when they were taken of the file as it is now. Nothing computes them
 * here; sync does, the first time it needs them.
 */
internal fun currentFingerprint(
    book: Book,
    row: BookFingerprintRow?,
): BookFingerprintRow? = row?.takeIf { book.openableUrl != null && it.fileModifiedAt == book.fileModifiedAt }
