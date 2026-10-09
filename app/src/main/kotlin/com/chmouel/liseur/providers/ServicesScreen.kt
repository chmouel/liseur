package com.chmouel.liseur.providers

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ServerChange
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.defaultServerName
import com.chmouel.liseur.tts.KeyDraft
import com.chmouel.liseur.tts.KeyRow
import com.chmouel.liseur.tts.OpenAiTts
import com.chmouel.liseur.tts.SpeechError
import com.chmouel.liseur.tts.SpeechLocalNetworkPrompt
import com.chmouel.liseur.tts.SpeechServerPreset
import com.chmouel.liseur.tts.SpeechServerPresets
import com.chmouel.liseur.tts.onLeaving
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.windowWidth
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The Services row in Settings > Reading. */
@Composable
internal fun ServicesEntry(onClick: () -> Unit) {
    ConnectionRow(
        icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
        title = stringResource(R.string.services_title),
        subtitle = stringResource(R.string.services_entry_summary),
        onClick = onClick,
    )
}

/** Marks the server being added, before it has an id. */
private const val NEW_SERVER = ""

/**
 * The one place where accounts and servers live, so no feature asks for
 * a key twice: the build's accounts, then the servers, each opening its
 * own screen to edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServicesScreen(connections: ServerConnections, accounts: ServiceAccounts, onBack: () -> Unit) {
    val servers by connections.servers.collectAsState(initial = ServerList.Empty)
    val readAloud by connections.readAloudServer.collectAsState(initial = null)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // Names this opening of the editor, so its saves find it again after a recreation.
    var draft by rememberSaveable { mutableStateOf("") }
    val drafts by connections.drafts.collectAsState()
    val edit = { id: String ->
        draft = UUID.randomUUID().toString()
        editing = id
    }
    editing?.let { opened ->
        val id = drafts[draft] ?: opened.takeIf { it != NEW_SERVER }
        BackHandler { editing = null }
        key(draft) {
            ServerScreen(
                connections = connections,
                draft = draft,
                id = id,
                server = servers.readable?.firstOrNull { it.id == id },
                usedByReadAloud = id != null && id == readAloud,
                onBack = { editing = null },
            )
        }
        return
    }
    SettingsPage(title = stringResource(R.string.services_title), onBack = onBack) {
        accounts.Rows()
        SettingsGroup(stringResource(R.string.services_servers)) {
            when (val list = servers) {
                ServerList.Unreadable -> Text(
                    text = stringResource(R.string.services_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
                is ServerList.Readable -> {
                    list.servers.forEach { server ->
                        ListItem(
                            modifier = Modifier.clickable { edit(server.id) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                            headlineContent = { Text(server.name) },
                            supportingContent = {
                                Text(
                                    if (server.id == readAloud) {
                                        stringResource(R.string.services_server_used_read_aloud, server.host)
                                    } else {
                                        server.host
                                    },
                                )
                            },
                        )
                        RowDivider()
                    }
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { edit(NEW_SERVER) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.services_add), color = MaterialTheme.colorScheme.primary)
                        },
                        supportingContent = if (list.servers.isEmpty()) {
                            { Text(stringResource(R.string.services_add_detail)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = { actions() },
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
                    .padding(bottom = 32.dp),
            ) {
                content()
            }
        }
    }
}

/** A new server's name: the hosted service it is on, or its host. */
private fun suggestedName(url: String, context: Context): String =
    SpeechServerPresets.matching(url)?.let { context.getString(it.name) } ?: defaultServerName(url)

/**
 * One server: its address, with the hosted services to pick from, its
 * name, its key and a connection test. Each field is saved when it is
 * left, so there is no Save button to forget; a new server is listed when
 * its address is first saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerScreen(
    connections: ServerConnections,
    draft: String,
    id: String?,
    server: ServerConnection?,
    usedByReadAloud: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val activity = LocalActivity.current
    // Kept across a recreation, which saves nothing, so what was typed is still there to save on leaving.
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(server?.url.orEmpty())) }
    var name by rememberSaveable { mutableStateOf(server?.name.orEmpty()) }
    var duplicate by rememberSaveable { mutableStateOf(false) }
    // The address the key belongs to: the saved one, or the one being saved.
    var committedUrl by rememberSaveable { mutableStateOf(server?.url) }
    // The address last saved, which a refused save falls back to.
    var persistedUrl by rememberSaveable { mutableStateOf(server?.url) }
    // Only the newest save's answer updates the fields; saves land in order.
    var saves by remember { mutableIntStateOf(0) }
    // Opened before the list loaded: fill the fields once it has.
    var filled by rememberSaveable { mutableStateOf(server != null || id == null) }
    if (!filled && server != null) {
        address = TextFieldValue(server.url)
        name = server.name
        committedUrl = server.url
        persistedUrl = server.url
        filled = true
    }
    var presetsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val urlFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val keyDraft = remember { KeyDraft() }
    var keyFocusWanted by remember { mutableIntStateOf(0) }
    val invalid = address.text.isNotBlank() && OpenAiTts.baseUrl(address.text) == null
    val currentId by rememberUpdatedState(id)

    val commit = {
        val url = address.text.trim()
        val typedName = name.trim()
        // Always sent, even when it looks unchanged: an earlier save still being written may change it.
        if (OpenAiTts.baseUrl(url) != null) {
            committedUrl = url
            val save = ++saves
            connections.save(draft, currentId, typedName, url, newName = typedName.ifEmpty { suggestedName(url, context) }) { change ->
                if (change is ServerChange.Saved) persistedUrl = url
                if (save == saves) {
                    duplicate = change == ServerChange.Duplicate
                    committedUrl = if (change is ServerChange.Saved) url else persistedUrl
                }
            }
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) latestCommit() }
    }
    LaunchedEffect(keyFocusWanted) {
        if (keyFocusWanted > 0) runCatching { keyFocus.requestFocus() }
    }
    val pick = { preset: SpeechServerPreset ->
        presetsOpen = false
        if (preset.example) {
            // Only an example: its host is selected for the reader's own, saved like a typed address.
            address = TextFieldValue(preset.url, TextRange(preset.host.first, preset.host.last + 1))
            runCatching { urlFocus.requestFocus() }
        } else {
            focus.clearFocus()
            address = TextFieldValue(preset.url)
            commit()
            if (!connections.keyConfigured(preset.origin).value) keyFocusWanted++
        }
    }
    val inUse = SpeechServerPresets.matching(address.text)
    val presetsLabel = stringResource(R.string.read_aloud_settings_server_presets)
    val title = server?.name ?: stringResource(R.string.services_add)

    SettingsPage(
        title = title,
        onBack = onBack,
        actions = {
            if (id != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.services_delete)) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        },
    ) {
        SettingsGroup(stringResource(R.string.services_server)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.services_server_address),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.read_aloud_settings_server_url_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = presetsOpen,
                    onExpandedChange = { presetsOpen = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            duplicate = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Typing in the field leaves the list closed: only its arrow opens it.
                            .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = false)
                            .focusRequester(urlFocus)
                            .onLeaving { commit() },
                        singleLine = true,
                        isError = invalid || duplicate,
                        placeholder = { Text(stringResource(R.string.read_aloud_settings_server_url_hint)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = presetsOpen,
                                // Before the anchor, whose own generic label would win.
                                modifier = Modifier
                                    .semantics { contentDescription = presetsLabel }
                                    .menuAnchor(MenuAnchorType.SecondaryEditable),
                            )
                        },
                        supportingText = when {
                            invalid -> {
                                { Text(stringResource(R.string.read_aloud_settings_server_url_invalid)) }
                            }
                            duplicate -> {
                                { Text(stringResource(R.string.services_server_duplicate)) }
                            }
                            else -> null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { if (!invalid) commit() }),
                    )
                    ExposedDropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                        SpeechServerPresets.all.forEach { preset ->
                            val selected = preset === inUse
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(preset.name))
                                        Text(
                                            text = if (preset.example) {
                                                stringResource(R.string.read_aloud_settings_server_preset_own)
                                            } else {
                                                preset.url
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailingIcon = if (selected) {
                                    {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = { pick(preset) },
                                modifier = Modifier.semantics { this.selected = selected },
                            )
                        }
                    }
                }
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.services_server_name),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .onLeaving { commit() },
                    singleLine = true,
                    placeholder = {
                        val url = address.text.trim()
                        if (OpenAiTts.baseUrl(url) != null) Text(suggestedName(url, context))
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            commit()
                            focus.clearFocus()
                        },
                    ),
                )
            }
            RowDivider()
            val owner = committedUrl?.let(ServerConnections::originOf)
            val keyConfigured by remember(owner) {
                owner?.let(connections::keyConfigured) ?: MutableStateFlow(false)
            }.collectAsState()
            val keyFailure by connections.keyFailure.collectAsState()
            KeyRow(
                title = stringResource(R.string.read_aloud_settings_server_key),
                missing = stringResource(R.string.read_aloud_settings_server_key_missing),
                privacy = null,
                owner = owner,
                configured = keyConfigured,
                failed = owner != null && keyFailure == owner,
                onKey = { origin, key, done -> connections.commitKey(origin, key, done) },
                onClear = { origin -> connections.commitKeyRemoval(origin) },
                focusRequester = keyFocus,
                draft = keyDraft,
            )
            RowDivider()
            ServerTestRow(connections, server?.url, owner)
        }
    }

    if (confirmDelete && server != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.services_delete_title, server.name)) },
            text = {
                Text(
                    if (usedByReadAloud) {
                        stringResource(R.string.services_delete_read_aloud, server.name)
                    } else {
                        stringResource(R.string.services_delete_unused)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        // A key still being typed would otherwise be saved as the editor closes, after its server is gone.
                        keyDraft.clear()
                        connections.delete(server.id, draft) { deleted -> if (deleted) onBack() }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private sealed interface ServerTest {
    data object Testing : ServerTest
    data class Passed(val models: Int) : ServerTest
    data class Failed(@StringRes val message: Int) : ServerTest
}

/** Whether the saved server answers with its key; what each feature does with it is checked on its own page. */
@Composable
private fun ServerTestRow(connections: ServerConnections, url: String?, owner: String?) {
    val accessAllowed = SpeechLocalNetworkPrompt(connections.localNetwork, url.orEmpty())
    val generation by remember(owner) { connections.generation(owner.orEmpty()) }.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember(url, generation) { mutableStateOf<ServerTest?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (val s = state) {
                null -> ""
                ServerTest.Testing -> stringResource(R.string.read_aloud_settings_test_running)
                is ServerTest.Passed -> pluralStringResource(R.plurals.services_test_passed, s.models, s.models)
                is ServerTest.Failed -> stringResource(s.message)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ServerTest.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = url != null && accessAllowed && state != ServerTest.Testing,
            onClick = {
                val tested = url ?: return@TextButton
                state = ServerTest.Testing
                scope.launch {
                    val result = connections.test(tested)
                    state = result.fold(
                        onSuccess = { ServerTest.Passed(it) },
                        onFailure = { ServerTest.Failed(testMessage(it)) },
                    )
                }
            },
        ) {
            Text(stringResource(R.string.read_aloud_settings_test))
        }
    }
}

@StringRes
private fun testMessage(error: Throwable): Int = when (error) {
    is SpeechError.LocalNetworkBlocked -> R.string.server_local_network_blocked
    is SpeechError.Network -> R.string.read_aloud_settings_server_unreachable
    is SpeechError.InvalidKey -> R.string.read_aloud_settings_server_refused
    else -> R.string.read_aloud_settings_server_models_failed
}
