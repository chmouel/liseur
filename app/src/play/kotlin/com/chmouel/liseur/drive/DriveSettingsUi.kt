package com.chmouel.liseur.drive

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.providers.PlaySettingsEntries
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.settings.search.SettingsFocus
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import kotlinx.coroutines.launch

/** Settings > Library entry linking straight to the Google Drive section on Services. */
@Composable
internal fun DriveSettingsEntry(
    controller: DriveAccountController,
    onOpen: () -> Unit,
) {
    val state by controller.uiState.collectAsState(initial = DriveSettingsUiState())
    val account = state.account
    val subtitle = if (account != null) {
        val statusLabel = statusLabelFor(account.state)
        stringResource(R.string.drive_sync_account_summary, account.email, statusLabel)
    } else {
        stringResource(R.string.drive_sync_settings_summary)
    }
    RowDivider()
    ConnectionRow(
        icon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
        title = stringResource(R.string.drive_sync_title),
        subtitle = subtitle,
        onClick = {
            SettingsFocus.key = PlaySettingsEntries.GoogleDrive.key
            onOpen()
        },
        modifier = Modifier.settingsAnchor(PlaySettingsEntries.GoogleDriveSettings),
    )
}

/** Play-only Google Drive Sync Settings group rendered on the Services page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DriveSettingsSection(controller: DriveAccountController) {
    val state by controller.uiState.collectAsState(initial = DriveSettingsUiState())
    val scope = rememberCoroutineScope()

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        scope.launch {
            controller.onAuthorizationActivityResult(result.resultCode, result.data)
        }
    }

    val pendingIntent = state.pendingAuthIntent
    LaunchedEffect(pendingIntent) {
        if (pendingIntent != null) {
            controller.consumePendingAuthIntent()
            val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            authLauncher.launch(request)
        }
    }

    state.pendingConfirmation?.let { confirmation ->
        DriveAccountSwitchDialog(
            confirmation = confirmation,
            busy = state.busy,
            onConfirm = { scope.launch { controller.confirmPendingSwitch() } },
            onCancel = { scope.launch { controller.cancelPendingSwitch() } },
        )
    }

    if (state.uploadPickerVisible) {
        DriveUploadFilesDialog(
            candidates = state.uploadCandidates,
            unmeteredOnly = !state.meteredTransfers,
            onToggleUnmeteredOnly = { unmeteredOnly ->
                scope.launch { controller.setMeteredTransfers(allowMetered = !unmeteredOnly) }
            },
            onConfirm = { selected ->
                scope.launch { controller.confirmUploadSelection(selected) }
            },
            onDismiss = { controller.closeUploadPicker() },
        )
    }

    SettingsGroup(stringResource(R.string.drive_sync_title)) {
        Column(Modifier.settingsAnchor(PlaySettingsEntries.GoogleDrive)) {
            Text(
                text = stringResource(R.string.drive_sync_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            )

            val account = state.account
            if (account != null) {
                RowDivider()
                val statusLabel = statusLabelFor(account.state)
                val statusColor = when (account.state) {
                    DriveAccount.STATE_NEEDS_RECONNECT -> MaterialTheme.colorScheme.error
                    DriveAccount.STATE_ACTIVE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(account.email) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${stringResource(R.string.drive_sync_status_label)}: $statusLabel",
                                color = statusColor,
                            )
                            if (account.state == DriveAccount.STATE_NEEDS_RECONNECT) {
                                Text(
                                    text = stringResource(R.string.drive_sync_needs_reconnect_detail),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            val lastSyncText = account.lastSuccessAt?.let { timestamp ->
                                DateUtils.getRelativeTimeSpanString(
                                    timestamp,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString()
                            } ?: stringResource(R.string.drive_sync_last_sync_never)
                            Text(
                                text = "${stringResource(R.string.drive_sync_last_sync_label)}: $lastSyncText",
                            )
                            val uploadsText = if (state.pendingUploadsCount > 0) {
                                pluralStringResource(
                                    R.plurals.drive_sync_pending_uploads_count,
                                    state.pendingUploadsCount,
                                    state.pendingUploadsCount,
                                )
                            } else {
                                stringResource(R.string.drive_sync_none)
                            }
                            Text(
                                text = "${stringResource(R.string.drive_sync_pending_uploads_label)}: $uploadsText",
                            )
                            val conflictsText = if (state.unresolvedConflictsCount > 0) {
                                pluralStringResource(
                                    R.plurals.drive_sync_conflicts_count,
                                    state.unresolvedConflictsCount,
                                    state.unresolvedConflictsCount,
                                )
                            } else {
                                stringResource(R.string.drive_sync_none)
                            }
                            Text(
                                text = "${stringResource(R.string.drive_sync_conflicts_label)}: $conflictsText",
                                color = if (state.unresolvedConflictsCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.Unspecified
                                },
                            )
                        }
                    },
                )
            }

            val errorText = state.errorMessage ?: state.account?.lastError?.takeIf {
                state.account?.state != DriveAccount.STATE_NEEDS_RECONNECT
            }
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.pausedSchemaDeviceLabels.isNotEmpty()) {
                RowDivider()
                Text(
                    text = stringResource(
                        R.string.drive_sync_upgrade_required,
                        state.pausedSchemaDeviceLabels.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.pendingWorkMatches.isNotEmpty()) {
                RowDivider()
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.drive_sync_match_section_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.pendingWorkMatches.forEach { match ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.drive_sync_match_body,
                                    match.bookTitle,
                                    match.candidateTitle ?: match.candidateWorkKey,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { scope.launch { controller.confirmWorkMatch(match) } },
                                ) {
                                    Text(stringResource(R.string.drive_sync_match_merge))
                                }
                                TextButton(
                                    onClick = { scope.launch { controller.rejectWorkMatch(match) } },
                                ) {
                                    Text(stringResource(R.string.drive_sync_match_keep_separate))
                                }
                            }
                        }
                    }
                }
            }

            if (state.busy || state.syncingNow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BusyIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(
                            if (state.syncingNow) R.string.drive_sync_syncing else R.string.drive_sync_connecting,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    when (account?.state) {
                        null -> {
                            Button(onClick = { scope.launch { controller.beginConnect(switchAccount = false) } }) {
                                Text(stringResource(R.string.drive_sync_connect))
                            }
                        }
                        DriveAccount.STATE_ACTIVE -> {
                            Button(onClick = { scope.launch { controller.openUploadPicker() } }) {
                                Text(stringResource(R.string.drive_sync_upload_epubs))
                            }
                            OutlinedButton(onClick = { scope.launch { controller.syncNow() } }) {
                                Text(stringResource(R.string.drive_sync_now))
                            }
                            OutlinedButton(onClick = { scope.launch { controller.disconnect() } }) {
                                Text(stringResource(R.string.drive_sync_disconnect))
                            }
                            OutlinedButton(onClick = { scope.launch { controller.beginConnect(switchAccount = true) } }) {
                                Text(stringResource(R.string.drive_sync_switch_account))
                            }
                        }
                        DriveAccount.STATE_NEEDS_RECONNECT -> {
                            Button(onClick = { scope.launch { controller.beginConnect(switchAccount = false) } }) {
                                Text(stringResource(R.string.drive_sync_reconnect))
                            }
                            OutlinedButton(onClick = { scope.launch { controller.beginConnect(switchAccount = true) } }) {
                                Text(stringResource(R.string.drive_sync_switch_account))
                            }
                            TextButton(onClick = { scope.launch { controller.disconnect() } }) {
                                Text(stringResource(R.string.drive_sync_disconnect))
                            }
                        }
                        else -> { // PAUSED
                            Button(onClick = { scope.launch { controller.beginConnect(switchAccount = false) } }) {
                                Text(stringResource(R.string.drive_sync_reconnect))
                            }
                            OutlinedButton(onClick = { scope.launch { controller.beginConnect(switchAccount = true) } }) {
                                Text(stringResource(R.string.drive_sync_switch_account))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveAccountSwitchDialog(
    confirmation: PendingAccountSwitchConfirmation,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val booksLabel = pluralStringResource(
        R.plurals.drive_sync_books_count,
        confirmation.localSummary.booksCount,
        confirmation.localSummary.booksCount,
    )
    val notesLabel = pluralStringResource(
        R.plurals.drive_sync_notes_count,
        confirmation.localSummary.notesCount,
        confirmation.localSummary.notesCount,
    )
    val sessionsLabel = pluralStringResource(
        R.plurals.drive_sync_sessions_count,
        confirmation.localSummary.readingSessionsCount,
        confirmation.localSummary.readingSessionsCount,
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.drive_sync_switch_title, confirmation.targetEmail)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.drive_sync_switch_body_summary,
                        confirmation.targetEmail,
                        booksLabel,
                        notesLabel,
                        sessionsLabel,
                    ),
                )
                if (confirmation.isAccountSwitch && confirmation.previousAccount != null) {
                    Text(
                        text = stringResource(
                            R.string.drive_sync_switch_body_keep_previous,
                            confirmation.previousAccount.email,
                        ),
                    )
                }
                if (confirmation.mainServerConnected) {
                    Text(
                        text = stringResource(R.string.drive_sync_switch_body_disconnect_server),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text(stringResource(R.string.drive_sync_switch_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.drive_sync_switch_cancel))
            }
        },
    )
}

@Composable
private fun DriveUploadFilesDialog(
    candidates: List<DriveUploadCandidate>,
    unmeteredOnly: Boolean,
    onToggleUnmeteredOnly: (Boolean) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val selectable = remember(candidates) { candidates.filter { !it.isUploaded } }
    var selectedUrls by remember(candidates) {
        mutableStateOf(
            candidates
                .filter { it.isPendingOrUploading }
                .map { it.bookUrl }
                .toSet(),
        )
    }
    val estimatedBytes = remember(candidates, selectedUrls) {
        candidates.filter { it.bookUrl in selectedUrls }.sumOf { it.sizeBytes }
    }
    val formattedBytes = Formatter.formatShortFileSize(context, estimatedBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_sync_upload_epubs_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.drive_sync_upload_epubs_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.drive_sync_upload_epubs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.drive_sync_upload_epubs_selection_summary,
                                selectedUrls.size,
                                selectedUrls.size,
                                formattedBytes,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { selectedUrls = selectable.map { it.bookUrl }.toSet() },
                                enabled = selectable.isNotEmpty(),
                            ) {
                                Text(stringResource(R.string.drive_sync_upload_epubs_select_all))
                            }
                            TextButton(
                                onClick = { selectedUrls = emptySet() },
                                enabled = selectedUrls.isNotEmpty(),
                            ) {
                                Text(stringResource(R.string.drive_sync_upload_epubs_clear))
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                    ) {
                        items(candidates, key = { it.bookUrl }) { item ->
                            val checked = item.isUploaded || item.bookUrl in selectedUrls
                            val toggle = {
                                if (!item.isUploaded) {
                                    selectedUrls = if (item.bookUrl in selectedUrls) {
                                        selectedUrls - item.bookUrl
                                    } else {
                                        selectedUrls + item.bookUrl
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !item.isUploaded, onClick = toggle)
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = if (item.isUploaded) null else { _ -> toggle() },
                                    enabled = !item.isUploaded,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val sizeLabel = Formatter.formatShortFileSize(context, item.sizeBytes)
                                    val statusText = when (item.uploadState) {
                                        DriveUpload.STATE_UPLOADED ->
                                            stringResource(R.string.drive_sync_upload_epubs_status_uploaded)
                                        DriveUpload.STATE_PENDING ->
                                            stringResource(R.string.drive_sync_upload_epubs_status_pending)
                                        DriveUpload.STATE_UPLOADING ->
                                            stringResource(R.string.drive_sync_upload_epubs_status_uploading)
                                        DriveUpload.STATE_FAILED ->
                                            stringResource(R.string.drive_sync_upload_epubs_status_failed)
                                        else -> null
                                    }
                                    val detail = listOfNotNull(
                                        item.author?.takeIf { it.isNotBlank() },
                                        sizeLabel,
                                        statusText,
                                    ).joinToString(" · ")
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                RowDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.drive_sync_upload_epubs_unmetered_only),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.drive_sync_upload_epubs_unmetered_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = unmeteredOnly,
                        onCheckedChange = onToggleUnmeteredOnly,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedUrls) },
                enabled = selectedUrls.isNotEmpty(),
            ) {
                Text(stringResource(R.string.drive_sync_upload_epubs_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.drive_sync_switch_cancel))
            }
        },
    )
}

@Composable
private fun statusLabelFor(state: String): String = when (state) {
    DriveAccount.STATE_ACTIVE -> stringResource(R.string.drive_sync_status_active)
    DriveAccount.STATE_NEEDS_RECONNECT -> stringResource(R.string.drive_sync_status_needs_reconnect)
    else -> stringResource(R.string.drive_sync_status_paused)
}
