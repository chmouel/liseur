package com.chmouel.liseur.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.chmouel.liseur.data.calibre.BulkBatch
import com.chmouel.liseur.data.calibre.BulkDownloadEstimate
import com.chmouel.liseur.data.calibre.BulkStopReason
import com.chmouel.liseur.data.calibre.SpaceVerdict
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.calibre.StorageUse
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncReport
import com.chmouel.liseur.ui.messageRes
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.settings.UploadPolicy
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/**
 * One screen for the whole book-server account: the user picks a kind of
 * server, gives an address and a way in, and the app works out the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerAccountScreen(
    state: ServerAccountUiState,
    onKindChange: (ServerKind) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onLiseurSyncSignInChange: (LiseurSyncSignIn) -> Unit,
    onDeviceTokenChange: (String) -> Unit,
    onConnect: (Boolean) -> Unit,
    onRetryCapabilities: () -> Unit,
    onKoboToken: (String) -> Unit,
    onSetUploadPolicy: (UploadPolicy) -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    onAnswerConfirmation: (String, Boolean) -> Unit,
    onAskDownloadAll: () -> Unit,
    onDismissDownloadAll: () -> Unit,
    onDownloadAll: () -> Unit,
    onCancelDownloadAll: () -> Unit,
    onDismissBatch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    // widthIn must come before fillMaxWidth: fillMaxSize would
                    // pin the width to the window first, leaving the cap with a
                    // fixed constraint it cannot narrow.
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // An address and a password are short things to type, and a
                    // field the width of a tablet makes them look like neither.
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val server = state.server
                if (server == null) {
                    if (state.lostToRestore) {
                        Notice(
                            text = stringResource(R.string.server_lost_to_restore),
                            tone = NoticeTone.PROBLEM,
                        )
                    }
                    ConnectForm(
                        state = state,
                        onKindChange = onKindChange,
                        onUrlChange = onUrlChange,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                        onApiKeyChange = onApiKeyChange,
                        onLiseurSyncSignInChange = onLiseurSyncSignInChange,
                        onDeviceTokenChange = onDeviceTokenChange,
                        onConnect = onConnect,
                    )
                } else {
                    ConnectedCard(
                        server = server,
                        storage = state.storage,
                        syncStatus = state.syncStatus,
                        syncReport = state.syncReport,
                        identity = state.identity,
                        onSyncNow = onSyncNow,
                        busy = state.connecting,
                        onRetryCapabilities = onRetryCapabilities,
                        onKoboToken = onKoboToken,
                        onDisconnect = onDisconnect,
                        uploadPolicy = state.uploadPolicy,
                        onSetUploadPolicy = onSetUploadPolicy,
                        batch = state.bulkBatch,
                        estimating = state.estimating,
                        onAskDownloadAll = onAskDownloadAll,
                        onCancelDownloadAll = onCancelDownloadAll,
                        onDismissBatch = onDismissBatch,
                    )
                    if (server.kind == ServerKind.LISEUR_SYNC) {
                        if (state.confirmations.isNotEmpty()) {
                            SameBookCard(state.confirmations, onAnswerConfirmation)
                        }
                        if (state.ambiguities > 0) {
                            Text(
                                pluralStringResource(
                                    R.plurals.sync_server_ambiguous,
                                    state.ambiguities,
                                    state.ambiguities,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val secretNote = when (server?.kind ?: state.kind) {
                    ServerKind.CALIBRE, ServerKind.GRIMMORY ->
                        R.string.server_password_storage_note
                    ServerKind.KOMGA -> R.string.server_api_key_storage_note
                    ServerKind.LISEUR_SYNC -> R.string.server_token_storage_note
                }
                Text(
                    stringResource(secretNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }

    state.bulkEstimate?.let { estimate ->
        DownloadAllDialog(
            estimate = estimate,
            onConfirm = onDownloadAll,
            onDismiss = onDismissDownloadAll,
        )
    }
}

/**
 * Says what fetching everything will cost, before it starts.
 *
 * A batch that will not fit is still offered rather than refused: it
 * will stop of its own accord once the device runs low, and the books it
 * did fetch are worth having. What the reader needs is to know that in
 * advance, not to be argued with.
 */
@Composable
private fun DownloadAllDialog(
    estimate: BulkDownloadEstimate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val free = Formatter.formatShortFileSize(context, estimate.freeBytes)
    val size = estimate.bytes?.let { Formatter.formatShortFileSize(context, it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (estimate.count == 0) {
                    stringResource(R.string.download_all)
                } else {
                    pluralStringResource(
                        R.plurals.download_all_confirm,
                        estimate.count,
                        estimate.count,
                    )
                },
            )
        },
        text = {
            Text(
                when {
                    estimate.count == 0 -> stringResource(R.string.download_all_none)
                    size == null -> stringResource(R.string.download_all_size_unknown, free)
                    estimate.verdict == SpaceVerdict.WILL_NOT_FIT ->
                        stringResource(R.string.download_all_will_not_fit, size, free)
                    estimate.verdict == SpaceVerdict.TIGHT ->
                        stringResource(R.string.download_all_tight, size, free)
                    else -> stringResource(R.string.download_all_size, size, free)
                },
            )
        },
        confirmButton = {
            if (estimate.count > 0) {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.download_all_start))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (estimate.count == 0) R.string.close else R.string.cancel,
                    ),
                )
            }
        },
    )
}

/**
 * The bulk download, whichever half of its life it is in: a bar and a
 * way to stop it while it runs, a summary and a way to dismiss that
 * once it has ended.
 *
 * Partial success is the ordinary outcome here — a batch that stopped
 * for room, or that the reader stopped, still leaves books behind that
 * are worth having — so it is reported as a count rather than as a
 * failure.
 *
 * A batch that has been asked to stop is not over yet: there is work
 * still to cancel and rows still to put back, and dismissing it in that
 * moment would take away the record the teardown navigates by. It says
 * why it is stopping and offers nothing until it has.
 */
@Composable
private fun BulkDownloadStatus(
    batch: BulkBatch,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val finished = batch.done + batch.failed
    val stopping = batch.stopReason != null && !batch.settled
    if (batch.settled || batch.stopReason != null || finished >= batch.total) {
        Text(
            text = if (stopping) {
                stringResource(R.string.download_all_stopping)
            } else {
                stringResource(R.string.download_all_done, batch.done, batch.total)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        batch.stopReason?.let { reason ->
            Notice(
                text = stringResource(
                    when (reason) {
                        BulkStopReason.CANCELLED -> R.string.download_all_stopped_cancelled
                        BulkStopReason.OUT_OF_SPACE -> R.string.download_all_stopped_space
                        BulkStopReason.ACCOUNT_CHANGED -> R.string.download_all_stopped_account
                    },
                ),
                tone = if (reason == BulkStopReason.CANCELLED) {
                    NoticeTone.NEUTRAL
                } else {
                    NoticeTone.PROBLEM
                },
            )
        }
        if (stopping) return
        if (batch.failed > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.download_all_failed,
                    batch.failed,
                    batch.failed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.dismiss))
        }
        return
    }

    Text(
        text = stringResource(R.string.download_all_progress, finished, batch.total),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!LocalEInk.current) {
        LinearProgressIndicator(
            progress = { if (batch.total == 0) 0f else finished.toFloat() / batch.total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TextButton(onClick = onCancel) {
        Text(stringResource(R.string.download_all_cancel))
    }
}

/**
 * What this kind of server is, before the reader is asked for its
 * address: a face to recognise it by, a line on what it does, and
 * somewhere to go for the one they have not got yet.
 */
/**
 * One concern of a connected server — what it downloads, what it syncs,
 * what it accepts — kept in its own card, because the settled screen is
 * long enough that a flat run of paragraphs gives the reader nothing to
 * navigate by.
 */
@Composable
private fun ServerSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleSmall)
                content()
            },
        )
    }
}

/** A line of fact about the server. */
@Composable
private fun DetailLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectForm(
    state: ServerAccountUiState,
    onKindChange: (ServerKind) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onLiseurSyncSignInChange: (LiseurSyncSignIn) -> Unit,
    onDeviceTokenChange: (String) -> Unit,
    onConnect: (Boolean) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    // The row and its link are one thing, so they share a gap rather
    // than each spending one of the column's: the whole point of the
    // redesign is the height at the top of this form.
    Column {
        ServerKindRow(
            kind = state.kind,
            enabled = !state.connecting,
            onClick = { picking = true },
        )
        TextButton(
            onClick = { runCatching { uriHandler.openUri(state.kind.homeUrl()) } },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(stringResource(state.kind.linkRes()))
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (picking) {
        ServerKindSheet(
            selected = state.kind,
            onPick = {
                onKindChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    OutlinedTextField(
        value = state.url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.server_url)) },
        placeholder = { Text("books.example.com") },
        singleLine = true,
        enabled = !state.connecting,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    var secretShown by rememberSaveable { mutableStateOf(false) }
    val secretToggle: @Composable () -> Unit = {
        IconButton(onClick = { secretShown = !secretShown }) {
            Icon(
                imageVector = if (secretShown) {
                    Icons.Outlined.VisibilityOff
                } else {
                    Icons.Outlined.Visibility
                },
                contentDescription = stringResource(
                    if (secretShown) R.string.hide_password else R.string.show_password,
                ),
            )
        }
    }
    val secretMask = if (secretShown) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }
    when (state.kind) {
        ServerKind.CALIBRE, ServerKind.GRIMMORY -> {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.server_username)) },
                singleLine = true,
                enabled = !state.connecting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.server_password)) },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = secretMask,
                trailingIcon = secretToggle,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.kind == ServerKind.GRIMMORY) {
                // The one thing worth saying next to the fields
                // themselves. Grimmory's browser login is right there
                // and does not work here, and nothing about the refusal
                // says why.
                Text(
                    stringResource(R.string.server_opds_user_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ServerKind.KOMGA -> {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.server_api_key)) },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = secretMask,
                trailingIcon = secretToggle,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.server_api_key_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ServerKind.LISEUR_SYNC -> {
            LiseurSyncForm(
                state = state,
                onSignInChange = onLiseurSyncSignInChange,
                onUsernameChange = onUsernameChange,
                onPasswordChange = onPasswordChange,
                onDeviceTokenChange = onDeviceTokenChange,
                secretMask = secretMask,
                secretToggle = secretToggle,
            )
        }
    }

    state.error?.let { error ->
        Notice(
            text = stringResource(error.messageRes(state.kind)),
            tone = NoticeTone.PROBLEM,
        )
        if (error == AccountError.UNREACHABLE_TRY_HTTP) {
            var confirmingHttp by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { confirmingHttp = true }) {
                Text(stringResource(R.string.server_try_http))
            }
            if (confirmingHttp) {
                AlertDialog(
                    onDismissRequest = { confirmingHttp = false },
                    title = { Text(stringResource(R.string.server_http_title)) },
                    text = { Text(stringResource(R.string.server_http_warning)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmingHttp = false
                                onConnect(true)
                            },
                        ) {
                            Text(stringResource(R.string.server_http_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingHttp = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
        }
    }

    Button(
        onClick = { onConnect(false) },
        enabled = !state.connecting && state.url.isNotBlank() && when (state.kind) {
            ServerKind.CALIBRE, ServerKind.GRIMMORY ->
                state.username.isNotBlank() && state.password.isNotBlank()
            ServerKind.KOMGA -> state.apiKey.isNotBlank()
            ServerKind.LISEUR_SYNC -> when (state.liseurSyncSignIn) {
                LiseurSyncSignIn.PASSWORD ->
                    state.username.isNotBlank() && state.password.isNotBlank()
                LiseurSyncSignIn.TOKEN -> state.deviceToken.isNotBlank()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.connecting) {
            BusyIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.server_connect))
        }
    }
}

@Composable
private fun ConnectedCard(
    server: RemoteServer,
    storage: StorageUse,
    syncStatus: PositionSyncStatus,
    syncReport: SyncReport,
    identity: SyncIdentity?,
    busy: Boolean,
    onRetryCapabilities: () -> Unit,
    onKoboToken: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    uploadPolicy: UploadPolicy,
    onSetUploadPolicy: (UploadPolicy) -> Unit,
    batch: BulkBatch?,
    estimating: Boolean,
    onAskDownloadAll: () -> Unit,
    onCancelDownloadAll: () -> Unit,
    onDismissBatch: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The address is the one thing here the reader may want to
                // act on rather than read: it is where the library they are
                // borrowing from actually lives.
                val uriHandler = LocalUriHandler.current
                val open = stringResource(R.string.server_open_in_browser)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = open) {
                            runCatching { uriHandler.openUri(server.baseUrl) }
                        },
                ) {
                    Text(
                        server.baseUrl.substringAfter("://"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                server.username.orEmpty()
                    .ifBlank { stringResource(server.kind.labelRes()) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DetailLine(
                text = if (storage.count == 0) {
                    stringResource(R.string.server_storage_empty)
                } else {
                    pluralStringResource(
                        R.plurals.server_storage,
                        storage.count,
                        storage.count,
                        Formatter.formatShortFileSize(LocalContext.current, storage.bytes),
                    )
                },
            )
        }
    }

    if (!server.canDownload) {
        Notice(
            text = stringResource(
                when (server.kind) {
                    ServerKind.CALIBRE -> R.string.server_no_download_right
                    ServerKind.KOMGA -> R.string.server_no_download_right_komga
                    ServerKind.LISEUR_SYNC -> R.string.server_no_download_right_liseur_sync
                    // Unreachable in practice: Grimmory's shim always
                    // reports downloads as available, because it has no
                    // role to withhold them with.
                    ServerKind.GRIMMORY -> R.string.server_no_download_right
                },
            ),
            tone = NoticeTone.PROBLEM,
        )
        TextButton(onClick = onRetryCapabilities, enabled = !busy) {
            Text(stringResource(R.string.server_check_again))
        }
    }

    // Only where the account may actually fetch a file, and only one
    // batch at a time: a second run started over the first would share
    // its unique work names and end up counting somebody else's books.
    if (server.canDownload) {
        ServerSection(
            title = stringResource(R.string.download_all),
        ) {
            Text(
                text = stringResource(R.string.download_all_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (batch != null) {
                BulkDownloadStatus(
                    batch = batch,
                    onCancel = onCancelDownloadAll,
                    onDismiss = onDismissBatch,
                )
            } else if (estimating) {
                Text(
                    text = stringResource(R.string.download_all_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(
                    onClick = onAskDownloadAll,
                    enabled = !busy,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.download_all))
                }
            }
        }
    }

    ServerSection(
        title = stringResource(R.string.server_section_sync),
    ) {
        Notice(
            // "Switched off" invites a reader to go and switch it on. On
            // Grimmory there is nothing to find: the shim carries no
            // reading position at all, and saying so is the whole
            // difference between a limitation and a fault.
            text = when {
                server.canSync -> stringResource(R.string.server_sync_on)
                server.kind == ServerKind.GRIMMORY ->
                    stringResource(R.string.server_sync_unsupported)

                else -> stringResource(R.string.server_sync_off)
            },
            tone = if (server.canSync) NoticeTone.GOOD else NoticeTone.NEUTRAL,
        )

        if (server.canSync) {
            DetailLine(
                text = syncStatus.describe(server.positionSyncedAt),
            )
            if (identity != null) {
                DetailLine(
                    text = stringResource(R.string.server_sync_identity, identity.login),
                )
            }
            val moved = describeMovement(syncReport)
            if (moved != null) {
                DetailLine(moved)
            }
            if (syncReport.unresolved > 0) {
                Notice(
                    text = pluralStringResource(
                        R.plurals.server_sync_unresolved,
                        syncReport.unresolved,
                        syncReport.unresolved,
                    ),
                    tone = NoticeTone.NEUTRAL,
                )
            }
            if (identity != null && identity.strandedBooks > 0) {
                Notice(
                    text = pluralStringResource(
                        R.plurals.server_sync_stranded,
                        identity.strandedBooks,
                        identity.strandedBooks,
                    ),
                    tone = NoticeTone.PROBLEM,
                )
            }
            TextButton(
                onClick = onSyncNow,
                enabled = syncStatus != PositionSyncStatus.Syncing,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.server_sync_now))
            }
        }
    }

    // Only where the account may actually send one: an option that
    // explains a thing the server will refuse is worse than no option.
    if (server.canUpload) {
        ServerSection(
            title = stringResource(R.string.upload_policy),
        ) {
            Text(
                text = stringResource(R.string.upload_policy_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UploadPolicyChoice(selected = uploadPolicy, onSelected = onSetUploadPolicy)
        }
    }

    // A token minted before the delete scope existed does not carry it,
    // and the app cannot widen one: that route authenticates with the
    // account password, which is never kept here. So say what the
    // reader has to do rather than hide an action with no explanation.
    if (server.kind == ServerKind.LISEUR_SYNC && !server.canDelete) {
        Text(
            text = stringResource(R.string.server_delete_needs_reconnect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // The Kobo token is a calibre-web notion; the others have nothing like it.
    if (server.kind == ServerKind.CALIBRE) {        AdvancedSection(server = server, onKoboToken = onKoboToken)
    }

    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.server_disconnect))
    }
}

/**
 * What the last exchange moved, in plain terms. Null when it moved
 * nothing, because "nothing changed" is the ordinary case and does not
 * deserve a line of its own.
 */
@Composable
private fun describeMovement(report: SyncReport): String? {
    if (report.at == null) return null
    val pulled = report.pulled
    val pushed = report.pushed
    return when {
        pulled > 0 && pushed > 0 -> stringResource(
            R.string.server_sync_moved_both,
            pulled,
            pushed,
        )
        pulled > 0 -> pluralStringResource(R.plurals.server_sync_pulled, pulled, pulled)
        pushed > 0 -> pluralStringResource(R.plurals.server_sync_pushed, pushed, pushed)
        else -> null
    }
}

/**
 * Kept out of the way: the token is normally picked up automatically,
 * and is only typed in by hand when that could not happen.
 */
@Composable
private fun AdvancedSection(server: RemoteServer, onKoboToken: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf(server.koboToken.orEmpty()) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.calibre_hide_advanced else R.string.calibre_show_advanced,
            ),
        )
    }
    // Expanding this on electronic paper is a full repaint of the lower
    // half of the form for every frame of the slide; it is the same form
    // either way, so it simply appears.
    val eInk = LocalEInk.current
    AnimatedVisibility(
        visible = expanded,
        enter = if (eInk) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (eInk) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.calibre_token_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.calibre_token)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onKoboToken(token) }) {
                    Text(stringResource(R.string.calibre_save_token))
                }
            }
        }
    }
}

/** Where to get a liseur-sync server, for a reader who has not got one yet. */
internal const val LISEUR_SYNC_SERVER_URL = "https://github.com/chmouel/liseur-sync"

/**
 * The liseur-sync way in: sign in and let the app mint a device token,
 * or paste a token minted on the server.
 */
@Composable
private fun LiseurSyncForm(
    state: ServerAccountUiState,
    onSignInChange: (LiseurSyncSignIn) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDeviceTokenChange: (String) -> Unit,
    secretMask: VisualTransformation,
    secretToggle: @Composable () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LiseurSyncSignIn.entries.forEachIndexed { index, way ->
            SegmentedButton(
                selected = state.liseurSyncSignIn == way,
                onClick = { onSignInChange(way) },
                enabled = !state.connecting,
                shape = SegmentedButtonDefaults.itemShape(index, LiseurSyncSignIn.entries.size),
            ) {
                Text(
                    stringResource(
                        when (way) {
                            LiseurSyncSignIn.PASSWORD -> R.string.liseur_sync_sign_in_password
                            LiseurSyncSignIn.TOKEN -> R.string.liseur_sync_sign_in_token
                        },
                    ),
                )
            }
        }
    }
    when (state.liseurSyncSignIn) {
        LiseurSyncSignIn.PASSWORD -> {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.server_username)) },
                singleLine = true,
                enabled = !state.connecting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.server_password)) },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = secretMask,
                trailingIcon = secretToggle,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.liseur_sync_password_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LiseurSyncSignIn.TOKEN -> {
            OutlinedTextField(
                value = state.deviceToken,
                onValueChange = onDeviceTokenChange,
                label = { Text(stringResource(R.string.liseur_sync_token)) },
                singleLine = true,
                enabled = !state.connecting,
                visualTransformation = secretMask,
                trailingIcon = secretToggle,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.liseur_sync_token_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The matches the server was not sure about, one question each.
 *
 * Asked here rather than interrupting the reader in the library,
 * because there is nothing urgent about it: the book still opens, the
 * position is still kept on this device, and all that waits is whether
 * it is shared with the other one.
 */
@Composable
private fun SameBookCard(
    confirmations: List<WorkConfirmation>,
    onAnswer: (String, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.sync_server_same_book_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.sync_server_same_book_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            confirmations.forEach { candidate ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(candidate.title, style = MaterialTheme.typography.bodyLarge)
                    candidate.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAnswer(candidate.bookUrl, true) }) {
                            Text(stringResource(R.string.sync_server_same_book_yes))
                        }
                        OutlinedButton(onClick = { onAnswer(candidate.bookUrl, false) }) {
                            Text(stringResource(R.string.sync_server_same_book_no))
                        }
                    }
                }
            }
        }
    }
}

/** Shared with the sync-server screen so one problem never reads two ways. */
internal enum class NoticeTone { GOOD, NEUTRAL, PROBLEM }

@Composable
internal fun Notice(text: String, tone: NoticeTone) {
    val color = when (tone) {
        NoticeTone.GOOD -> MaterialTheme.colorScheme.primary
        NoticeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.PROBLEM -> MaterialTheme.colorScheme.error
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = when (tone) {
                NoticeTone.GOOD -> Icons.Outlined.CheckCircle
                NoticeTone.NEUTRAL -> Icons.Outlined.CloudOff
                NoticeTone.PROBLEM -> Icons.Outlined.Warning
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun AccountError.messageRes(kind: ServerKind): Int = when (this) {
    AccountError.BAD_CREDENTIALS -> when (kind) {
        ServerKind.CALIBRE -> R.string.server_error_credentials
        ServerKind.KOMGA -> R.string.server_error_credentials_komga
        // Names the admin setting as well as the password: Grimmory
        // refuses a request to a switched-off Komga API with the same
        // 403 it uses for a bad one, and nothing on the wire tells them
        // apart.
        ServerKind.GRIMMORY -> R.string.server_error_credentials_grimmory
        ServerKind.LISEUR_SYNC -> R.string.server_error_credentials_liseur_sync
    }
    AccountError.WRONG_SERVER -> when (kind) {
        ServerKind.CALIBRE -> R.string.server_error_not_calibre
        ServerKind.KOMGA -> R.string.server_error_not_komga
        ServerKind.GRIMMORY -> R.string.server_error_not_grimmory
        ServerKind.LISEUR_SYNC -> R.string.server_error_not_liseur_sync
    }
    AccountError.UNREACHABLE -> R.string.server_error_unreachable
    AccountError.UNREACHABLE_TRY_HTTP -> R.string.server_error_https
    AccountError.INSECURE_TRANSPORT -> R.string.server_sync_insecure
    AccountError.INSUFFICIENT_SCOPES -> R.string.server_error_scopes_liseur_sync
    AccountError.RATE_LIMITED -> R.string.server_error_rate_limited
}

/** Plain words for how the last position sync went. */
@Composable
private fun PositionSyncStatus.describe(lastSyncedAt: Long?): String = when (this) {
    PositionSyncStatus.Syncing -> stringResource(R.string.server_sync_running)
    is PositionSyncStatus.Failed -> stringResource(reason.messageRes())
    PositionSyncStatus.Unavailable -> stringResource(R.string.server_sync_off)
    is PositionSyncStatus.Synced -> stringResource(R.string.server_sync_last, relative(at))
    PositionSyncStatus.Idle -> lastSyncedAt
        ?.let { stringResource(R.string.server_sync_last, relative(it)) }
        ?: stringResource(R.string.server_sync_never)
}


@Composable
private fun relative(at: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

private fun UploadPolicy.labelRes(): Int = when (this) {
    UploadPolicy.ASK -> R.string.upload_policy_ask
    UploadPolicy.ALWAYS -> R.string.upload_policy_always
    UploadPolicy.NEVER -> R.string.upload_policy_never
}

private fun UploadPolicy.detailRes(): Int = when (this) {
    UploadPolicy.ASK -> R.string.upload_policy_ask_detail
    UploadPolicy.ALWAYS -> R.string.upload_policy_always_detail
    UploadPolicy.NEVER -> R.string.upload_policy_never_detail
}

/**
 * The three ways a new book may reach the server, one under the other.
 *
 * A segmented row cannot hold these: three multi-word labels wrap to two
 * lines each on a phone, and the selected one loses more width still to
 * its check mark. Stacking them buys the room to say what each choice
 * does, which matters here because the difference between them is a
 * book leaving the device or not.
 */
@Composable
private fun UploadPolicyChoice(
    selected: UploadPolicy,
    onSelected: (UploadPolicy) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        UploadPolicy.entries.forEach { policy ->
            val chosen = policy == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(
                        selected = chosen,
                        role = Role.RadioButton,
                        onClick = { onSelected(policy) },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen, onClick = null)
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        text = stringResource(policy.labelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(policy.detailRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
