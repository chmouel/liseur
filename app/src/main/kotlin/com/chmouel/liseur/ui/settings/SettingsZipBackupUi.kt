package com.chmouel.liseur.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
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
    val export: () -> Unit,
    val restore: () -> Unit,
    val confirmRestore: () -> Unit,
    val dismissPreview: () -> Unit,
    val close: () -> Unit,
)

sealed interface SettingsBackupUiStatus {
    data class Exported(val fonts: Int, val annotations: Int) : SettingsBackupUiStatus
    data class Restored(
        val fontsImported: Int,
        val fontsAlreadyPresent: Int,
        val fontFailures: Int,
        val annotationsAdded: Int,
        val annotationsAlreadyPresent: Int,
        val positionsRestored: Int,
    ) : SettingsBackupUiStatus

    data class Failed(val failure: SettingsBackupFailure) : SettingsBackupUiStatus
    data object PartiallyRestored : SettingsBackupUiStatus
    data class BooksExported(val result: BookExportResult) : SettingsBackupUiStatus
    data class BooksExportCancelled(val counts: BookExportProgress) : SettingsBackupUiStatus
}

private data class SettingsBackupState(
    val preview: SettingsBackupInspection.Ready? = null,
    val status: SettingsBackupUiStatus? = null,
    val busy: Boolean = false,
    val bookProgress: BookExportProgress? = null,
)

private class SettingsBackupViewModel(
    private val repository: SettingsBackupRepository,
    private val bookExport: BookExportRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsBackupState())
    val state = _state.asStateFlow()
    private var activeOperation: Job? = null
    private var closing = false

    override fun onCleared() {
        viewModelScope.launch(NonCancellable) { repository.discardProcessInspections() }
    }

    fun export(uri: Uri) = runBusy {
        _state.update {
            it.copy(status = when (val result = repository.exportTo(uri)) {
                is SettingsBackupExportResult.Exported -> SettingsBackupUiStatus.Exported(result.fonts, result.annotations)
                is SettingsBackupExportResult.Failed -> SettingsBackupUiStatus.Failed(result.failure)
            })
        }
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
        repository.discardInspection(_state.value.preview?.archiveId)
        when (val result = repository.inspect(uri)) {
            is SettingsBackupInspection.Ready ->
                _state.update { it.copy(preview = result, status = null) }
            is SettingsBackupInspection.Failed ->
                _state.update { it.copy(status = SettingsBackupUiStatus.Failed(result.failure)) }
        }
    }

    fun confirmRestore() {
        val archiveId = _state.value.preview?.archiveId ?: return
        runBusy {
            _state.update { it.copy(preview = null) }
            withContext(NonCancellable) {
                val result = repository.restore(archiveId)
                _state.update {
                    it.copy(
                        status = when (result) {
                            is SettingsBackupRestoreResult.Restored -> SettingsBackupUiStatus.Restored(
                                result.fontsImported,
                                result.fontsAlreadyPresent,
                                result.fontFailures,
                                result.annotationsAdded,
                                result.annotationsAlreadyPresent,
                                result.positionsRestored,
                            )
                            is SettingsBackupRestoreResult.Failed ->
                                SettingsBackupUiStatus.Failed(result.failure)
                            SettingsBackupRestoreResult.PartiallyRestored ->
                                SettingsBackupUiStatus.PartiallyRestored
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                SettingsBackupViewModel(app.container.settingsBackup, app.container.bookExport)
            }
        }
    }
}

@Composable
fun rememberSettingsZipBackup(): SettingsZipBackupUi {
    val model: SettingsBackupViewModel = viewModel(factory = SettingsBackupViewModel.Factory)
    val state by model.state.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        if (it != null) model.export(it)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) model.inspect(it)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) model.exportBooks(it)
    }
    return SettingsZipBackupUi(
        preview = state.preview,
        status = state.status,
        busy = state.busy,
        bookProgress = state.bookProgress,
        exportBooks = { folder.launch(null) },
        cancelBookExport = model::cancelBookExport,
        export = {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            save.launch("liseur-backup-$date.zip")
        },
        restore = { open.launch(arrayOf("*/*")) },
        confirmRestore = model::confirmRestore,
        dismissPreview = model::dismissPreview,
        close = model::close,
    )
}
