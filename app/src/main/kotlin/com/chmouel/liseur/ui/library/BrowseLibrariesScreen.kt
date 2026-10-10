package com.chmouel.liseur.ui.library

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.opds.GutenbergBrowse
import com.chmouel.liseur.data.remote.BrowseCategory
import java.util.Locale
import com.chmouel.liseur.data.remote.BrowseOwned
import com.chmouel.liseur.data.remote.BrowseOwnedState
import com.chmouel.liseur.data.remote.browseCoverUrl
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.RemoteUrl
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.coverMinSize
import com.chmouel.liseur.ui.settings.AccountError
import com.chmouel.liseur.ui.settings.LiseurSyncSignIn
import com.chmouel.liseur.ui.settings.Notice
import com.chmouel.liseur.ui.settings.NoticeTone
import com.chmouel.liseur.ui.settings.ServerKindLogo
import com.chmouel.liseur.ui.settings.ServerKindRow
import com.chmouel.liseur.ui.settings.ServerKindSheet
import com.chmouel.liseur.ui.settings.homeUrl
import com.chmouel.liseur.ui.settings.labelRes
import com.chmouel.liseur.ui.settings.linkRes
import com.chmouel.liseur.ui.settings.messageRes
import com.chmouel.liseur.ui.settings.toUiError
import com.chmouel.liseur.ui.windowWidth
import com.chmouel.liseur.ui.withoutBottom

@Composable
fun BrowseLibrariesRoute(
    onExit: () -> Unit,
    openGutenberg: Boolean = false,
    onGutenbergOpened: () -> Unit = {},
    model: BrowseLibrariesViewModel = viewModel(factory = BrowseLibrariesViewModel.Factory),
) {
    val state by model.state.collectAsStateWithLifecycle()
    // Wait for the saved list so an existing Gutenberg is reopened, not saved twice.
    LaunchedEffect(openGutenberg, state.savedLoaded) {
        if (openGutenberg && state.savedLoaded) {
            model.connectGutenberg()
            onGutenbergOpened()
        }
    }
    val back = { if (!model.back()) onExit() }
    BackHandler(onBack = back)
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val openLibrary by rememberUpdatedState(onExit)
    LaunchedEffect(model) {
        model.events.collect { event ->
            val message = when (event) {
                is BrowseEvent.Added -> resources.getQuantityString(R.plurals.browse_added_count, event.count, event.count)
                BrowseEvent.AddFailed -> resources.getString(R.string.browse_add_failed)
                BrowseEvent.RemoveFailed -> resources.getString(R.string.browse_remove_failed)
                BrowseEvent.CategoryIncomplete -> resources.getString(R.string.browse_incomplete)
                BrowseEvent.NothingToAdd -> resources.getString(R.string.browse_nothing_to_add)
                BrowseEvent.ConnectFailed -> resources.getString(R.string.browse_gutenberg_failed)
            }
            val action = if (event is BrowseEvent.Added) resources.getString(R.string.browse_open_library) else null
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = action,
                withDismissAction = action == null,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) openLibrary()
        }
    }
    when {
        state.formOpen -> BrowseConnectionScreen(state, model, snackbar)
        state.serverId == null -> SavedCatalogsScreen(state, model, snackbar, onBack = back)
        else -> CatalogScreen(state, model, snackbar, onBack = back)
    }
    state.pending?.let { pending -> ConfirmAddDialog(pending, model) }
    state.detail?.let { book -> BookDetailSheet(book, state, model) }
}

@Composable
private fun ConfirmAddDialog(pending: List<RemoteBook>, model: BrowseLibrariesViewModel) {
    val context = LocalContext.current
    val count = pending.size
    val knownSize = browseKnownSize(pending)
    AlertDialog(
        onDismissRequest = model::dismissPending,
        icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
        title = { Text(stringResource(R.string.browse_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pluralStringResource(R.plurals.browse_download_count, count, count))
                if (knownSize != null) {
                    Text(
                        stringResource(R.string.browse_download_size, Formatter.formatShortFileSize(context, knownSize)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = model::confirmAdd) { Text(stringResource(R.string.browse_add_download)) }
        },
        dismissButton = {
            TextButton(onClick = model::dismissPending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// Saved catalogs

@Composable
private fun browseConnectionName(server: RemoteServer): String =
    if (GutenbergBrowse.isRoot(server)) stringResource(R.string.browse_gutenberg_name)
    else browseCatalogName(server.baseUrl)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedCatalogsScreen(
    state: BrowseLibrariesState,
    model: BrowseLibrariesViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    var removing by remember { mutableStateOf<RemoteServer?>(null) }
    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.browse_remove_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(browseConnectionName(server), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.browse_remove_detail))
                }
            },
            confirmButton = {
                TextButton(onClick = { model.remove(server.id); removing = null }) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.browse_title)) },
                    navigationIcon = { BackButton(onBack) },
                )
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            if (state.saved.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = model::openForm,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.browse_add_connection)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            !state.savedLoaded -> Unit
            state.saved.isEmpty() -> SavedCatalogsEmpty(
                onAdd = model::openForm,
                onGutenberg = model::connectGutenberg,
                connecting = state.connecting,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding.withoutBottom())
                    .consumeWindowInsets(padding),
                // The bottom inset is the list's, so cards scroll on under
                // the navigation bar; 96dp keeps the last one clear of the FAB.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp + padding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.saved, key = { it.id }) { server ->
                    SavedCatalogCard(
                        server = server,
                        onOpen = { model.openServer(server.id) },
                        onRemove = { removing = server },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedCatalogCard(server: RemoteServer, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = browseConnectionName(server)
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { ServerKindLogo(server.kind, Modifier.size(40.dp)) },
            headlineContent = {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            },
            supportingContent = {
                val kind = stringResource(server.kind.labelRes())
                val user = server.username?.takeIf { it.isNotBlank() }
                Text(
                    if (user == null) kind else "$kind · $user",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.browse_catalog_options, name),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SavedCatalogsEmpty(
    onAdd: () -> Unit,
    onGutenberg: () -> Unit,
    connecting: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.browse_empty),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.browse_empty_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, enabled = !connecting) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.browse_add_connection))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGutenberg, enabled = !connecting) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.browse_gutenberg))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.browse_gutenberg_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// Connection form

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseConnectionScreen(
    state: BrowseLibrariesState,
    model: BrowseLibrariesViewModel,
    snackbar: SnackbarHostState,
) {
    var kind by rememberSaveable { mutableStateOf(ServerKind.CALIBRE) }
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var deviceToken by remember { mutableStateOf("") }
    var signIn by rememberSaveable { mutableStateOf(LiseurSyncSignIn.PASSWORD) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var secretShown by rememberSaveable { mutableStateOf(false) }
    var confirmingHttp by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val busy = state.connecting
    val connect = { allowHttp: Boolean ->
        val token = if (kind == ServerKind.LISEUR_SYNC && signIn == LiseurSyncSignIn.TOKEN) deviceToken else ""
        val usesPassword = !(kind == ServerKind.LISEUR_SYNC && signIn == LiseurSyncSignIn.TOKEN)
        model.connect(
            kind,
            address,
            if (usesPassword) username else "",
            if (usesPassword) password else "",
            apiKey,
            token,
            allowHttp,
        )
    }
    val ready = !busy && address.isNotBlank() && when (kind) {
        ServerKind.CALIBRE, ServerKind.BOOKORBIT -> username.isNotBlank() && password.isNotBlank()
        ServerKind.KOMGA -> apiKey.isNotBlank()
        ServerKind.LISEUR_SYNC -> when (signIn) {
            LiseurSyncSignIn.PASSWORD -> username.isNotBlank() && password.isNotBlank()
            LiseurSyncSignIn.TOKEN -> deviceToken.isNotBlank()
        }
        ServerKind.CUSTOM -> username.isBlank() == password.isBlank()
    }
    val secretMask = if (secretShown) VisualTransformation.None else PasswordVisualTransformation()
    val secretToggle: @Composable () -> Unit = {
        IconButton(onClick = { secretShown = !secretShown }) {
            Icon(
                imageVector = if (secretShown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(if (secretShown) R.string.hide_password else R.string.show_password),
            )
        }
    }
    val textField: @Composable (String, (String) -> Unit, Int, Boolean, ImeAction) -> Unit =
        { value, onChange, label, secret, ime ->
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(stringResource(label)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (secret) secretMask else VisualTransformation.None,
                trailingIcon = if (secret) secretToggle else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ime,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    if (picking) {
        ServerKindSheet(
            selected = kind,
            onPick = { kind = it; picking = false },
            onDismiss = { picking = false },
            showSync = false,
        )
    }
    if (confirmingHttp) {
        AlertDialog(
            onDismissRequest = { confirmingHttp = false },
            title = { Text(stringResource(R.string.server_http_title)) },
            text = { Text(stringResource(R.string.server_http_warning)) },
            confirmButton = {
                TextButton(onClick = { confirmingHttp = false; connect(true) }) {
                    Text(stringResource(R.string.server_http_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingHttp = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse_add_connection)) },
                navigationIcon = {
                    IconButton(onClick = model::closeForm) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.browse_form_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.gutenbergSaved) {
                GutenbergShortcut(onClick = model::connectGutenberg, enabled = !busy)
            }
            Column {
                ServerKindRow(kind = kind, enabled = !busy, onClick = { picking = true }, showSync = false)
                TextButton(
                    onClick = { runCatching { uriHandler.openUri(kind.homeUrl()) } },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(kind.linkRes()))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = {
                    Text(stringResource(if (kind == ServerKind.CUSTOM) R.string.server_url_opds else R.string.server_url))
                },
                placeholder = { Text(if (kind == ServerKind.CUSTOM) "books.example.com/opds" else "books.example.com") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            when (kind) {
                ServerKind.CALIBRE, ServerKind.BOOKORBIT -> {
                    textField(username, { username = it }, R.string.server_username, false, ImeAction.Next)
                    textField(password, { password = it }, R.string.server_password, true, ImeAction.Done)
                    if (kind == ServerKind.BOOKORBIT) FieldHelp(stringResource(R.string.server_bookorbit_password_help))
                }
                ServerKind.CUSTOM -> {
                    textField(username, { username = it }, R.string.server_username_optional, false, ImeAction.Next)
                    textField(password, { password = it }, R.string.server_password_optional, true, ImeAction.Done)
                    FieldHelp(stringResource(R.string.server_custom_credentials_help))
                }
                ServerKind.KOMGA -> {
                    textField(apiKey, { apiKey = it }, R.string.server_api_key, true, ImeAction.Done)
                    FieldHelp(stringResource(R.string.server_api_key_help))
                }
                ServerKind.LISEUR_SYNC -> {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        LiseurSyncSignIn.entries.forEachIndexed { index, way ->
                            SegmentedButton(
                                selected = signIn == way,
                                onClick = { signIn = way },
                                enabled = !busy,
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
                    when (signIn) {
                        LiseurSyncSignIn.PASSWORD -> {
                            textField(username, { username = it }, R.string.server_username, false, ImeAction.Next)
                            textField(password, { password = it }, R.string.server_password, true, ImeAction.Done)
                        }
                        LiseurSyncSignIn.TOKEN -> {
                            textField(deviceToken, { deviceToken = it }, R.string.liseur_sync_token, true, ImeAction.Done)
                            FieldHelp(stringResource(R.string.liseur_sync_token_help))
                        }
                    }
                }
            }
            state.connectionFailure?.let { failure ->
                val error = failure.toUiError()
                Notice(stringResource(error.messageRes(kind)), NoticeTone.PROBLEM)
                if (error == AccountError.UNREACHABLE_TRY_HTTP) {
                    TextButton(onClick = { confirmingHttp = true }, enabled = !busy) {
                        Text(stringResource(R.string.server_try_http))
                    }
                }
            }
            Button(
                onClick = { connect(false) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    BusyIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.browse_save_connection))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GutenbergShortcut(onClick: () -> Unit, enabled: Boolean) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.AutoStories, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.browse_gutenberg), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(stringResource(R.string.browse_gutenberg_detail)) },
        )
    }
}

@Composable
private fun FieldHelp(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// Browsing a catalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogScreen(
    state: BrowseLibrariesState,
    model: BrowseLibrariesViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val server = state.server
    val catalogName = server?.let { browseConnectionName(it) } ?: stringResource(R.string.browse_title)
    val addable = state.addable
    Scaffold(
        topBar = {
            Column {
                if (state.selecting) {
                    TopAppBar(
                        title = {
                            val count = state.selected.size
                            Text(pluralStringResource(R.plurals.browse_selected_count, count, count))
                        },
                        navigationIcon = {
                            IconButton(onClick = model::clearSelection) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.browse_clear_selection))
                            }
                        },
                        actions = {
                            if (addable.isNotEmpty()) {
                                IconButton(onClick = model::selectAll) {
                                    Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.browse_select_all))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                } else {
                    CatalogTopBar(state, model, catalogName, onBack)
                }
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selecting && state.selected.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                SelectionAddBar(state.selected.values, enabled = !state.working, onAdd = model::prepareSelected)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val hasContent = state.listing.categories.isNotEmpty() || state.listing.books.isNotEmpty()
        PullToRefreshBox(
            isRefreshing = state.loading && hasContent,
            onRefresh = model::reload,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding.withoutBottom())
                .consumeWindowInsets(padding),
        ) {
            // The bottom inset, which includes the selection bar while it
            // shows, is the grid's own so covers scroll on under it.
            CatalogGrid(state, model, catalogName, hasContent, padding.calculateBottomPadding())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogTopBar(
    state: BrowseLibrariesState,
    model: BrowseLibrariesViewModel,
    catalogName: String,
    onBack: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val categoryId = state.path.lastOrNull()?.id
    val canAddCategory = categoryId != null && state.server?.canDownload == true &&
        (state.server?.let(GutenbergBrowse::isRoot) != true ||
            !GutenbergBrowse.isVirtual(categoryId) || GutenbergBrowse.isShelf(categoryId))
    TopAppBar(
        title = {
            Column {
                Text(
                    state.path.lastOrNull()?.let { browseCategoryLabel(it) } ?: catalogName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.server?.takeIf { state.path.isEmpty() }?.let { server ->
                    Text(
                        stringResource(server.kind.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = { BackButton(onBack) },
        actions = {
            if (canAddCategory) {
                IconButton(onClick = model::prepareCategory, enabled = !state.working && !state.loading) {
                    Icon(Icons.Outlined.LibraryAdd, contentDescription = stringResource(R.string.browse_add_category))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_refresh)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        onClick = { menuOpen = false; model.reload() },
                    )
                    if (state.addable.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_select_all)) },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                            onClick = { menuOpen = false; model.selectAll() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun CatalogGrid(
    state: BrowseLibrariesState,
    model: BrowseLibrariesViewModel,
    catalogName: String,
    hasContent: Boolean,
    bottomInset: Dp,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.serverId, state.path.map(BrowseCategory::id)) {
        gridState.scrollToItem(0)
    }
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.listing.nextPage, state.moreFailed) {
        if (nearEnd && !state.moreFailed) model.loadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = coverMinSize(windowWidth())),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottomInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.path.isNotEmpty()) {
            item(key = "breadcrumbs", span = { GridItemSpan(maxLineSpan) }) {
                Breadcrumbs(catalogName, state.path, model::openPath)
            }
        }
        if (state.loadFailed) {
            item(key = "load-failed", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(bottom = 12.dp)) {
                    InlineNotice(
                        text = stringResource(R.string.browse_load_failed),
                        action = stringResource(R.string.catalog_retry),
                        onAction = model::reload,
                        problem = true,
                    )
                }
            }
        }
        if (!state.loading && !state.listing.complete && hasContent) {
            item(key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(bottom = 12.dp)) {
                    InlineNotice(text = stringResource(R.string.browse_partial), action = null, onAction = {})
                }
            }
        }
        if (state.loading && !hasContent) {
            items(SKELETON_TILES, key = { "skeleton:$it" }) { SkeletonTile(Modifier.padding(bottom = TILE_GAP)) }
            return@LazyVerticalGrid
        }
        if (!state.loadFailed && !hasContent) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.browse_section_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
        if (state.listing.categories.isNotEmpty()) {
            item(key = "collections-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.browse_section_collections), state.listing.categories.size)
            }
            val categories = state.listing.categories
            itemsIndexed(categories, key = { _, it -> "category:${it.id}" }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, category ->
                CategoryRow(
                    category = category,
                    first = index == 0,
                    last = index == categories.lastIndex,
                    modifier = Modifier.padding(bottom = if (index == categories.lastIndex) 16.dp else 2.dp),
                ) { model.openCategory(category) }
            }
        }
        if (state.listing.books.isNotEmpty()) {
            item(key = "books-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    stringResource(R.string.browse_section_books),
                    state.listing.books.size,
                    more = state.listing.nextPage != null,
                ) {
                    if (!state.selecting && state.addable.isNotEmpty()) {
                        TextButton(onClick = model::selectAll) { Text(stringResource(R.string.browse_select_all)) }
                    }
                }
            }
            items(state.listing.books, key = { "book:${it.remoteId}" }) { book ->
                RemoteBookTile(
                    book = book,
                    server = state.server,
                    owned = state.owned[book.remoteId],
                    canAdd = state.canAdd(book),
                    selecting = state.selecting,
                    selected = book.remoteId in state.selected,
                    modifier = Modifier.padding(bottom = TILE_GAP),
                    onClick = { if (state.selecting) model.toggle(book) else model.showDetail(book) },
                    onLongClick = { if (state.canAdd(book)) model.startSelection(book) else model.showDetail(book) },
                )
            }
        }
        if (hasContent && state.listing.nextPage != null) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.moreFailed -> InlineNotice(
                            text = stringResource(R.string.browse_load_failed),
                            action = stringResource(R.string.catalog_retry),
                            onAction = model::retryMore,
                            problem = true,
                        )
                        state.loadingMore -> BusyIndicator(Modifier.size(28.dp))
                        else -> TextButton(onClick = model::loadMore) { Text(stringResource(R.string.browse_load_more)) }
                    }
                }
            }
        }
    }
}

private const val LOAD_MORE_AHEAD = 6

private val TILE_GAP = 16.dp

private const val SKELETON_TILES = 9

@Composable
internal fun browseCategoryLabel(category: BrowseCategory): String {
    val language = GutenbergBrowse.languageCode(category.id)
    val shelf = GutenbergBrowse.shelfCategory(category.id)
    return when {
        category.id == GutenbergBrowse.LANGUAGES -> stringResource(R.string.browse_gutenberg_languages)
        category.id == GutenbergBrowse.CATEGORIES -> stringResource(R.string.browse_gutenberg_categories)
        language != null -> {
            val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ENGLISH
            starterLanguageLabel(language, locale)
        }
        shelf != null -> starterCategoryLabel(shelf)
        else -> category.title
    }
}

@Composable
private fun Breadcrumbs(catalogName: String, path: List<BrowseCategory>, onOpen: (Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumbs = listOf(catalogName) + path.map { browseCategoryLabel(it) }
        crumbs.forEachIndexed { depth, title ->
            val current = depth == crumbs.lastIndex
            if (depth > 0) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            TextButton(
                onClick = { onOpen(depth) },
                enabled = !current,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, more: Boolean = false, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            if (more) "$count+" else count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun CategoryRow(
    category: BrowseCategory,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val outer = 16.dp
    val inner = 4.dp
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(
            topStart = if (first) outer else inner,
            topEnd = if (first) outer else inner,
            bottomStart = if (last) outer else inner,
            bottomEnd = if (last) outer else inner,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 56.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                browseCategoryLabel(category),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun InlineNotice(text: String, action: String?, onAction: () -> Unit, problem: Boolean = false) {
    Surface(
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp).heightIn(min = 40.dp),
        ) {
            if (problem) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(end = 8.dp))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SkeletonTile(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/** A throwaway [Book] so the catalog tile draws its cover exactly the way the library does. */
private fun previewBook(book: RemoteBook, server: RemoteServer?): Book {
    val cover = book.coverHref?.let { href ->
        when {
            server == null -> null
            server.kind.linksAreAbsolute -> href
            else -> RemoteUrl.resolve(server.baseUrl, href)
        }
    }
    return Book(
        url = "browse-preview:${server?.id}:${book.remoteId}",
        title = book.title,
        author = book.author,
        coverPath = null,
        source = null,
        addedAt = 0L,
        lastOpenedAt = null,
        coverUrl = if (cover != null && server != null) browseCoverUrl(cover, server) else null,
        browseServerId = server?.id,
        seriesName = book.seriesName,
        seriesIndex = book.seriesIndex,
        sizeBytes = book.sizeBytes,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteBookTile(
    book: RemoteBook,
    server: RemoteServer?,
    owned: BrowseOwned?,
    canAdd: Boolean,
    selecting: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val preview = remember(book, server) { previewBook(book, server) }
    val description = ownedDescription(owned, canAdd)
    val selectedText = stringResource(R.string.series_assign_selected)
    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onLongClickLabel = if (canAdd) stringResource(R.string.browse_select) else null,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) "$selectedText, $description" else description
            },
    ) {
        Box {
            val shape = RoundedCornerShape(10.dp)
            BookCover(
                book = preview,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .alpha(if (!canAdd && owned == null) 0.5f else 1f)
                    .then(
                        if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape) else Modifier,
                    ),
            )
            when (owned?.state) {
                BrowseOwnedState.QUEUED, BrowseOwnedState.DOWNLOADING -> DownloadOverlay(
                    fraction = owned.fraction,
                    queued = owned.state == BrowseOwnedState.QUEUED,
                    modifier = Modifier.matchParentSize(),
                )
                BrowseOwnedState.IN_LIBRARY -> StatusBadge(
                    icon = Icons.Outlined.Check,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
                BrowseOwnedState.FAILED -> StatusBadge(
                    icon = Icons.Outlined.ErrorOutline,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
                null -> if (selecting && canAdd) {
                    SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
            }
            if (owned == null || owned.state == BrowseOwnedState.IN_LIBRARY) {
                SeriesIndexRibbon(index = book.seriesIndex, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Column(Modifier.padding(top = 6.dp).heightIn(min = 52.dp)) {
            Text(
                text = preview.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = book.author ?: stringResource(R.string.browse_unknown_author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ownedDescription(owned: BrowseOwned?, canAdd: Boolean): String = when (owned?.state) {
    BrowseOwnedState.QUEUED -> stringResource(R.string.state_download_queued)
    BrowseOwnedState.DOWNLOADING -> stringResource(R.string.state_downloading)
    BrowseOwnedState.IN_LIBRARY -> stringResource(R.string.browse_already_added)
    BrowseOwnedState.FAILED -> stringResource(R.string.browse_download_failed)
    null -> if (canAdd) stringResource(R.string.state_on_server) else stringResource(R.string.browse_not_downloadable)
}

@Composable
private fun StatusBadge(
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        tonalElevation = 2.dp,
        modifier = modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else CoverBadgeScrim,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else CoverBadgeContent,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else CoverBadgeContent),
        modifier = modifier.size(28.dp),
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SelectionAddBar(selected: Collection<RemoteBook>, enabled: Boolean, onAdd: () -> Unit) {
    val context = LocalContext.current
    val count = selected.size
    val size = browseKnownSize(selected)
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                size?.let { stringResource(R.string.browse_download_size, Formatter.formatShortFileSize(context, it)) }
                    .orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onAdd, enabled = enabled) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.browse_add_n, count, count))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailSheet(book: RemoteBook, state: BrowseLibrariesState, model: BrowseLibrariesViewModel) {
    val context = LocalContext.current
    val server = state.server
    val preview = remember(book, server) { previewBook(book, server) }
    val owned = state.owned[book.remoteId]
    val canAdd = state.canAdd(book)
    LiseurModalBottomSheet(
        onDismissRequest = model::dismissDetail,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(preview, Modifier.width(112.dp).aspectRatio(2f / 3f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(preview.displayTitle, style = MaterialTheme.typography.titleLarge)
                    Text(
                        book.author ?: stringResource(R.string.browse_unknown_author),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SeriesLine(preview)
                    book.sizeBytes?.let {
                        Text(
                            Formatter.formatShortFileSize(context, it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (canAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { model.addNow(book) },
                        enabled = !state.working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.browse_add_to_library))
                    }
                    OutlinedButton(onClick = { model.startSelection(book) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browse_select))
                    }
                }
            } else {
                Notice(
                    ownedDescription(owned, canAdd = false),
                    if (owned?.state == BrowseOwnedState.IN_LIBRARY) NoticeTone.GOOD else NoticeTone.NEUTRAL,
                )
            }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
