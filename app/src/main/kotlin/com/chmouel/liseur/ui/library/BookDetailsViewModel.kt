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
import com.chmouel.liseur.data.db.WorkIdentityDao
import com.chmouel.liseur.data.library.folderDisplayName
import com.chmouel.liseur.data.remote.BookProvenance
import com.chmouel.liseur.domain.MetadataSnapshot
import com.chmouel.liseur.domain.MetadataSource
import com.chmouel.liseur.domain.resolveMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

sealed interface BookDetailsUiState {
    data object Loading : BookDetailsUiState

    /** The book left the library while the screen was open. */
    data object Gone : BookDetailsUiState

    data class Ready(val details: BookDetails) : BookDetailsUiState
}

data class BookDetails(
    val book: Book,
    val metadata: MetadataSnapshot,
    val provenance: BookProvenance,
    /** The watched folder's name, once the provider has answered. */
    val folderName: String?,
    val progression: Double?,
    val annotations: Int,
    val fingerprint: BookFingerprintRow?,
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
    private val folderName: suspend (String) -> String?,
) : ViewModel() {

    fun details(bookUrl: String): StateFlow<BookDetailsUiState> {
        val book = bookDao.observeByUrl(bookUrl)
        val folder = book.map { it?.source }
            .distinctUntilChanged()
            .transformLatest { source ->
                emit(null)
                if (source != null) emit(folderName(source))
            }
        val metadata = metadataDao.observe(bookUrl).map { rows ->
            rows.mapNotNull { row -> MetadataSource.of(row.source)?.let { it to row.toSnapshot() } }
                .toMap()
                .let(::resolveMetadata)
                ?: MetadataSnapshot()
        }
        val reading = combine(
            progressDao.observeTotalProgression(bookUrl),
            annotationDao.observeMarkCount(bookUrl),
            identityDao.observeFingerprint(bookUrl),
            ::Triple,
        )
        return combine(book, metadata, folder, reading) { current, resolved, folderName, (progression, notes, print) ->
            if (current == null) {
                BookDetailsUiState.Gone
            } else {
                BookDetailsUiState.Ready(
                    BookDetails(
                        book = current,
                        metadata = resolved,
                        provenance = BookProvenance.of(current.url, current.source),
                        folderName = folderName,
                        progression = progression,
                        annotations = notes,
                        fingerprint = currentFingerprint(current, print),
                    ),
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0),
            BookDetailsUiState.Loading,
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                val database = application.container.database
                BookDetailsViewModel(
                    bookDao = database.bookDao(),
                    metadataDao = database.bookMetadataDao(),
                    progressDao = database.readingProgressDao(),
                    annotationDao = database.annotationDao(),
                    identityDao = database.workIdentityDao(),
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
internal fun currentFingerprint(book: Book, row: BookFingerprintRow?): BookFingerprintRow? =
    row?.takeIf { book.openableUrl != null && it.fileModifiedAt == book.fileModifiedAt }
