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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.MetadataOverrideFields
import com.chmouel.liseur.domain.PersonalEvaluation
import com.chmouel.liseur.providers.PlaySettingsEntries
import com.chmouel.liseur.reader.chrome.findActivity
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.settings.search.SettingsFocus
import com.chmouel.liseur.ui.settings.search.settingsAnchor
import kotlinx.coroutines.launch

/** Short status shown on the Settings > Book server row when Google Drive is linked. */
@Composable
internal fun driveConnectedSummary(controller: DriveAccountController): String? {
    val state by controller.uiState.collectAsState(initial = DriveSettingsUiState())
    val account = state.account ?: return null
    val statusLabel = statusLabelFor(account.state)
    return "${stringResource(R.string.drive_sync_title)} · ${
        stringResource(R.string.drive_sync_account_summary, account.displayName, statusLabel)
    }"
}

/** Server-type selector card matching [com.chmouel.liseur.ui.settings.ServerKindRow]. */
@Composable
internal fun DriveBookServerSelectedRow(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            overlineContent = { Text(stringResource(R.string.server_kind)) },
            headlineContent = { Text(stringResource(R.string.drive_sync_title)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.drive_sync_settings_summary))
                    Text(
                        stringResource(R.string.server_sync_exact),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.server_kind_change),
                )
            },
        )
    }
}

/** Switch entry on a connected main server; the controller confirms before disconnecting it. */
@Composable
internal fun DriveBookServerSwitchAction(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.drive_sync_use_instead))
    }
}

/** First option row in [com.chmouel.liseur.ui.settings.ServerKindSheet] on Play builds. */
@Composable
internal fun DriveBookServerPickerRow(
    selected: Boolean,
    onPick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onPick,
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(R.string.drive_sync_title)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.drive_sync_settings_summary))
                Text(
                    stringResource(R.string.server_sync_exact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.CloudSync,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = null)
        },
    )
}

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
        if (state.unresolvedConflictsCount > 0) {
            val conflictsLabel = pluralStringResource(
                R.plurals.drive_sync_conflicts_count,
                state.unresolvedConflictsCount,
                state.unresolvedConflictsCount,
            )
            stringResource(
                R.string.drive_sync_account_summary_conflicts,
                account.displayName,
                statusLabel,
                conflictsLabel,
            )
        } else {
            stringResource(R.string.drive_sync_account_summary, account.displayName, statusLabel)
        }
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
        modifier = Modifier.settingsAnchor(PlaySettingsEntries.GoogleDrive),
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

    val activity = LocalContext.current.findActivity()
    DisposableEffect(controller) {
        onDispose {
            if (activity?.isChangingConfigurations != true) controller.abandonInteractiveFlow()
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

    if (state.conflictsDialogVisible) {
        DriveConflictsDialog(
            groups = state.unresolvedConflictGroups,
            resolving = state.resolvingConflict,
            onResolve = { group, choice ->
                scope.launch { controller.resolveConflictGroup(group, choice) }
            },
            onDismiss = { controller.closeConflictsDialog() },
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
                    headlineContent = { Text(account.displayName) },
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

            val errorText = state.errorMessageRes?.let { stringResource(it) }
                ?: state.errorMessage?.takeIf { it.isNotBlank() }
                ?: state.account?.lastError?.takeIf {
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
                            if (state.unresolvedConflictsCount > 0) {
                                Button(onClick = { controller.openConflictsDialog() }) {
                                    Text(
                                        "${stringResource(R.string.drive_sync_resolve_conflicts)} (${state.unresolvedConflictsCount})",
                                    )
                                }
                            }
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

    val vocabularyLabel = pluralStringResource(
        R.plurals.drive_sync_vocabulary_count,
        confirmation.localSummary.vocabularyWordsCount,
        confirmation.localSummary.vocabularyWordsCount,
    )

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.drive_sync_switch_title, confirmation.targetDisplayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.drive_sync_switch_body_summary,
                        confirmation.targetDisplayName,
                        booksLabel,
                        notesLabel,
                        sessionsLabel,
                        vocabularyLabel,
                    ),
                )
                if (confirmation.isAccountSwitch && confirmation.previousAccount != null) {
                    Text(
                        text = stringResource(
                            R.string.drive_sync_switch_body_keep_previous,
                            confirmation.previousAccount.displayName,
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
private fun DriveConflictsDialog(
    groups: List<DriveConflictUiGroup>,
    resolving: Boolean,
    onResolve: (DriveConflictUiGroup, DriveConflictChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!resolving) onDismiss() },
        title = { Text(stringResource(R.string.drive_sync_conflicts_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.drive_sync_conflicts_dialog_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(groups, key = { it.groupId }) { group ->
                        Surface(
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(12.dp),
                            ) {
                                val domainLabel = conflictDomainLabel(group.domain)
                                val fieldLabel = conflictFieldLabel(group.domain, group.fieldLabel)
                                val subtitle = listOfNotNull(domainLabel, fieldLabel).joinToString(" · ")
                                Text(
                                    text = group.bookTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                val localValue = if (group.localDeleted) {
                                    stringResource(R.string.drive_sync_conflict_deleted_value)
                                } else {
                                    group.localSummary
                                }
                                val remoteValue = if (group.remoteDeleted) {
                                    stringResource(R.string.drive_sync_conflict_deleted_value)
                                } else {
                                    group.remoteSummary
                                }
                                Text(
                                    text = "${stringResource(R.string.drive_sync_conflict_local_label)}: $localValue",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "${stringResource(R.string.drive_sync_conflict_remote_label)}: $remoteValue",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) {
                                    if (group.kind == DriveConflict.KIND_DELETE_EDIT) {
                                        val keepLabel = if (group.domain == DriveWireCodec.DOMAIN_ANNOTATION) {
                                            stringResource(R.string.drive_sync_conflict_keep_edited_note)
                                        } else {
                                            stringResource(R.string.drive_sync_conflict_keep_edited)
                                        }
                                        val deleteLabel = if (group.domain == DriveWireCodec.DOMAIN_ANNOTATION) {
                                            stringResource(R.string.drive_sync_conflict_delete_note)
                                        } else {
                                            stringResource(R.string.drive_sync_conflict_delete_value)
                                        }
                                        Button(
                                            onClick = { onResolve(group, DriveConflictChoice.KEEP_EDITED) },
                                            enabled = !resolving,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(keepLabel)
                                        }
                                        OutlinedButton(
                                            onClick = { onResolve(group, DriveConflictChoice.DELETE) },
                                            enabled = !resolving,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        val keepMineLabel = if (group.domain == DriveWireCodec.DOMAIN_SERIES) {
                                            stringResource(R.string.drive_sync_conflict_series_keep_mine)
                                        } else {
                                            stringResource(R.string.drive_sync_conflict_keep_mine)
                                        }
                                        val keepOtherLabel = if (group.domain == DriveWireCodec.DOMAIN_SERIES) {
                                            stringResource(R.string.drive_sync_conflict_series_keep_other)
                                        } else {
                                            stringResource(R.string.drive_sync_conflict_keep_other)
                                        }
                                        Button(
                                            onClick = { onResolve(group, DriveConflictChoice.KEEP_LOCAL) },
                                            enabled = !resolving,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(keepMineLabel)
                                        }
                                        OutlinedButton(
                                            onClick = { onResolve(group, DriveConflictChoice.KEEP_REMOTE) },
                                            enabled = !resolving,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(keepOtherLabel)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !resolving) {
                Text(stringResource(R.string.drive_sync_conflicts_dialog_close))
            }
        },
    )
}

/** Play-only "Delete from synced library" row inside a book's Manage sheet when Drive is active. */
@Composable
internal fun DriveBookManageAction(
    controller: DriveAccountController,
    book: Book,
    onDone: () -> Unit,
) {
    if (book.browseServerId != null) return
    val state by controller.uiState.collectAsState(initial = DriveSettingsUiState())
    if (state.account?.state != DriveAccount.STATE_ACTIVE) return

    val scope = rememberCoroutineScope()
    var confirmVisible by remember(book.url) { mutableStateOf(false) }
    var deleteFailed by remember(book.url) { mutableStateOf(false) }

    if (confirmVisible) {
        val isDriveRemoteRow = book.url.startsWith(DriveWorkIdentity.DRIVE_BOOK_URL_PREFIX)
        AlertDialog(
            onDismissRequest = { confirmVisible = false },
            title = { Text(stringResource(R.string.drive_sync_delete_from_library_title)) },
            text = {
                Text(
                    text = stringResource(
                        if (isDriveRemoteRow) {
                            R.string.drive_sync_delete_from_library_warning_remote
                        } else {
                            R.string.drive_sync_delete_from_library_warning_local
                        },
                        book.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmVisible = false
                        deleteFailed = false
                        scope.launch {
                            if (controller.deleteFromSyncedLibrary(book)) {
                                onDone()
                            } else {
                                deleteFailed = true
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.drive_sync_delete_from_library_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmVisible = false }) {
                    Text(stringResource(R.string.drive_sync_switch_cancel))
                }
            },
        )
    }

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { confirmVisible = true }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.drive_sync_delete_from_library),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (deleteFailed) {
        Text(
            text = stringResource(R.string.drive_sync_delete_from_library_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun conflictDomainLabel(domain: String): String = when (domain) {
    DriveWireCodec.DOMAIN_ANNOTATION -> stringResource(R.string.drive_sync_conflict_domain_annotation)
    DriveWireCodec.DOMAIN_EVALUATION -> stringResource(R.string.drive_sync_conflict_domain_evaluation)
    DriveWireCodec.DOMAIN_METADATA -> stringResource(R.string.drive_sync_conflict_domain_metadata)
    DriveWireCodec.DOMAIN_SERIES -> stringResource(R.string.drive_sync_conflict_domain_series)
    else -> domain
}

@Composable
private fun conflictFieldLabel(domain: String, field: String?): String? {
    if (field.isNullOrBlank() || domain == DriveWireCodec.DOMAIN_SERIES) return null
    return when (field) {
        PersonalEvaluation.RATING -> stringResource(R.string.drive_sync_conflict_field_rating)
        PersonalEvaluation.REVIEW -> stringResource(R.string.drive_sync_conflict_field_review)
        MetadataOverrideFields.TITLE -> stringResource(R.string.drive_sync_conflict_field_title)
        MetadataOverrideFields.AUTHOR -> stringResource(R.string.drive_sync_conflict_field_author)
        MetadataOverrideFields.DESCRIPTION -> stringResource(R.string.drive_sync_conflict_field_description)
        MetadataOverrideFields.COVER -> stringResource(R.string.drive_sync_conflict_field_cover)
        else -> field
    }
}

@Composable
private fun statusLabelFor(state: String): String = when (state) {
    DriveAccount.STATE_ACTIVE -> stringResource(R.string.drive_sync_status_active)
    DriveAccount.STATE_NEEDS_RECONNECT -> stringResource(R.string.drive_sync_status_needs_reconnect)
    else -> stringResource(R.string.drive_sync_status_paused)
}
