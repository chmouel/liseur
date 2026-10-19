package com.chmouel.liseur.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.chmouel.liseur.LiseurApplication
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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
import com.chmouel.liseur.data.library.SettingsBackupRepository
import com.chmouel.liseur.data.library.SettingsBackupRestoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

private data class SettingsBackupState(
    val preview: SettingsBackupInspection.Ready? = null,
    val status: SettingsBackupUiStatus? = null,
    val busy: Boolean = false,
    val bookProgress: BookExportProgress? = null,
)

/** What a document returned by the export picker becomes. */
internal enum class PickedExport { RUN, ABANDON, DISCARD }

/**
 * A plain export still runs when the app was recreated behind the picker. An
 * encrypted one has then lost its in-memory password, so it is abandoned rather
 * than written without the connections the user asked for.
 */
internal fun pickedExport(pending: Boolean, needsPassword: Boolean, hasPassword: Boolean, available: Boolean) = when {
    !pending || !available -> PickedExport.DISCARD
    needsPassword && !hasPassword -> PickedExport.ABANDON
    else -> PickedExport.RUN
}

private class SettingsBackupViewModel(
    private val repository: SettingsBackupRepository,
    private val bookExport: BookExportRepository,
    /** Holds only whether an export is pending and needs a password; never the password itself. */
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsBackupState())
    val state = _state.asStateFlow()
    private var activeOperation: Job? = null
    private var closing = false
    private var exportPassword: CharArray? = null

    fun prepareExport(password: CharArray?, includeTranslations: Boolean) {
        discardPassword()
        exportPassword = password
        savedState[EXPORT_PENDING] = true
        savedState[EXPORT_NEEDS_PASSWORD] = password != null
        savedState[EXPORT_TRANSLATIONS] = includeTranslations
    }

    fun discardPassword() {
        wipePassword()
        savedState[EXPORT_PENDING] = false
        savedState[EXPORT_NEEDS_PASSWORD] = false
    }

    private fun wipePassword() {
        exportPassword?.fill('\u0000')
        exportPassword = null
    }

    override fun onCleared() {
        wipePassword()
        viewModelScope.launch(NonCancellable) { repository.discardProcessInspections() }
    }

    fun export(uri: Uri) {
        val action = pickedExport(
            pending = savedState[EXPORT_PENDING] ?: false,
            needsPassword = savedState[EXPORT_NEEDS_PASSWORD] ?: false,
            hasPassword = exportPassword != null,
            available = !closing && !_state.value.busy,
        )
        val password = exportPassword
        val includeTranslations = savedState[EXPORT_TRANSLATIONS] ?: true
        exportPassword = null
        savedState[EXPORT_PENDING] = false
        savedState[EXPORT_NEEDS_PASSWORD] = false
        if (action != PickedExport.RUN) {
            password?.fill('\u0000')
            discardExport(uri)
            if (action == PickedExport.ABANDON) {
                _state.update { it.copy(status = SettingsBackupUiStatus.Failed(SettingsBackupFailure.EXPORT_PASSWORD_LOST)) }
            }
            return
        }
        runBusy {
            try {
                val result = repository.exportTo(uri, password, includeTranslations = includeTranslations)
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
            } finally { password?.fill('\u0000') }
        }
    }

    fun discardExport(uri: Uri) {
        viewModelScope.launch { repository.discardExport(uri) }
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
        discardPassword()
        if (closing) return
        closing = true
        _state.update { it.copy(busy = true) }
        val operation = activeOperation
        viewModelScope.launch {
            try {
                operation?.cancelAndJoin()
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
        private const val EXPORT_PENDING = "export_pending"
        private const val EXPORT_NEEDS_PASSWORD = "export_needs_password"
        private const val EXPORT_TRANSLATIONS = "export_translations"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                SettingsBackupViewModel(app.container.settingsBackup, app.container.bookExport, createSavedStateHandle())
            }
        }
    }
}

@Composable
fun rememberSettingsZipBackup(active: Boolean): SettingsZipBackupUi {
    val container = (LocalContext.current.applicationContext as LiseurApplication).container
    val translations by container.wholeBookTranslations.records.collectAsState(initial = emptyList())
    val model: SettingsBackupViewModel = viewModel(factory = SettingsBackupViewModel.Factory)
    val state by model.state.collectAsState()
    LaunchedEffect(active) {
        if (!active) model.close()
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        if (it != null) {
            if (active) model.export(it) else { model.discardPassword(); model.discardExport(it) }
        } else model.discardPassword()
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
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            save.launch("liseur-backup-$date.zip")
        },
        restore = { open.launch(arrayOf("*/*")) },
        confirmRestore = model::confirmRestore,
        dismissPreview = model::dismissPreview,
        close = model::close,
        hasTranslations = translations.isNotEmpty(),
    )
}
