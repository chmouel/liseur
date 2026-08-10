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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LocalEInk
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.data.calibre.StorageUse
import com.chmouel.liseur.data.remote.SyncIdentity
import com.chmouel.liseur.data.remote.SyncReport
import com.chmouel.liseur.ui.messageRes
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.remote.ServerKind
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
    onConnect: (Boolean) -> Unit,
    onRetryCapabilities: () -> Unit,
    onKoboToken: (String) -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
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
                    )
                }
                val secretNote = when (server?.kind ?: state.kind) {
                    ServerKind.CALIBRE -> R.string.server_password_storage_note
                    ServerKind.KOMGA -> R.string.server_api_key_storage_note
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
}

@Composable
private fun ConnectForm(
    state: ServerAccountUiState,
    onKindChange: (ServerKind) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onConnect: (Boolean) -> Unit,
) {
    Text(
        stringResource(R.string.server_kind),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ServerKind.entries.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = state.kind == kind,
                onClick = { onKindChange(kind) },
                enabled = !state.connecting,
                shape = SegmentedButtonDefaults.itemShape(index, ServerKind.entries.size),
            ) {
                Text(stringResource(kind.labelRes()))
            }
        }
    }
    Text(
        stringResource(
            when (state.kind) {
                ServerKind.CALIBRE -> R.string.server_intro_calibre
                ServerKind.KOMGA -> R.string.server_intro_komga
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        ServerKind.CALIBRE -> {
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
        enabled = !state.connecting && state.url.isNotBlank() &&
            (state.kind == ServerKind.CALIBRE || state.apiKey.isNotBlank()),
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
            Text(
                server.baseUrl.substringAfter("://"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                server.username.orEmpty().ifBlank { stringResource(server.kind.labelRes()) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (!server.canDownload) {
        Notice(
            text = stringResource(
                when (server.kind) {
                    ServerKind.CALIBRE -> R.string.server_no_download_right
                    ServerKind.KOMGA -> R.string.server_no_download_right_komga
                },
            ),
            tone = NoticeTone.PROBLEM,
        )
        TextButton(onClick = onRetryCapabilities, enabled = !busy) {
            Text(stringResource(R.string.server_check_again))
        }
    }

    Notice(
        text = if (server.canSync) {
            stringResource(R.string.server_sync_on)
        } else {
            stringResource(R.string.server_sync_off)
        },
        tone = if (server.canSync) NoticeTone.GOOD else NoticeTone.NEUTRAL,
    )

    if (server.canSync) {
        Text(
            text = syncStatus.describe(server.positionSyncedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (identity != null) {
            Text(
                text = stringResource(R.string.server_sync_identity, identity.login),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val moved = describeMovement(syncReport)
        if (moved != null) {
            Text(
                text = moved,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        ) {
            Text(stringResource(R.string.server_sync_now))
        }
    }

    // The Kobo token is a calibre-web notion; Komga has nothing like it.
    if (server.kind == ServerKind.CALIBRE) {
        AdvancedSection(server = server, onKoboToken = onKoboToken)
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

private fun ServerKind.labelRes(): Int = when (this) {
    ServerKind.CALIBRE -> R.string.server_kind_calibre
    ServerKind.KOMGA -> R.string.server_kind_komga
}

private fun AccountError.messageRes(kind: ServerKind): Int = when (this) {
    AccountError.BAD_CREDENTIALS -> when (kind) {
        ServerKind.CALIBRE -> R.string.server_error_credentials
        ServerKind.KOMGA -> R.string.server_error_credentials_komga
    }
    AccountError.WRONG_SERVER -> when (kind) {
        ServerKind.CALIBRE -> R.string.server_error_not_calibre
        ServerKind.KOMGA -> R.string.server_error_not_komga
    }
    AccountError.UNREACHABLE -> R.string.server_error_unreachable
    AccountError.UNREACHABLE_TRY_HTTP -> R.string.server_error_https
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
