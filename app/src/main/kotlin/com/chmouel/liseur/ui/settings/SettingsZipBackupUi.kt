package com.chmouel.liseur.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.chmouel.liseur.LiseurApplication
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chmouel.liseur.container
import com.chmouel.liseur.data.library.BookBackupCandidate
import com.chmouel.liseur.data.library.BookExportRepository
import com.chmouel.liseur.data.library.BookExportProgress
import com.chmouel.liseur.data.library.BookExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import com.chmouel.liseur.data.library.SettingsBackupExportResult
import com.chmouel.liseur.data.library.SettingsBackupFailure
import com.chmouel.liseur.data.library.SettingsBackupInspection
import com.chmouel.liseur.data.library.SettingsBackupPreparation
import com.chmouel.liseur.data.library.SettingsBackupProgress
import com.chmouel.liseur.data.library.SettingsBackupRepository
import com.chmouel.liseur.data.library.SettingsBackupRestoreResult
import com.chmouel.liseur.data.library.PreparedExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SettingsZipBackupUi(
    val preview: SettingsBackupInspection.Ready?,
    val status: SettingsBackupUiStatus?,
    val busy: Boolean,
    val bookProgress: BookExportProgress?,
    val exportBooks: () -> Unit,
    val cancelBookExport: () -> Unit,
    val export: (CharArray?, Boolean) -> Unit,
    val restore: () -> Unit,
    val confirmRestore: (CharArray?) -> Unit,
    val dismissPreview: () -> Unit,
    val close: () -> Unit,
    val hasTranslations: Boolean = false,
    val includeSessions: Boolean = true,
    val setIncludeSessions: (Boolean) -> Unit = {},
    val includeBooks: Boolean = false,
    val setIncludeBooks: (Boolean) -> Unit = {},
    val bookCandidates: List<BookBackupCandidate>? = null,
    val selectedBooks: Set<String> = emptySet(),
    val toggleBook: (String) -> Unit = {},
    val selectAllBooks: (Boolean) -> Unit = {},
    val archiveProgress: SettingsBackupProgress? = null,
    val cancelArchive: () -> Unit = {},
)

sealed interface SettingsBackupUiStatus {
    data class Exported(
        val fonts: Int,
        val annotations: Int,
        val liseurSyncNeedsPairing: Boolean,
        val translationBooks: Int = 0,
        val translationBooksSkipped: Int = 0,
        val books: Int = 0,
        val booksSkipped: Int = 0,
        val sessions: Int = 0,
        val bookBytes: Long = 0,
    ) : SettingsBackupUiStatus
    data class Restored(
        val fontsImported: Int,
        val fontsAlreadyPresent: Int,
        val fontFailures: Int,
        val annotationsAdded: Int,
        val annotationsAlreadyPresent: Int,
        val positionsRestored: Int,
        val companionRecapsAdded: Int,
        val liseurSyncNeedsPairing: Boolean,
        val translationBooks: Int = 0,
        val translationBooksSkipped: Int = 0,
        val booksImported: Int = 0,
        val booksAlreadyPresent: Int = 0,
        val bookFailures: Int = 0,
        val sessionsAdded: Int = 0,
        val sessionsAlreadyPresent: Int = 0,
    ) : SettingsBackupUiStatus

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupUiStatus
    data class PartiallyRestored(val liseurSyncNeedsPairing: Boolean) : SettingsBackupUiStatus
    data class BooksExported(val result: BookExportResult) : SettingsBackupUiStatus
    data class BooksExportCancelled(val counts: BookExportProgress) : SettingsBackupUiStatus
    data object Cancelled : SettingsBackupUiStatus
}

internal data class SettingsBackupState(
    val preview: SettingsBackupInspection.Ready? = null,
    val status: SettingsBackupUiStatus? = null,
    val busy: Boolean = false,
    val bookProgress: BookExportProgress? = null,
    /** A prepared archive is waiting for the picker to open. */
    val saveRequest: String? = null,
    val includeSessions: Boolean = true,
    val includeBooks: Boolean = false,
    val bookCandidates: List<BookBackupCandidate>? = null,
    val selectedBooks: Set<String> = emptySet(),
    val archiveProgress: SettingsBackupProgress? = null,
)

internal class SettingsBackupViewModel(
    private val repository: SettingsBackupRepository,
    private val bookExport: BookExportRepository,
    /** Holds where the prepared archive waits; the password never leaves this call. */
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsBackupState())
    val state = _state.asStateFlow()
    private var activeOperation: Job? = null
    private var candidatesJob: Job? = null
    private var restoring = false
    private var closing = false

    fun setIncludeSessions(enabled: Boolean) {
        if (closing || _state.value.busy) return
        _state.update { it.copy(includeSessions = enabled) }
    }

    fun setIncludeBooks(enabled: Boolean) {
        if (closing || _state.value.busy) return
        _state.update { it.copy(includeBooks = enabled) }
        if (enabled && _state.value.bookCandidates == null && candidatesJob?.isActive != true) {
            candidatesJob = viewModelScope.launch(Dispatchers.IO) {
                val candidates = repository.bookCandidates()
                val initialSelection = candidates
                    .filter { it.sizeBytes == null || it.sizeBytes <= SettingsBackupRepository.MAX_BOOK_BYTES }
                    .mapTo(linkedSetOf()) { it.url }
                _state.update { current ->
                    if (current.bookCandidates != null) current
                    else current.copy(bookCandidates = candidates, selectedBooks = initialSelection)
                }
            }
        }
    }

    fun toggleBook(url: String) {
        if (closing || _state.value.busy) return
        val candidate = _state.value.bookCandidates?.firstOrNull { it.url == url }
        if (candidate?.sizeBytes != null && candidate.sizeBytes > SettingsBackupRepository.MAX_BOOK_BYTES) return
        _state.update { current ->
            val updated = if (url in current.selectedBooks) current.selectedBooks - url else current.selectedBooks + url
            current.copy(selectedBooks = updated)
        }
    }

    fun selectAllBooks(all: Boolean) {
        if (closing || _state.value.busy) return
        _state.update { current ->
            val updated = if (all) {
                current.bookCandidates.orEmpty()
                    .filter { it.sizeBytes == null || it.sizeBytes <= SettingsBackupRepository.MAX_BOOK_BYTES }
                    .mapTo(linkedSetOf()) { it.url }
            } else {
                emptySet()
            }
            current.copy(selectedBooks = updated)
        }
    }

    fun cancelArchive() {
        if (_state.value.archiveProgress != null && !restoring) {
            activeOperation?.cancel()
        }
    }

    /** Builds the archive before the picker creates a destination document. */
    fun prepareExport(password: CharArray?, includeTranslations: Boolean) {
        if (closing || _state.value.busy) {
            password?.fill('\u0000')
            return
        }
        val current = _state.value
        val includeSessions = current.includeSessions
        val bookUrls = if (current.includeBooks) current.selectedBooks else emptySet()
        runBusy {
            try {
                // A prepared archive the picker never consumed is dropped before a new one.
                takePreparedExport()?.let { repository.discardPreparedExport(it) }
                when (
                    val preparation = repository.prepareExport(
                        password = password,
                        includeTranslations = includeTranslations,
                        includeSessions = includeSessions,
                        bookUrls = bookUrls,
                        onProgress = { progress -> _state.update { it.copy(archiveProgress = progress) } },
                    )
                ) {
                    is SettingsBackupPreparation.Prepared -> {
                        rememberPrepared(preparation.export)
                        _state.update { it.copy(status = null, saveRequest = exportFileName()) }
                    }
                    is SettingsBackupPreparation.Failed ->
                        _state.update { it.copy(status = SettingsBackupUiStatus.Failed(preparation.failure)) }
                }
            } catch (e: CancellationException) {
                if (!closing) _state.update { it.copy(status = SettingsBackupUiStatus.Cancelled) }
                throw e
            } finally {
                _state.update { it.copy(archiveProgress = null) }
                password?.fill('\u0000')
            }
        }
    }

    fun saveLaunched(name: String) {
        if (_state.value.saveRequest == name) _state.update { it.copy(saveRequest = null) }
    }

    /** Copies the prepared archive after the picker returns, or discards it when cancelled. */
    fun writeExport(target: Uri?) {
        val prepared = takePreparedExport()
        if (closing || _state.value.busy) {
            viewModelScope.launch {
                prepared?.let { repository.discardPreparedExport(it) }
                target?.let { repository.discardExport(it) }
            }
            return
        }
        if (target == null) {
            prepared?.let { viewModelScope.launch { repository.discardPreparedExport(it) } }
            return
        }
        if (prepared == null) {
            viewModelScope.launch { repository.discardExport(target) }
            _state.update { it.copy(status = SettingsBackupUiStatus.Failed(SettingsBackupFailure.FILE_ACCESS)) }
            return
        }
        runBusy {
            try {
                val result = repository.writeExport(prepared, target) { progress ->
                    _state.update { it.copy(archiveProgress = progress) }
                }
                _state.update {
                    it.copy(status = when (result) {
                        is SettingsBackupExportResult.Exported ->
                            SettingsBackupUiStatus.Exported(
                                fonts = result.fonts,
                                annotations = result.annotations,
                                liseurSyncNeedsPairing = result.liseurSyncNeedsPairing,
                                translationBooks = result.translationBooks,
                                translationBooksSkipped = result.translationBooksSkipped,
                                books = result.books,
                                booksSkipped = result.booksSkipped,
                                sessions = result.sessions,
                                bookBytes = result.bookBytes,
                            )
                        is SettingsBackupExportResult.Failed -> SettingsBackupUiStatus.Failed(result.failure)
                    })
                }
            } catch (e: CancellationException) {
                if (!closing) _state.update { it.copy(status = SettingsBackupUiStatus.Cancelled) }
                throw e
            } finally {
                _state.update { it.copy(archiveProgress = null) }
                repository.discardPreparedExport(prepared)
            }
        }
    }

    override fun onCleared() {
        // A pending archive stays: Android can destroy the activity behind the picker and
        // restore it, and the stale sweep removes one whose screen never came back.
        viewModelScope.launch(NonCancellable) {
            repository.discardProcessInspections()
        }
    }

    private fun exportFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return "liseur-backup-$date.zip"
    }

    private fun rememberPrepared(prepared: PreparedExport) {
        savedState[PREPARED_NAME] = prepared.file.name
        savedState[PREPARED_FONTS] = prepared.fonts
        savedState[PREPARED_ANNOTATIONS] = prepared.annotations
        savedState[PREPARED_PAIRING] = prepared.liseurSyncNeedsPairing
        savedState[PREPARED_TRANSLATION_BOOKS] = prepared.translationBooks
        savedState[PREPARED_TRANSLATION_SKIPPED] = prepared.translationBooksSkipped
        savedState[PREPARED_BOOKS] = prepared.books
        savedState[PREPARED_BOOKS_SKIPPED] = prepared.booksSkipped
        savedState[PREPARED_SESSIONS] = prepared.sessions
        savedState[PREPARED_BOOK_BYTES] = prepared.bookBytes
    }

    private fun takePreparedExport(): PreparedExport? {
        // Saved state also starts from the launch intent's extras, so every value is checked
        // and only a name this app generated counts.
        val name = savedState.remove<Any>(PREPARED_NAME) as? String
        val fonts = savedState.remove<Any>(PREPARED_FONTS) as? Int ?: 0
        val annotations = savedState.remove<Any>(PREPARED_ANNOTATIONS) as? Int ?: 0
        val pairing = savedState.remove<Any>(PREPARED_PAIRING) as? Boolean ?: false
        val translationBooks = savedState.remove<Any>(PREPARED_TRANSLATION_BOOKS) as? Int ?: 0
        val translationSkipped = savedState.remove<Any>(PREPARED_TRANSLATION_SKIPPED) as? Int ?: 0
        val books = savedState.remove<Any>(PREPARED_BOOKS) as? Int ?: 0
        val booksSkipped = savedState.remove<Any>(PREPARED_BOOKS_SKIPPED) as? Int ?: 0
        val sessions = savedState.remove<Any>(PREPARED_SESSIONS) as? Int ?: 0
        val bookBytes = savedState.remove<Any>(PREPARED_BOOK_BYTES) as? Long ?: 0L
        // A prepared archive swept from the cache counts as absent, not as a failed copy.
        val file = name?.let(repository::preparedExportFile)?.takeIf { it.isFile } ?: return null
        return PreparedExport(
            file = file,
            fonts = fonts,
            annotations = annotations,
            liseurSyncNeedsPairing = pairing,
            translationBooks = translationBooks,
            translationBooksSkipped = translationSkipped,
            books = books,
            booksSkipped = booksSkipped,
            sessions = sessions,
            bookBytes = bookBytes,
        )
    }

    fun exportBooks(uri: Uri) = runBusy {
        _state.update { it.copy(status = null, bookProgress = BookExportProgress(0)) }
        try {
            val result = bookExport.exportTo(uri) { progress ->
                _state.update { it.copy(bookProgress = progress) }
            }
            _state.update { it.copy(status = SettingsBackupUiStatus.BooksExported(result)) }
        } catch (e: CancellationException) {
            _state.update {
                it.copy(status = SettingsBackupUiStatus.BooksExportCancelled(it.bookProgress ?: BookExportProgress(0)))
            }
            throw e
        } finally {
            _state.update { it.copy(bookProgress = null) }
        }
    }

    fun cancelBookExport() {
        if (_state.value.bookProgress != null) activeOperation?.cancel()
    }

    fun inspect(uri: Uri) = runBusy {
        val previousArchive = _state.value.preview?.archiveId
        _state.update { it.copy(preview = null, status = null) }
        try {
            repository.discardInspection(previousArchive)
            when (
                val result = repository.inspect(uri) { progress ->
                    _state.update { it.copy(archiveProgress = progress) }
                }
            ) {
                is SettingsBackupInspection.Ready ->
                    _state.update { it.copy(preview = result, status = null) }
                is SettingsBackupInspection.Failed ->
                    _state.update { it.copy(status = SettingsBackupUiStatus.Failed(result.failure)) }
            }
        } catch (e: CancellationException) {
            if (!closing) _state.update { it.copy(status = SettingsBackupUiStatus.Cancelled) }
            throw e
        } finally {
            _state.update { it.copy(archiveProgress = null) }
        }
    }

    fun confirmRestore(password: CharArray?) {
        val archiveId = _state.value.preview?.archiveId
        if (archiveId == null || closing || _state.value.busy) {
            password?.fill('\u0000')
            return
        }
        runBusy {
            // A retry must not keep showing the previous attempt's wrong-password status.
            _state.update { it.copy(status = null) }
            restoring = true
            try {
                withContext(NonCancellable) {
                    val result = try {
                        repository.restore(archiveId, password) { progress ->
                            _state.update { it.copy(archiveProgress = progress) }
                        }
                    } finally {
                        password?.fill('\u0000')
                    }
                    _state.update {
                        it.copy(
                            preview = if (result == SettingsBackupRestoreResult.Failed(SettingsBackupFailure.PASSWORD)) it.preview else null,
                            status = when (result) {
                                is SettingsBackupRestoreResult.Restored -> SettingsBackupUiStatus.Restored(
                                    fontsImported = result.fontsImported,
                                    fontsAlreadyPresent = result.fontsAlreadyPresent,
                                    fontFailures = result.fontFailures,
                                    annotationsAdded = result.annotationsAdded,
                                    annotationsAlreadyPresent = result.annotationsAlreadyPresent,
                                    positionsRestored = result.positionsRestored,
                                    companionRecapsAdded = result.companionRecapsAdded,
                                    liseurSyncNeedsPairing = result.liseurSyncNeedsPairing,
                                    translationBooks = result.translationBooks,
                                    translationBooksSkipped = result.translationBooksSkipped,
                                    booksImported = result.booksImported,
                                    booksAlreadyPresent = result.booksAlreadyPresent,
                                    bookFailures = result.bookFailures,
                                    sessionsAdded = result.sessionsAdded,
                                    sessionsAlreadyPresent = result.sessionsAlreadyPresent,
                                )
                                is SettingsBackupRestoreResult.Failed ->
                                    SettingsBackupUiStatus.Failed(result.failure)
                                is SettingsBackupRestoreResult.PartiallyRestored ->
                                    SettingsBackupUiStatus.PartiallyRestored(result.liseurSyncNeedsPairing)
                            },
                        )
                    }
                }
            } finally {
                restoring = false
                _state.update { it.copy(archiveProgress = null) }
            }
        }
    }

    fun dismissPreview() {
        val archiveId = _state.value.preview?.archiveId ?: return
        _state.update { it.copy(preview = null) }
        viewModelScope.launch { repository.discardInspection(archiveId) }
    }

    fun close() {
        if (closing) return
        closing = true
        _state.update { it.copy(busy = true, saveRequest = null) }
        val operation = activeOperation
        val loadingCandidates = candidatesJob
        val prepared = takePreparedExport()
        viewModelScope.launch {
            try {
                loadingCandidates?.cancelAndJoin()
                operation?.cancelAndJoin()
                prepared?.let { repository.discardPreparedExport(it) }
                repository.discardProcessInspections()
            } finally {
                closing = false
                _state.update { it.copy(preview = null, busy = false, archiveProgress = null) }
                activeOperation = null
                candidatesJob = null
            }
        }
    }

    private fun runBusy(action: suspend () -> Unit) {
        if (closing || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        activeOperation = viewModelScope.launch {
            try {
                action()
            } finally {
                _state.update { it.copy(busy = closing) }
                activeOperation = null
            }
        }
    }

    companion object {
        private const val PREPARED_NAME = "prepared_export_name"
        private const val PREPARED_FONTS = "prepared_export_fonts"
        private const val PREPARED_ANNOTATIONS = "prepared_export_annotations"
        private const val PREPARED_PAIRING = "prepared_export_pairing"
        private const val PREPARED_TRANSLATION_BOOKS = "prepared_export_translation_books"
        private const val PREPARED_TRANSLATION_SKIPPED = "prepared_export_translation_skipped"
        private const val PREPARED_BOOKS = "prepared_export_books"
        private const val PREPARED_BOOKS_SKIPPED = "prepared_export_books_skipped"
        private const val PREPARED_SESSIONS = "prepared_export_sessions"
        private const val PREPARED_BOOK_BYTES = "prepared_export_book_bytes"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                SettingsBackupViewModel(app.container.settingsBackup, app.container.bookExport, createSavedStateHandle())
            }
        }
    }
}

/**
 * Opens the destination picker once per prepared request, and only on a resumed screen.
 *
 * The request is only re-read at recomposition, while [repeatOnLifecycle] runs its block
 * again on every transition back to RESUMED, so the block checks the request itself: the
 * resume that follows a closed picker finds it consumed and opens nothing.
 */
internal suspend fun Lifecycle.launchSaveRequest(
    request: () -> String?,
    consume: (String) -> Unit,
) {
    val requested = request() ?: return
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        if (request() == requested) consume(requested)
    }
}

@Composable
fun rememberSettingsZipBackup(active: Boolean): SettingsZipBackupUi {
    val container = (LocalContext.current.applicationContext as LiseurApplication).container
    // Opening the coordinator reconciles translation storage, so only an open backup screen asks.
    val translations by remember(active) {
        if (active) container.wholeBookTranslations.records else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val model: SettingsBackupViewModel = viewModel(factory = SettingsBackupViewModel.Factory)
    val state by model.state.collectAsState()
    LaunchedEffect(active) {
        if (!active) model.close()
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        model.writeExport(it)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A request prepared in the background waits for RESUMED instead of orphaning its archive.
    LaunchedEffect(state.saveRequest) {
        lifecycle.launchSaveRequest(
            request = { model.state.value.saveRequest },
            consume = { name ->
                model.saveLaunched(name)
                save.launch(name)
            },
        )
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null && active) model.inspect(it)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null && active) model.exportBooks(it)
    }
    return SettingsZipBackupUi(
        preview = state.preview,
        status = state.status,
        busy = state.busy,
        bookProgress = state.bookProgress,
        exportBooks = { folder.launch(null) },
        cancelBookExport = model::cancelBookExport,
        export = { password, includeTranslations ->
            model.prepareExport(password, includeTranslations)
        },
        restore = { open.launch(arrayOf("*/*")) },
        confirmRestore = model::confirmRestore,
        dismissPreview = model::dismissPreview,
        close = model::close,
        hasTranslations = translations.isNotEmpty(),
        includeSessions = state.includeSessions,
        setIncludeSessions = model::setIncludeSessions,
        includeBooks = state.includeBooks,
        setIncludeBooks = model::setIncludeBooks,
        bookCandidates = state.bookCandidates,
        selectedBooks = state.selectedBooks,
        toggleBook = model::toggleBook,
        selectAllBooks = model::selectAllBooks,
        archiveProgress = state.archiveProgress,
        cancelArchive = model::cancelArchive,
    )
}
