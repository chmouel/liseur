package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.LinearProgressIndicator
import com.chmouel.liseur.data.library.BookExportResult
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.chmouel.liseur.R
import com.chmouel.liseur.data.library.SettingsBackupFailure
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    backup: SettingsZipBackupUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var includeConnections by remember { mutableStateOf(false) }
    var includeTranslations by remember { mutableStateOf(true) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    // A wrong password shows only for an attempt made since the restore dialog opened.
    var restoreAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(backup.preview) {
        if (passwordMode == "restore" && backup.preview == null) passwordMode = null
    }
    passwordMode?.let { mode ->
        BackupPasswordDialog(
            exporting = mode == "export",
            busy = backup.busy,
            failed = restoreAttempted &&
                (backup.status as? SettingsBackupUiStatus.Failed)?.failure == SettingsBackupFailure.PASSWORD,
            onDismiss = { if (!backup.busy) passwordMode = null },
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

    backup.preview?.takeIf { passwordMode == null }?.let { ready ->
        val settingCount = pluralStringResource(
            R.plurals.settings_backup_setting_count,
            ready.preview.settingCount,
            ready.preview.settingCount,
        )
        val fontCount = pluralStringResource(
            R.plurals.settings_backup_font_count,
            ready.preview.fontCount,
            ready.preview.fontCount,
        )
        AlertDialog(
            onDismissRequest = backup.dismissPreview,
            title = { Text(stringResource(R.string.settings_backup_preview_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_backup_preview_body,
                        settingCount,
                        fontCount,
                        stringResource(
                            R.string.annotations_backup_summary,
                            pluralStringResource(R.plurals.annotations_backup_mark_count, ready.preview.annotationCount, ready.preview.annotationCount),
                            pluralStringResource(R.plurals.annotations_backup_book_count, ready.preview.annotationBookCount, ready.preview.annotationBookCount),
                        ),
                    ) + (if (ready.preview.positionCount > 0) {
                        "\n\n" + pluralStringResource(R.plurals.settings_backup_positions_preview, ready.preview.positionCount, ready.preview.positionCount)
                    } else { "" }) + (if (ready.preview.companionRecapCount > 0) {
                        "\n\n" + stringResource(R.string.settings_backup_companion_preview, ready.preview.companionRecapCount)
                    } else { "" }) + (if (ready.preview.translationBookCount > 0) {
                        "\n\n" + stringResource(R.string.whole_book_backup_preview, ready.preview.translationBookCount)
                    } else { "" }) + (if (ready.preview.includesConnections) {
                        "\n\n" + stringResource(R.string.settings_backup_connections_replace)
                    } else { "" }) + (if (ready.preview.annotationCount > 0) {
                        "\n\n" + stringResource(
                            R.string.import_preview_body,
                            stringResource(
                                R.string.annotations_backup_summary,
                                pluralStringResource(R.plurals.annotations_backup_mark_count, ready.preview.annotationCount, ready.preview.annotationCount),
                                pluralStringResource(R.plurals.annotations_backup_book_count, ready.preview.annotationBookCount, ready.preview.annotationBookCount),
                            ),
                            pluralStringResource(R.plurals.annotations_backup_matched_books, ready.preview.matchedAnnotationBooks, ready.preview.matchedAnnotationBooks),
                        )
                    } else {
                        ""
                    }),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ready.preview.includesConnections) {
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
                    .padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_backup_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = includeConnections, enabled = !backup.busy, role = Role.Checkbox,
                        onValueChange = { includeConnections = it })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeConnections, onCheckedChange = null, enabled = !backup.busy)
                    Text(stringResource(R.string.settings_backup_include_connections), Modifier.padding(start = 12.dp))
                }
                if (backup.hasTranslations) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = includeTranslations,
                            enabled = !backup.busy,
                            role = Role.Checkbox,
                            onValueChange = { includeTranslations = it },
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeTranslations, onCheckedChange = null, enabled = !backup.busy)
                        Text(stringResource(R.string.whole_book_backup_include), Modifier.padding(start = 12.dp))
                    }
                    Text(
                        stringResource(R.string.whole_book_backup_unencrypted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                BackupActionRow(
                    icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                    title = stringResource(R.string.settings_backup_export),
                    subtitle = stringResource(R.string.settings_backup_export_detail),
                    enabled = !backup.busy,
                    onClick = {
                        if (includeConnections) passwordMode = "export" else backup.export(null, includeTranslations)
                    },
                )
                BackupActionRow(
                    icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_detail),
                    enabled = !backup.busy,
                    onClick = backup.restore,
                )
                BackupActionRow(
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    title = stringResource(R.string.books_export_title),
                    subtitle = stringResource(R.string.books_export_detail),
                    enabled = !backup.busy,
                    onClick = backup.exportBooks,
                )
                backup.bookProgress?.let { progress ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                backup.status?.let { status ->
                    val message = when (status) {
                        is SettingsBackupUiStatus.Exported -> listOfNotNull(
                            pluralStringResource(
                                R.plurals.settings_backup_exported,
                                status.fonts,
                                status.fonts,
                            ) + " " + stringResource(R.string.settings_backup_annotation_count, status.annotations),
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
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
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
        SettingsBackupFailure.EXPORT_PASSWORD_LOST -> R.string.settings_backup_error_export_password_lost
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
                Text(
                    if (exporting) {
                        stringResource(
                            R.string.settings_backup_password_export,
                        )
                    } else {
                        stringResource(R.string.settings_backup_password_restore)
                    },
                )
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
