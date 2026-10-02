package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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

    backup.preview?.let { ready ->
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
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = backup.confirmRestore, enabled = !backup.busy) {
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
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
                BackupActionRow(
                    icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                    title = stringResource(R.string.settings_backup_export),
                    subtitle = stringResource(R.string.settings_backup_export_detail),
                    enabled = !backup.busy,
                    onClick = backup.export,
                )
                BackupActionRow(
                    icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_detail),
                    enabled = !backup.busy,
                    onClick = backup.restore,
                )
                backup.status?.let { status ->
                    val message = when (status) {
                        is SettingsBackupUiStatus.Exported -> pluralStringResource(
                            R.plurals.settings_backup_exported,
                            status.fonts,
                            status.fonts,
                        )
                        is SettingsBackupUiStatus.Restored -> stringResource(
                            R.string.settings_backup_restored,
                            pluralStringResource(
                                R.plurals.settings_backup_restored_fonts,
                                status.fontsImported,
                                status.fontsImported,
                            ),
                            pluralStringResource(
                                R.plurals.settings_backup_already_installed,
                                status.fontsAlreadyPresent,
                                status.fontsAlreadyPresent,
                            ),
                            pluralStringResource(
                                R.plurals.settings_backup_font_failures,
                                status.fontFailures,
                                status.fontFailures,
                            ),
                        )
                        is SettingsBackupUiStatus.Failed ->
                            stringResource(status.failure.message)
                        SettingsBackupUiStatus.PartiallyRestored ->
                            stringResource(R.string.settings_backup_error_partial)
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
        SettingsBackupFailure.RESTORE -> R.string.settings_backup_error_restore
    }
