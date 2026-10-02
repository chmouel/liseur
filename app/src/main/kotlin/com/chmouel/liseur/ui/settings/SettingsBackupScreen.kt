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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
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
        AlertDialog(
            onDismissRequest = backup.dismissPreview,
            title = { Text(stringResource(R.string.settings_backup_preview_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_backup_preview_body,
                        ready.preview.settingCount,
                        ready.preview.fontCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = backup.confirmRestore) {
                    Text(stringResource(R.string.settings_backup_restore_action))
                }
            },
            dismissButton = {
                TextButton(onClick = backup.dismissPreview) {
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
                    enabled = true,
                    onClick = backup.export,
                )
                BackupActionRow(
                    icon = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_detail),
                    enabled = true,
                    onClick = backup.restore,
                )
                backup.status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
