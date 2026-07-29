package com.chmouel.liseur.ui.settings

import androidx.compose.animation.AnimatedVisibility
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.chmouel.liseur.data.calibre.PositionSyncStatus
import com.chmouel.liseur.data.calibre.StorageUse
import com.chmouel.liseur.data.calibre.SyncIdentity
import com.chmouel.liseur.data.calibre.SyncReport
import com.chmouel.liseur.ui.messageRes
import com.chmouel.liseur.data.db.CalibreServer

/**
 * One screen for the whole calibre-web account: the user gives an
 * address and a login, and the app works out the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreAccountScreen(
    state: CalibreAccountUiState,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
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
                title = { Text(stringResource(R.string.calibre_title)) },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val server = state.server
            if (server == null) {
                if (state.lostToRestore) {
                    Notice(
                        text = stringResource(R.string.calibre_lost_to_restore),
                        tone = NoticeTone.PROBLEM,
                    )
                }
                ConnectForm(
                    state = state,
                    onUrlChange = onUrlChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
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
            Text(
                stringResource(R.string.calibre_password_storage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ConnectForm(
    state: CalibreAccountUiState,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: (Boolean) -> Unit,
) {
    Text(
        stringResource(R.string.calibre_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.calibre_url)) },
        placeholder = { Text("books.example.com") },
        singleLine = true,
        enabled = !state.connecting,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.calibre_username)) },
        singleLine = true,
        enabled = !state.connecting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    var passwordShown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.calibre_password)) },
        singleLine = true,
        enabled = !state.connecting,
        visualTransformation = if (passwordShown) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { passwordShown = !passwordShown }) {
                Icon(
                    imageVector = if (passwordShown) {
                        Icons.Outlined.VisibilityOff
                    } else {
                        Icons.Outlined.Visibility
                    },
                    contentDescription = stringResource(
                        if (passwordShown) R.string.hide_password else R.string.show_password,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    state.error?.let { error ->
        Notice(
            text = stringResource(error.messageRes()),
            tone = NoticeTone.PROBLEM,
        )
        if (error == AccountError.UNREACHABLE_TRY_HTTP) {
            var confirmingHttp by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { confirmingHttp = true }) {
                Text(stringResource(R.string.calibre_try_http))
            }
            if (confirmingHttp) {
                AlertDialog(
                    onDismissRequest = { confirmingHttp = false },
                    title = { Text(stringResource(R.string.calibre_http_title)) },
                    text = { Text(stringResource(R.string.calibre_http_warning)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmingHttp = false
                                onConnect(true)
                            },
                        ) {
                            Text(stringResource(R.string.calibre_http_confirm))
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
        enabled = !state.connecting && state.url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.connecting) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(stringResource(R.string.calibre_connect))
        }
    }
}

@Composable
private fun ConnectedCard(
    server: CalibreServer,
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
                server.username,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (storage.count == 0) {
                    stringResource(R.string.calibre_storage_empty)
                } else {
                    pluralStringResource(
                        R.plurals.calibre_storage,
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
            text = stringResource(R.string.calibre_no_download_right),
            tone = NoticeTone.PROBLEM,
        )
        TextButton(onClick = onRetryCapabilities, enabled = !busy) {
            Text(stringResource(R.string.calibre_check_again))
        }
    }

    Notice(
        text = if (server.canSync) {
            stringResource(R.string.calibre_sync_on)
        } else {
            stringResource(R.string.calibre_sync_off)
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
                text = stringResource(R.string.calibre_sync_identity, identity.login),
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
                    R.plurals.calibre_sync_unresolved,
                    syncReport.unresolved,
                    syncReport.unresolved,
                ),
                tone = NoticeTone.NEUTRAL,
            )
        }
        if (identity != null && identity.strandedBooks > 0) {
            Notice(
                text = pluralStringResource(
                    R.plurals.calibre_sync_stranded,
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
            Text(stringResource(R.string.calibre_sync_now))
        }
    }

    AdvancedSection(server = server, onKoboToken = onKoboToken)

    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.calibre_disconnect))
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
            R.string.calibre_sync_moved_both,
            pulled,
            pushed,
        )
        pulled > 0 -> pluralStringResource(R.plurals.calibre_sync_pulled, pulled, pulled)
        pushed > 0 -> pluralStringResource(R.plurals.calibre_sync_pushed, pushed, pushed)
        else -> null
    }
}

/**
 * Kept out of the way: the token is normally picked up automatically,
 * and is only typed in by hand when that could not happen.
 */
@Composable
private fun AdvancedSection(server: CalibreServer, onKoboToken: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf(server.koboToken.orEmpty()) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.calibre_hide_advanced else R.string.calibre_show_advanced,
            ),
        )
    }
    AnimatedVisibility(expanded) {
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

private enum class NoticeTone { GOOD, NEUTRAL, PROBLEM }

@Composable
private fun Notice(text: String, tone: NoticeTone) {
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

private fun AccountError.messageRes(): Int = when (this) {
    AccountError.BAD_CREDENTIALS -> R.string.calibre_error_credentials
    AccountError.NOT_CALIBRE_WEB -> R.string.calibre_error_not_calibre
    AccountError.UNREACHABLE -> R.string.calibre_error_unreachable
    AccountError.UNREACHABLE_TRY_HTTP -> R.string.calibre_error_https
}

/** Plain words for how the last position sync went. */
@Composable
private fun PositionSyncStatus.describe(lastSyncedAt: Long?): String = when (this) {
    PositionSyncStatus.Syncing -> stringResource(R.string.calibre_sync_running)
    is PositionSyncStatus.Failed -> stringResource(reason.messageRes())
    PositionSyncStatus.Unavailable -> stringResource(R.string.calibre_sync_off)
    is PositionSyncStatus.Synced -> stringResource(R.string.calibre_sync_last, relative(at))
    PositionSyncStatus.Idle -> lastSyncedAt
        ?.let { stringResource(R.string.calibre_sync_last, relative(it)) }
        ?: stringResource(R.string.calibre_sync_never)
}


@Composable
private fun relative(at: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
