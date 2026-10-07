package com.chmouel.liseur.tts

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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowBack
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.ChipRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Read aloud's row in Reading & navigation, Advanced: which service and voice, or that none is set up. */
@Composable
internal fun ReadAloudSettingsEntry(feature: SpeechReadAloud, onClick: () -> Unit) {
    val configured by feature.configured.collectAsState()
    val provider by feature.provider.collectAsState(initial = ReadAloudProvider.Default)
    val geminiVoice by feature.geminiVoice.collectAsState(initial = GeminiVoice.Default)
    val openAiVoice by feature.openAiVoice.collectAsState(initial = "")
    val voice = when (provider) {
        ReadAloudProvider.GEMINI -> geminiVoice.id
        ReadAloudProvider.OPENAI -> openAiVoice
    }
    RowDivider()
    ListItem(
        headlineContent = { Text(stringResource(R.string.read_aloud_settings_title)) },
        supportingContent = {
            Text(
                if (configured) {
                    stringResource(R.string.read_aloud_settings_entry_summary, stringResource(provider.label), voice)
                } else {
                    stringResource(R.string.read_aloud_settings_entry_missing)
                },
            )
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Read aloud's own screen: which service makes the voice, and that
 * service's key, server, model and voice. A Gemini key is first used on
 * the first play; an OpenAI-compatible service is asked for its models and
 * voices once its address is saved, and again when its key changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadAloudSettingsScreen(feature: SpeechReadAloud, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val provider by feature.provider.collectAsState(initial = ReadAloudProvider.Default)
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.read_aloud_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
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
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                SettingsGroup(stringResource(R.string.read_aloud_settings_provider)) {
                    ChipRow(
                        title = stringResource(R.string.read_aloud_settings_provider_choose),
                        subtitle = stringResource(R.string.read_aloud_settings_provider_detail),
                        options = ReadAloudProvider.entries,
                        selected = provider,
                        label = { stringResource(it.label) },
                        onSelected = { scope.launch { feature.setProvider(it) } },
                    )
                }
                SettingsGroup(stringResource(provider.label)) {
                    when (provider) {
                        ReadAloudProvider.GEMINI -> GeminiRows(feature)
                        ReadAloudProvider.OPENAI -> OpenAiRows(feature)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiRows(feature: SpeechReadAloud) {
    val configured by feature.geminiKeyConfigured.collectAsState()
    val voice by feature.geminiVoice.collectAsState(initial = GeminiVoice.Default)
    val scope = rememberCoroutineScope()
    var voicesOpen by remember { mutableStateOf(false) }

    KeyRow(
        title = stringResource(R.string.read_aloud_settings_key),
        missing = stringResource(R.string.read_aloud_settings_key_missing),
        privacy = stringResource(R.string.read_aloud_settings_privacy),
        configured = configured,
        onKey = { scope.launch { feature.setGeminiKey(it) } },
        onClear = { scope.launch { feature.clearGeminiKey() } },
    )
    RowDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_voice),
            style = MaterialTheme.typography.bodyLarge,
        )
        ExposedDropdownMenuBox(
            expanded = voicesOpen,
            onExpandedChange = { voicesOpen = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = geminiVoiceLabel(voice),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicesOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = voicesOpen, onDismissRequest = { voicesOpen = false }) {
                GeminiVoice.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(geminiVoiceLabel(choice)) },
                        onClick = {
                            voicesOpen = false
                            scope.launch { feature.setGeminiVoice(choice) }
                        },
                    )
                }
            }
        }
    }
}

/** What a menu knows of one of the service's lists, for [url]. */
private sealed interface Listing {
    val url: String

    data class Loading(override val url: String) : Listing
    data class Loaded(override val url: String, val items: List<String>) : Listing
    data class Failed(override val url: String, @StringRes val message: Int) : Listing
}

@Composable
private fun OpenAiRows(feature: SpeechReadAloud) {
    val storedUrl by feature.openAiUrl.collectAsState(initial = "")
    val storedModel by feature.openAiModel.collectAsState(initial = "")
    val storedVoice by feature.openAiVoice.collectAsState(initial = "")
    val keyConfigured by feature.openAiKeyConfigured.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var typed by remember(storedUrl) { mutableStateOf(storedUrl) }
    val invalid = typed.isNotBlank() && OpenAiTts.baseUrl(typed) == null
    val reachable = OpenAiTts.baseUrl(storedUrl) != null
    var models by remember { mutableStateOf<Listing?>(null) }
    var voices by remember { mutableStateOf<Listing?>(null) }

    // Each list is fetched for one address; a reply for an address the
    // reader has since moved on from is dropped. A fresh setup takes the
    // first entry, so it can read without another tap.
    val loadModels = { url: String ->
        models = Listing.Loading(url)
        scope.launch {
            val result = feature.openAiModels(url)
            if (models?.url != url) return@launch
            models = result.toListing(
                url,
                none = R.string.read_aloud_settings_server_models_none,
                failed = R.string.read_aloud_settings_server_models_failed,
            )
            val first = result.getOrNull()?.firstOrNull()
            if (first != null && feature.openAiModel.first().isBlank()) feature.setOpenAiModel(first)
        }
    }
    val loadVoices = { url: String ->
        voices = Listing.Loading(url)
        scope.launch {
            val result = feature.openAiVoices(url)
            if (voices?.url != url) return@launch
            voices = result.toListing(
                url,
                none = R.string.read_aloud_settings_server_voices_none,
                failed = R.string.read_aloud_settings_server_voices_failed,
            )
            val first = result.getOrNull()?.firstOrNull()
            if (first != null && feature.openAiVoice.first().isBlank()) feature.setOpenAiVoice(first)
        }
    }
    val refresh = { url: String ->
        if (OpenAiTts.baseUrl(url) != null) {
            loadModels(url)
            loadVoices(url)
        }
    }
    LaunchedEffect(storedUrl) { refresh(storedUrl) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_server_url),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.read_aloud_settings_server_url_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
            isError = invalid,
            placeholder = { Text(stringResource(R.string.read_aloud_settings_server_url_hint)) },
            supportingText = if (invalid) {
                { Text(stringResource(R.string.read_aloud_settings_server_url_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!invalid) {
                        val url = typed.trim()
                        focus.clearFocus()
                        scope.launch { feature.setOpenAiUrl(url) }
                    }
                },
            ),
        )
    }
    RowDivider()
    KeyRow(
        title = stringResource(R.string.read_aloud_settings_server_key),
        missing = stringResource(R.string.read_aloud_settings_server_key_missing),
        privacy = null,
        configured = keyConfigured,
        onKey = {
            scope.launch {
                feature.setOpenAiKey(it)
                refresh(storedUrl)
            }
        },
        onClear = {
            scope.launch {
                feature.clearOpenAiKey()
                refresh(storedUrl)
            }
        },
    )
    RowDivider()
    ListedField(
        title = stringResource(R.string.read_aloud_settings_server_model),
        placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
        loading = stringResource(R.string.read_aloud_settings_server_models_loading),
        stored = storedModel,
        enabled = reachable,
        listing = models?.takeIf { it.url == storedUrl },
        onSave = { scope.launch { feature.setOpenAiModel(it) } },
        onRetry = { loadModels(storedUrl) },
    )
    RowDivider()
    ListedField(
        title = stringResource(R.string.read_aloud_settings_voice),
        placeholder = stringResource(R.string.read_aloud_settings_server_voice_choose),
        loading = stringResource(R.string.read_aloud_settings_server_voices_loading),
        stored = storedVoice,
        enabled = reachable,
        listing = voices?.takeIf { it.url == storedUrl },
        onSave = { scope.launch { feature.setOpenAiVoice(it) } },
        onRetry = { loadVoices(storedUrl) },
    )
    Text(
        text = stringResource(R.string.read_aloud_settings_server_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
}

/**
 * A name the service knows: picked from the list it gave, or typed when
 * the list is missing or lacks it. Typing saves on Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListedField(
    title: String,
    placeholder: String,
    loading: String,
    stored: String,
    enabled: Boolean,
    listing: Listing?,
    onSave: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var typed by remember(stored) { mutableStateOf(stored) }
    var open by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.read_aloud_settings_server_pick_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { expand ->
                open = expand && enabled
                if (open && (listing == null || listing is Listing.Failed)) onRetry()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                enabled = enabled,
                placeholder = { Text(placeholder) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = open,
                        modifier = Modifier.menuAnchor(MenuAnchorType.SecondaryEditable, enabled),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        open = false
                        focus.clearFocus()
                        onSave(typed.trim())
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                when (listing) {
                    is Listing.Loaded -> listing.items.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = {
                                open = false
                                typed = choice
                                focus.clearFocus()
                                onSave(choice)
                            },
                        )
                    }
                    is Listing.Failed -> {
                        DropdownMenuItem(
                            text = { Text(stringResource(listing.message)) },
                            onClick = {},
                            enabled = false,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.read_aloud_settings_server_retry)) },
                            onClick = onRetry,
                        )
                    }
                    else -> DropdownMenuItem(text = { Text(loading) }, onClick = {}, enabled = false)
                }
            }
        }
    }
}

private fun Result<List<String>>.toListing(url: String, @StringRes none: Int, @StringRes failed: Int): Listing = fold(
    onSuccess = { if (it.isEmpty()) Listing.Failed(url, none) else Listing.Loaded(url, it) },
    onFailure = {
        Listing.Failed(
            url,
            when (it) {
                is SpeechError.Network -> R.string.read_aloud_settings_server_unreachable
                is SpeechError.InvalidKey -> R.string.read_aloud_settings_server_refused
                is IllegalArgumentException -> R.string.read_aloud_settings_server_url_invalid
                else -> failed
            },
        )
    },
)

/**
 * A key typed once and never shown again: whether one is saved, a masked
 * field to paste a new one, and a way to remove it.
 */
@Composable
private fun KeyRow(
    title: String,
    missing: String,
    privacy: String?,
    configured: Boolean,
    onKey: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focus = LocalFocusManager.current
    // Plain remember on purpose: a key half pasted must not outlive the
    // screen in saved instance state.
    var typed by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (configured) stringResource(R.string.read_aloud_settings_key_saved) else missing,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
            placeholder = {
                Text(
                    stringResource(
                        if (configured) R.string.read_aloud_settings_key_replace else R.string.read_aloud_settings_key_hint,
                    ),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    val key = typed.trim()
                    if (key.isNotEmpty()) {
                        typed = ""
                        focus.clearFocus()
                        onKey(key)
                    }
                },
            ),
        )
        if (privacy != null || configured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = privacy.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (configured) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.read_aloud_settings_key_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun geminiVoiceLabel(voice: GeminiVoice): String =
    stringResource(R.string.read_aloud_settings_voice_choice, voice.id, stringResource(voice.style))
