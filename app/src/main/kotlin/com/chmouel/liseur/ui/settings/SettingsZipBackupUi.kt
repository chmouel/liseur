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
import com.chmouel.liseur.data.library.BookExportRepository
import com.chmouel.liseur.data.library.BookExportProgress
import com.chmouel.liseur.data.library.BookExportResult
import kotlinx.coroutines.CancellationException
import com.chmouel.liseur.data.library.SettingsBackupExportResult
import com.chmouel.liseur.data.library.SettingsBackupFailure
import com.chmouel.liseur.data.library.SettingsBackupInspection
import com.chmouel.liseur.data.library.SettingsBackupPreparation
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
)

sealed interface SettingsBackupUiStatus {
    data class Exported(
        val fonts: Int,
        val annotations: Int,
        val liseurSyncNeedsPairing: Boolean,
        val translationBooks: Int = 0,
        val translationBooksSkipped: Int = 0,
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
    ) : SettingsBackupUiStatus

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupUiStatus
    data class PartiallyRestored(val liseurSyncNeedsPairing: Boolean) : SettingsBackupUiStatus
    data class BooksExported(val result: BookExportResult) : SettingsBackupUiStatus
    data class BooksExportCancelled(val counts: BookExportProgress) : SettingsBackupUiStatus
}

internal data class SettingsBackupState(
    val preview: SettingsBackupInspection.Ready? = null,
    val status: SettingsBackupUiStatus? = null,
    val busy: Boolean = false,
    val bookProgress: BookExportProgress? = null,
    /** A prepared archive is waiting for the picker to open. */
    val saveRequest: String? = null,
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
    private var closing = false

    /** Builds the archive before the picker creates a destination document. */
    fun prepareExport(password: CharArray?, includeTranslations: Boolean) {
        if (closing || _state.value.busy) {
            password?.fill('\u0000')
            return
        }
        runBusy {
            try {
                // A prepared archive the picker never consumed is dropped before a new one.
                takePreparedExport()?.let { repository.discardPreparedExport(it) }
                when (val preparation = repository.prepareExport(password, includeTranslations)) {
                    is SettingsBackupPreparation.Prepared -> {
                        rememberPrepared(preparation.export)
                        _state.update { it.copy(status = null, saveRequest = exportFileName()) }
                    }
                    is SettingsBackupPreparation.Failed ->
                        _state.update { it.copy(status = SettingsBackupUiStatus.Failed(preparation.failure)) }
                }
            } finally {
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
                val result = repository.writeExport(prepared, target)
                _state.update {
                    it.copy(status = when (result) {
                        is SettingsBackupExportResult.Exported ->
                            SettingsBackupUiStatus.Exported(
                                result.fonts, result.annotations, result.liseurSyncNeedsPairing,
                                result.translationBooks, result.translationBooksSkipped,
                            )
                        is SettingsBackupExportResult.Failed -> SettingsBackupUiStatus.Failed(result.failure)
                    })
                }
            } finally {
                repository.discardPreparedExport(prepared)
            }
        }
    }

    override fun onCleared() {
        val prepared = takePreparedExport()
        viewModelScope.launch(NonCancellable) {
            prepared?.let { repository.discardPreparedExport(it) }
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
    }

    private fun takePreparedExport(): PreparedExport? {
        val name = savedState.remove<String>(PREPARED_NAME) ?: return null
        val fonts = savedState.remove<Int>(PREPARED_FONTS) ?: 0
        val annotations = savedState.remove<Int>(PREPARED_ANNOTATIONS) ?: 0
        val pairing = savedState.remove<Boolean>(PREPARED_PAIRING) ?: false
        val translationBooks = savedState.remove<Int>(PREPARED_TRANSLATION_BOOKS) ?: 0
        val translationSkipped = savedState.remove<Int>(PREPARED_TRANSLATION_SKIPPED) ?: 0
        // Saved state also starts from the launch intent's extras, so only a name this app generated counts.
        // A prepared archive swept from the cache counts as absent, not as a failed copy.
        val file = repository.preparedExportFile(name)?.takeIf { it.isFile } ?: return null
        return PreparedExport(file, fonts, annotations, pairing, translationBooks, translationSkipped)
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
        repository.discardInspection(previousArchive)
        when (val result = repository.inspect(uri)) {
            is SettingsBackupInspection.Ready ->
                _state.update { it.copy(preview = result, status = null) }
            is SettingsBackupInspection.Failed ->
                _state.update { it.copy(status = SettingsBackupUiStatus.Failed(result.failure)) }
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
            withContext(NonCancellable) {
                val result = try { repository.restore(archiveId, password) }
                finally { password?.fill('\u0000') }
                _state.update {
                    it.copy(
                        preview = if (result == SettingsBackupRestoreResult.Failed(SettingsBackupFailure.PASSWORD)) it.preview else null,
                        status = when (result) {
                            is SettingsBackupRestoreResult.Restored -> SettingsBackupUiStatus.Restored(
                                result.fontsImported,
                                result.fontsAlreadyPresent,
                                result.fontFailures,
                                result.annotationsAdded,
                                result.annotationsAlreadyPresent,
                                result.positionsRestored,
                                result.companionRecapsAdded,
                                result.liseurSyncNeedsPairing,
                                result.translationBooks,
                                result.translationBooksSkipped,
                            )
                            is SettingsBackupRestoreResult.Failed ->
                                SettingsBackupUiStatus.Failed(result.failure)
                            is SettingsBackupRestoreResult.PartiallyRestored ->
                                SettingsBackupUiStatus.PartiallyRestored(result.liseurSyncNeedsPairing)
                        },
                    )
                }
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
        val prepared = takePreparedExport()
        viewModelScope.launch {
            try {
                operation?.cancelAndJoin()
                prepared?.let { repository.discardPreparedExport(it) }
                repository.discardProcessInspections()
            } finally {
                closing = false
                _state.update { it.copy(preview = null, busy = false) }
                activeOperation = null
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
    )
}
