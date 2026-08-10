package com.chmouel.liseur.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.SyncAccount
import com.chmouel.liseur.data.remote.PositionSyncStatus
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.messageRes
import com.chmouel.liseur.ui.windowWidth

/**
 * Connecting a place to keep reading positions.
 *
 * Its own screen rather than a section of the book-server one, because
 * it answers a different question. The book server is where the books
 * came from; this is where the reader's *place* in them lives, and it
 * can be somewhere else entirely — including for books that came off an
 * SD card and have no server at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncServerScreen(
    state: SyncServerUiState,
    onSignInChange: (SyncSignIn) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onWantInsights: (Boolean) -> Unit,
    onConnect: (Boolean) -> Unit,
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
                title = { Text(stringResource(R.string.sync_server_title)) },
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
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val account = state.account
                if (account == null) {
                    ConnectSyncForm(
                        state = state,
                        onSignInChange = onSignInChange,
                        onUrlChange = onUrlChange,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                        onTokenChange = onTokenChange,
                        onWantInsights = onWantInsights,
                        onConnect = onConnect,
                    )
                } else {
                    ConnectedSyncCard(
                        account = account,
                        syncStatus = state.syncStatus,
                        onSyncNow = onSyncNow,
                        onDisconnect = onDisconnect,
                    )
                }
                Text(
                    stringResource(R.string.sync_server_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectSyncForm(
    state: SyncServerUiState,
    onSignInChange: (SyncSignIn) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onWantInsights: (Boolean) -> Unit,
    onConnect: (Boolean) -> Unit,
) {
    Text(
        stringResource(R.string.sync_server_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SyncSignIn.entries.forEachIndexed { index, way ->
            SegmentedButton(
                selected = state.signIn == way,
                onClick = { onSignInChange(way) },
                enabled = !state.connecting,
                shape = SegmentedButtonDefaults.itemShape(index, SyncSignIn.entries.size),
            ) {
                Text(
                    stringResource(
                        when (way) {
                            SyncSignIn.PASSWORD -> R.string.sync_server_sign_in_password
                            SyncSignIn.TOKEN -> R.string.sync_server_sign_in_token
                        },
                    ),
                )
            }
        }
    }
    OutlinedTextField(
        value = state.url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.server_url)) },
        placeholder = { Text("sync.example.com") },
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
        label = { Text(stringResource(R.string.server_username)) },
        singleLine = true,
        enabled = !state.connecting,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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

    when (state.signIn) {
        SyncSignIn.PASSWORD -> {
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
                stringResource(R.string.sync_server_password_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                title = stringResource(R.string.sync_server_insights),
                subtitle = stringResource(R.string.sync_server_insights_detail),
                checked = state.wantInsights,
                onCheckedChange = onWantInsights,
            )
        }

        SyncSignIn.TOKEN -> {
            OutlinedTextField(
                value = state.token,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.sync_server_token)) },
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
                stringResource(R.string.sync_server_token_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    state.error?.let { error ->
        Notice(text = stringResource(error.messageRes()), tone = NoticeTone.PROBLEM)
        if (error == SyncAccountError.UNREACHABLE_TRY_HTTP) {
            var confirmingHttp by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { confirmingHttp = true }) {
                Text(stringResource(R.string.server_try_http))
            }
            if (confirmingHttp) {
                AlertDialog(
                    onDismissRequest = { confirmingHttp = false },
                    title = { Text(stringResource(R.string.server_http_title)) },
                    text = { Text(stringResource(R.string.sync_server_http_warning)) },
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
        enabled = state.canConnect,
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
private fun ConnectedSyncCard(
    account: SyncAccount,
    syncStatus: PositionSyncStatus,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
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
                account.baseUrl.substringAfter("://"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                account.username,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.sync_server_device, account.deviceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (account.insightsTokenCipher == null) {
                Text(
                    stringResource(R.string.sync_server_no_insights),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when (syncStatus) {
        is PositionSyncStatus.Failed ->
            Notice(text = stringResource(syncStatus.reason.messageRes()), tone = NoticeTone.PROBLEM)

        is PositionSyncStatus.Synced -> Notice(
            text = stringResource(
                R.string.server_sync_last,
                DateUtils.getRelativeTimeSpanString(syncStatus.at).toString(),
            ),
            tone = NoticeTone.GOOD,
        )

        else -> Unit
    }

    Button(
        onClick = onSyncNow,
        enabled = syncStatus !is PositionSyncStatus.Syncing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.server_sync_now))
    }

    var confirmingDisconnect by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { confirmingDisconnect = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.server_disconnect))
    }
    if (confirmingDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmingDisconnect = false },
            title = { Text(stringResource(R.string.sync_server_disconnect_title)) },
            text = { Text(stringResource(R.string.sync_server_disconnect_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDisconnect = false
                        onDisconnect()
                    },
                ) {
                    Text(stringResource(R.string.server_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDisconnect = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** A switch with an explanation, matching the settings screen's rows. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun SyncAccountError.messageRes(): Int = when (this) {
    SyncAccountError.BAD_CREDENTIALS -> R.string.sync_server_error_credentials
    SyncAccountError.WRONG_SERVER -> R.string.sync_server_error_wrong_server
    SyncAccountError.UNREACHABLE -> R.string.server_error_unreachable
    SyncAccountError.UNREACHABLE_TRY_HTTP -> R.string.server_error_https
    SyncAccountError.INSECURE_TRANSPORT -> R.string.server_sync_insecure
    SyncAccountError.RATE_LIMITED -> R.string.sync_server_error_rate_limited
}
