package com.chmouel.liseur.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.HardcoverAccount
import com.chmouel.liseur.data.hardcover.HardcoverPushOutcome
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth

/** What to say when Hardcover could not be talked to. */
@StringRes
fun SyncFailure.hardcoverMessageRes(): Int = when (this) {
    SyncFailure.Unauthorised, SyncFailure.Forbidden -> R.string.hardcover_error_token
    SyncFailure.Offline, SyncFailure.Timeout -> R.string.hardcover_error_offline
    else -> R.string.hardcover_error_other
}

/** What to tell the reader came of a push they asked for. */
@StringRes
fun HardcoverPushOutcome.hardcoverMessageRes(): Int = when (this) {
    HardcoverPushOutcome.Pushed -> R.string.hardcover_pushed
    HardcoverPushOutcome.UpToDate -> R.string.hardcover_up_to_date
    HardcoverPushOutcome.NeedsMatch -> R.string.hardcover_needs_match
    HardcoverPushOutcome.SwitchedOff -> R.string.hardcover_push_off
    HardcoverPushOutcome.NotConnected -> R.string.hardcover_error_token
    is HardcoverPushOutcome.Failed -> reason.hardcoverMessageRes()
}

/**
 * The Hardcover account: a token to paste, and how reading is reported.
 *
 * Its own page rather than a section of the server screen, because it is
 * not a server the library comes from: a reader with nothing but local
 * files has as much use for it as one with a catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardcoverScreen(
    account: HardcoverAccount?,
    connecting: Boolean,
    error: SyncFailure?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Not saved across process death on purpose: a half-typed credential
    // has no business in a saved-state bundle.
    var token by remember(account?.userId) { mutableStateOf("") }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.hardcover_title)) },
                navigationIcon = { PaneBackButton(onBack) },
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
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.hardcover_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (account == null) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.hardcover_token_label)) },
                        supportingText = { Text(stringResource(R.string.hardcover_token_help)) },
                        singleLine = true,
                        enabled = !connecting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            text = stringResource(it.hardcoverMessageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onConnect(token) },
                        enabled = !connecting && token.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hardcover_connect))
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        R.string.hardcover_connected_as,
                                        account.username,
                                    ),
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = onDisconnect) {
                                    Text(stringResource(R.string.hardcover_disconnect))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        RowDivider()
                        SwitchRow(
                            title = stringResource(R.string.hardcover_auto_sync),
                            subtitle = stringResource(R.string.hardcover_auto_sync_detail),
                            checked = account.autoSync,
                            onCheckedChange = onAutoSync,
                        )
                    }
                }
            }
        }
    }
}
