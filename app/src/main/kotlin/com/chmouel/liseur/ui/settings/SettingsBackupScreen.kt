package com.chmouel.liseur.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.library.BookBackupCandidate
import com.chmouel.liseur.data.library.BookExportResult
import com.chmouel.liseur.data.library.SettingsBackupFailure
import com.chmouel.liseur.data.library.SettingsBackupProgress
import com.chmouel.liseur.data.library.SettingsBackupRepository
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.search.SettingsEntries
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import com.chmouel.liseur.ui.windowWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    backup: SettingsZipBackupUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showExportOptions by remember { mutableStateOf(false) }
    var includeConnections by remember { mutableStateOf(false) }
    var includeTranslations by remember { mutableStateOf(true) }
    var showBookPicker by remember { mutableStateOf(false) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    // A wrong password shows only for an attempt made since the restore dialog opened.
    var restoreAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(backup.preview) {
        if (passwordMode == "restore" && backup.preview == null) passwordMode = null
    }

    val candidates = backup.bookCandidates
    val selectedCandidates = remember(candidates, backup.selectedBooks) {
        candidates.orEmpty().filter { it.url in backup.selectedBooks }
    }
    val selectedBooksBytes = remember(selectedCandidates) {
        selectedCandidates.sumOf { it.sizeBytes ?: 0L }
    }
    val booksOverTotalLimit = backup.includeBooks &&
        selectedBooksBytes > SettingsBackupRepository.MAX_BOOKS_TOTAL_BYTES

    if (showBookPicker && candidates != null && candidates.isNotEmpty()) {
        BookSelectionDialog(
            candidates = candidates,
            selectedUrls = backup.selectedBooks,
            selectedBytes = selectedBooksBytes,
            busy = backup.busy,
            onToggle = backup.toggleBook,
            onSelectAll = backup.selectAllBooks,
            onDismiss = { showBookPicker = false },
        )
    }

    if (showExportOptions && !showBookPicker) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text(stringResource(R.string.settings_backup_export)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.settings_backup_detail))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = includeConnections,
                                enabled = !backup.busy,
                                role = Role.Checkbox,
                                onValueChange = { includeConnections = it },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeConnections, onCheckedChange = null, enabled = !backup.busy)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.settings_backup_include_connections))
                            Text(
                                stringResource(R.string.settings_backup_accounts_password_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = backup.includeSessions,
                                enabled = !backup.busy,
                                role = Role.Checkbox,
                                onValueChange = backup.setIncludeSessions,
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = backup.includeSessions, onCheckedChange = null, enabled = !backup.busy)
                        Text(stringResource(R.string.settings_backup_include_stats), Modifier.padding(start = 12.dp))
                    }
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = backup.includeBooks,
                                enabled = !backup.busy,
                                role = Role.Checkbox,
                                onValueChange = backup.setIncludeBooks,
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = backup.includeBooks, onCheckedChange = null, enabled = !backup.busy)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.settings_backup_include_books))
                            Text(
                                stringResource(R.string.settings_backup_include_books_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (backup.includeBooks) {
                        RowDivider()
                        if (candidates != null && candidates.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_backup_books_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        } else if (candidates != null) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !backup.busy) { showBookPicker = true }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    stringResource(R.string.settings_backup_books_choose),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.settings_backup_books_selected,
                                        selectedCandidates.size,
                                        selectedCandidates.size,
                                        Formatter.formatShortFileSize(context, selectedBooksBytes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (booksOverTotalLimit) {
                                    Text(
                                        stringResource(
                                            R.string.settings_backup_books_over_limit,
                                            Formatter.formatShortFileSize(context, SettingsBackupRepository.MAX_BOOKS_TOTAL_BYTES),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (backup.hasTranslations) {
                        RowDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = includeTranslations,
                                    enabled = !backup.busy,
                                    role = Role.Checkbox,
                                    onValueChange = { includeTranslations = it },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = includeTranslations, onCheckedChange = null, enabled = !backup.busy)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(stringResource(R.string.whole_book_backup_include))
                                Text(
                                    stringResource(R.string.whole_book_backup_unencrypted),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !backup.busy && !booksOverTotalLimit,
                    onClick = {
                        showExportOptions = false
                        if (includeConnections) passwordMode = "export" else backup.export(null, includeTranslations)
                    },
                ) {
                    Text(stringResource(R.string.settings_backup_export))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportOptions = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    passwordMode?.let { mode ->
        BackupPasswordDialog(
            exporting = mode == "export",
            busy = backup.busy,
            failed = restoreAttempted &&
                (backup.status as? SettingsBackupUiStatus.Failed)?.failure == SettingsBackupFailure.PASSWORD,
            onDismiss = {
                if (!backup.busy) {
                    passwordMode = null
                    if (mode == "restore") backup.dismissStatus()
                    if (mode == "export") showExportOptions = true
                }
            },
            onConfirm = { password ->
                if (mode == "export") {
                    passwordMode = null
                    backup.export(password, includeTranslations)
                } else {
                    restoreAttempted = true
                    backup.confirmRestore(password)
                }
            },
        )
    }

    (backup.status as? SettingsBackupUiStatus.Failed)
        ?.takeIf { passwordMode == null }
        ?.let { failure ->
            AlertDialog(
                onDismissRequest = backup.dismissStatus,
                text = { Text(stringResource(failure.failure.message)) },
                confirmButton = {
                    TextButton(onClick = backup.dismissStatus) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

    backup.preview?.takeIf { passwordMode == null }?.let { ready ->
        val preview = ready.preview
        AlertDialog(
            onDismissRequest = { if (!backup.busy) backup.dismissPreview() },
            title = { Text(stringResource(R.string.settings_backup_preview_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            if (preview.includesConnections) R.string.settings_backup_connections_replace
                            else R.string.settings_backup_no_connections,
                        ),
                    )
                    if (preview.settingCount > 0) {
                        Text(pluralStringResource(R.plurals.settings_backup_setting_count, preview.settingCount, preview.settingCount))
                    }
                    if (preview.fontCount > 0) {
                        Text(pluralStringResource(R.plurals.settings_backup_font_count, preview.fontCount, preview.fontCount))
                    }
                    if (preview.bookCount > 0) {
                        val books = pluralStringResource(R.plurals.settings_backup_books_preview, preview.bookCount, preview.bookCount)
                        val toAdd = if (preview.booksToImport > 0) {
                            pluralStringResource(
                                R.plurals.settings_backup_books_to_add,
                                preview.booksToImport,
                                preview.booksToImport,
                                Formatter.formatShortFileSize(context, preview.bookImportBytes),
                            )
                        } else {
                            null
                        }
                        Text(listOfNotNull(books, toAdd).joinToString(" "))
                    }
                    if (preview.annotationCount > 0) {
                        val marks = stringResource(
                            R.string.annotations_backup_summary,
                            pluralStringResource(R.plurals.annotations_backup_mark_count, preview.annotationCount, preview.annotationCount),
                            pluralStringResource(R.plurals.annotations_backup_book_count, preview.annotationBookCount, preview.annotationBookCount),
                        )
                        if (preview.matchedAnnotationBooks == preview.annotationBookCount) {
                            Text(marks)
                        } else {
                            Text(
                                stringResource(
                                    R.string.import_preview_body,
                                    marks,
                                    pluralStringResource(R.plurals.annotations_backup_matched_books, preview.matchedAnnotationBooks, preview.matchedAnnotationBooks),
                                ),
                            )
                        }
                    }
                    if (preview.positionCount > 0) {
                        Text(pluralStringResource(R.plurals.settings_backup_positions_preview, preview.positionCount, preview.positionCount))
                    }
                    if (preview.sessionCount > 0) {
                        Text(pluralStringResource(R.plurals.settings_backup_sessions_preview, preview.sessionCount, preview.sessionCount))
                    }
                    if (preview.companionRecapCount > 0) {
                        Text(stringResource(R.string.settings_backup_companion_preview, preview.companionRecapCount))
                    }
                    if (preview.translationBookCount > 0) {
                        Text(stringResource(R.string.whole_book_backup_preview, preview.translationBookCount))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (preview.includesConnections) {
                        restoreAttempted = false
                        passwordMode = "restore"
                    } else backup.confirmRestore(null)
                }, enabled = !backup.busy) {
                    Text(stringResource(R.string.settings_backup_restore_action))
                }
            },
            dismissButton = {
                TextButton(onClick = backup.dismissPreview, enabled = !backup.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_backup_title)) },
                navigationIcon = { PaneBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 32.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    BackupActionRow(
                        icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                        title = stringResource(R.string.settings_backup_export),
                        enabled = !backup.busy,
                        onClick = { showExportOptions = true },
                        modifier = Modifier.settingsAnchor(SettingsEntries.BackupExport),
                    )
                    RowDivider()
                    BackupActionRow(
                        icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                        title = stringResource(R.string.settings_backup_restore),
                        enabled = !backup.busy,
                        onClick = backup.restore,
                        modifier = Modifier.settingsAnchor(SettingsEntries.BackupRestore),
                    )
                    backup.archiveProgress?.let { progress ->
                        RowDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            val label = when (progress.phase) {
                                SettingsBackupProgress.Phase.HASHING_BOOKS ->
                                    stringResource(
                                        R.string.settings_backup_progress_hashing,
                                        progress.done.toInt(),
                                        (progress.total ?: 0L).toInt(),
                                    )
                                SettingsBackupProgress.Phase.WRITING ->
                                    stringResource(
                                        R.string.settings_backup_progress_writing,
                                        Formatter.formatShortFileSize(context, progress.done),
                                        Formatter.formatShortFileSize(context, progress.total ?: progress.done),
                                    )
                                SettingsBackupProgress.Phase.READING ->
                                    stringResource(
                                        R.string.settings_backup_progress_reading,
                                        Formatter.formatShortFileSize(context, progress.done),
                                    )
                                SettingsBackupProgress.Phase.IMPORTING_BOOKS ->
                                    stringResource(
                                        R.string.settings_backup_progress_importing,
                                        progress.done.toInt(),
                                        (progress.total ?: 0L).toInt(),
                                    )
                            }
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            val total = progress.total
                            if (total != null && total > 0L) {
                                LinearProgressIndicator(
                                    progress = { (progress.done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                            if (progress.phase != SettingsBackupProgress.Phase.IMPORTING_BOOKS) {
                                TextButton(onClick = backup.cancelArchive) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        }
                    }
                }

                SettingsGroup(stringResource(R.string.settings_backup_other_exports)) {
                    BackupActionRow(
                        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        title = stringResource(R.string.books_export_title),
                        subtitle = stringResource(R.string.books_export_detail),
                        enabled = !backup.busy,
                        onClick = backup.exportBooks,
                        modifier = Modifier.settingsAnchor(SettingsEntries.BooksExport),
                    )
                    RowDivider()
                    EvaluationExportButton(enabled = !backup.busy)
                    backup.bookProgress?.let { progress ->
                        RowDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.books_export_progress, progress.processed, progress.total),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (progress.total > 0) {
                                LinearProgressIndicator(
                                    progress = { progress.processed.toFloat() / progress.total },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                            TextButton(onClick = backup.cancelBookExport) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
                backup.status?.takeUnless { it is SettingsBackupUiStatus.Failed }?.let { status ->
                    val message = when (status) {
                        is SettingsBackupUiStatus.Exported -> listOfNotNull(
                            pluralStringResource(
                                R.plurals.settings_backup_exported,
                                status.fonts,
                                status.fonts,
                            ) + " " + stringResource(R.string.settings_backup_annotation_count, status.annotations),
                            if (status.books > 0) {
                                pluralStringResource(R.plurals.settings_backup_exported_books, status.books, status.books)
                            } else { null },
                            if (status.booksSkipped > 0) {
                                pluralStringResource(R.plurals.settings_backup_books_skipped, status.booksSkipped, status.booksSkipped)
                            } else { null },
                            if (status.sessions > 0) {
                                pluralStringResource(R.plurals.settings_backup_exported_sessions, status.sessions, status.sessions)
                            } else { null },
                            if (status.translationBooks > 0 || status.translationBooksSkipped > 0) {
                                stringResource(R.string.whole_book_backup_exported, status.translationBooks, status.translationBooksSkipped)
                            } else { null },
                            if (status.liseurSyncNeedsPairing) {
                                stringResource(
                                    R.string.settings_backup_liseur_sync_exported_notice,
                                    stringResource(R.string.server_kind_liseur_sync),
                                )
                            } else {
                                null
                            },
                        ).joinToString(" ")
                        is SettingsBackupUiStatus.Restored -> {
                            val restoredFonts = if (status.fontsImported > 0) {
                                pluralStringResource(
                                    R.plurals.settings_backup_restored_fonts,
                                    status.fontsImported,
                                    status.fontsImported,
                                )
                            } else {
                                null
                            }
                            val alreadyInstalled = if (status.fontsAlreadyPresent > 0) {
                                pluralStringResource(
                                    R.plurals.settings_backup_already_installed,
                                    status.fontsAlreadyPresent,
                                    status.fontsAlreadyPresent,
                                )
                            } else {
                                null
                            }
                            val fontFailures = if (status.fontFailures > 0) {
                                pluralStringResource(
                                    R.plurals.settings_backup_font_failures,
                                    status.fontFailures,
                                    status.fontFailures,
                                )
                            } else {
                                null
                            }
                            listOfNotNull(
                                stringResource(R.string.settings_backup_restored_settings),
                                if (status.booksImported > 0) {
                                    pluralStringResource(R.plurals.settings_backup_books_restored, status.booksImported, status.booksImported)
                                } else { null },
                                if (status.booksAlreadyPresent > 0) {
                                    pluralStringResource(R.plurals.settings_backup_books_already_here, status.booksAlreadyPresent, status.booksAlreadyPresent)
                                } else { null },
                                if (status.bookFailures > 0) {
                                    pluralStringResource(R.plurals.settings_backup_book_failures, status.bookFailures, status.bookFailures)
                                } else { null },
                                if (status.sessionsAdded > 0) {
                                    pluralStringResource(R.plurals.settings_backup_sessions_restored, status.sessionsAdded, status.sessionsAdded)
                                } else { null },
                                if (status.translationBooks > 0 || status.translationBooksSkipped > 0) {
                                    stringResource(R.string.whole_book_backup_restored, status.translationBooks, status.translationBooksSkipped)
                                } else { null },
                                if (status.positionsRestored > 0) {
                                    pluralStringResource(R.plurals.settings_backup_positions_restored, status.positionsRestored, status.positionsRestored)
                                } else { null },
                                if (status.companionRecapsAdded > 0) {
                                    stringResource(R.string.settings_backup_companion_restored, status.companionRecapsAdded)
                                } else { null },
                                restoredFonts,
                                alreadyInstalled,
                                fontFailures,
                                if (status.annotationsAdded > 0) {
                                    stringResource(R.string.import_annotations_done, pluralStringResource(R.plurals.annotations_backup_mark_count, status.annotationsAdded, status.annotationsAdded))
                                } else if (status.annotationsAlreadyPresent > 0) {
                                    stringResource(R.string.import_annotations_none)
                                } else {
                                    null
                                },
                                if (status.liseurSyncNeedsPairing) {
                                    stringResource(
                                        R.string.settings_backup_liseur_sync_restored_notice,
                                        stringResource(R.string.server_kind_liseur_sync),
                                    )
                                } else {
                                    null
                                },
                            ).joinToString(" ")
                        }
                        is SettingsBackupUiStatus.Failed ->
                            stringResource(status.failure.message)
                        is SettingsBackupUiStatus.PartiallyRestored -> listOfNotNull(
                            stringResource(R.string.settings_backup_error_partial),
                            if (status.liseurSyncNeedsPairing) {
                                stringResource(
                                    R.string.settings_backup_liseur_sync_restored_notice,
                                    stringResource(R.string.server_kind_liseur_sync),
                                )
                            } else {
                                null
                            },
                        ).joinToString(" ")
                        is SettingsBackupUiStatus.BooksExported -> when (val result = status.result) {
                            is BookExportResult.Completed -> stringResource(
                                R.string.books_export_result,
                                result.counts.exported, result.counts.skipped, result.counts.failed,
                            )
                            BookExportResult.Empty -> stringResource(R.string.books_export_empty)
                            is BookExportResult.Failed -> stringResource(
                                when (result.reason) {
                                    BookExportResult.Failure.LIBRARY -> R.string.books_export_error_library
                                    BookExportResult.Failure.FOLDER -> R.string.books_export_error_folder
                                },
                            )
                        }
                        is SettingsBackupUiStatus.BooksExportCancelled -> stringResource(
                            R.string.books_export_cancelled,
                            status.counts.exported, status.counts.skipped, status.counts.failed,
                        )
                        SettingsBackupUiStatus.Cancelled ->
                            stringResource(R.string.settings_backup_cancelled)
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookSelectionDialog(
    candidates: List<BookBackupCandidate>,
    selectedUrls: Set<String>,
    selectedBytes: Long,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val overLimit = selectedBytes > SettingsBackupRepository.MAX_BOOKS_TOTAL_BYTES
    val maxBookFormatted = remember(context) {
        Formatter.formatShortFileSize(context, SettingsBackupRepository.MAX_BOOK_BYTES)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.settings_backup_books_choose))
                Text(
                    pluralStringResource(
                        R.plurals.settings_backup_books_selected,
                        selectedUrls.size,
                        selectedUrls.size,
                        Formatter.formatShortFileSize(context, selectedBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { onSelectAll(true) }, enabled = !busy) {
                        Text(stringResource(R.string.settings_backup_books_select_all))
                    }
                    TextButton(onClick = { onSelectAll(false) }, enabled = !busy) {
                        Text(stringResource(R.string.settings_backup_books_clear))
                    }
                }
                if (overLimit) {
                    Text(
                        stringResource(
                            R.string.settings_backup_books_over_limit,
                            Formatter.formatShortFileSize(context, SettingsBackupRepository.MAX_BOOKS_TOTAL_BYTES),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.url }) { book ->
                        val tooLarge = book.sizeBytes != null && book.sizeBytes > SettingsBackupRepository.MAX_BOOK_BYTES
                        val checked = !tooLarge && book.url in selectedUrls
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = !busy && !tooLarge,
                                    role = Role.Checkbox,
                                    onValueChange = { onToggle(book.url) },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = !busy && !tooLarge,
                            )
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    text = book.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val sizeLabel = if (tooLarge) {
                                    stringResource(R.string.settings_backup_books_too_large, maxBookFormatted)
                                } else {
                                    book.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
                                        ?: stringResource(R.string.settings_backup_books_size_unknown)
                                }
                                val detail = listOfNotNull(book.author?.takeIf { it.isNotBlank() }, sizeLabel).joinToString(" · ")
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (tooLarge) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

private val SettingsBackupFailure.message: Int
    get() = when (this) {
        SettingsBackupFailure.FILE_ACCESS -> R.string.settings_backup_error_file
        SettingsBackupFailure.INVALID_ARCHIVE -> R.string.settings_backup_error_invalid
        SettingsBackupFailure.UNSUPPORTED_VERSION -> R.string.settings_backup_error_version
        SettingsBackupFailure.TOO_LARGE -> R.string.settings_backup_error_size
        SettingsBackupFailure.STORAGE -> R.string.settings_backup_error_storage
        SettingsBackupFailure.PASSWORD -> R.string.settings_backup_password_error
        SettingsBackupFailure.RESTORE -> R.string.settings_backup_error_restore
        SettingsBackupFailure.UNREADABLE_CREDENTIAL -> R.string.settings_backup_error_credential
    }

@Composable
private fun BackupPasswordDialog(
    exporting: Boolean,
    busy: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_backup_password_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (exporting) {
                    Text(stringResource(R.string.settings_backup_password_export))
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.settings_backup_password_title)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (exporting) OutlinedTextField(
                    value = confirmation, onValueChange = { confirmation = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.settings_backup_password_confirm)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (failed && !exporting) Text(
                    stringResource(R.string.settings_backup_password_error), color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotEmpty() && (!exporting || password == confirmation),
                onClick = { onConfirm(password.toCharArray()); password = ""; confirmation = "" },
            ) { Text(stringResource(if (exporting) R.string.settings_backup_export else R.string.settings_backup_restore_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}
