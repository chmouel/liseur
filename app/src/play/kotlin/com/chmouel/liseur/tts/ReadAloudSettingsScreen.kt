package com.chmouel.liseur.tts

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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.RowDivider
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.launch

/** Read aloud's row on the main settings screen: which service and voice, or that none is set up. */
@Composable
internal fun ReadAloudSettingsEntry(feature: SpeechReadAloud, onClick: () -> Unit) {
    val configured by feature.configured.collectAsState()
    val provider by feature.provider.collectAsState(initial = ReadAloudProvider.Default)
    val geminiVoice by feature.geminiVoice.collectAsState(initial = GeminiVoice.Default)
    val kokoroVoice by feature.kokoroVoice.collectAsState(initial = null)
    val voice = when (provider) {
        ReadAloudProvider.GEMINI -> geminiVoice.id
        ReadAloudProvider.KOKORO -> kokoroVoice.orEmpty()
    }
    ConnectionRow(
        icon = { Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null) },
        title = stringResource(R.string.read_aloud_settings_title),
        subtitle = if (configured) {
            stringResource(R.string.read_aloud_settings_entry_summary, stringResource(provider.label), voice)
        } else {
            stringResource(R.string.read_aloud_settings_entry_missing)
        },
        onClick = onClick,
    )
}

/**
 * Read aloud's own screen: which service makes the voice, and that
 * service's key, server and voice. Nothing here reaches the network on
 * its own; a key is first used on the first play, and a Kokoro server is
 * only asked for its voices when the reader asks to choose one.
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
                        ReadAloudProvider.KOKORO -> KokoroRows(feature)
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

/** What the voice menu knows of the Kokoro server's voices, for [url]. */
private sealed interface KokoroVoices {
    val url: String

    data class Loading(override val url: String) : KokoroVoices
    data class Loaded(override val url: String, val voices: List<String>) : KokoroVoices
    data class Failed(override val url: String, val message: Int) : KokoroVoices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KokoroRows(feature: SpeechReadAloud) {
    val storedUrl by feature.kokoroUrl.collectAsState(initial = "")
    val voice by feature.kokoroVoice.collectAsState(initial = null)
    val keyConfigured by feature.kokoroKeyConfigured.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var typed by remember(storedUrl) { mutableStateOf(storedUrl) }
    val invalid = typed.isNotBlank() && KokoroTts.baseUrl(typed) == null
    var voices by remember { mutableStateOf<KokoroVoices?>(null) }
    var voicesOpen by remember { mutableStateOf(false) }

    val loadVoices = { url: String ->
        if (KokoroTts.baseUrl(url) != null) {
            voices = KokoroVoices.Loading(url)
            scope.launch {
                val result = feature.kokoroVoices(url)
                // Only if the reader has not moved on to another server meanwhile.
                if (voices?.url != url) return@launch
                voices = result.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) {
                            KokoroVoices.Failed(url, R.string.read_aloud_settings_kokoro_voices_none)
                        } else {
                            KokoroVoices.Loaded(url, list)
                        }
                    },
                    onFailure = { KokoroVoices.Failed(url, it.voicesMessage()) },
                )
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_kokoro_url),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.read_aloud_settings_kokoro_url_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true,
            isError = invalid,
            placeholder = { Text(stringResource(R.string.read_aloud_settings_kokoro_url_hint)) },
            supportingText = if (invalid) {
                { Text(stringResource(R.string.read_aloud_settings_kokoro_url_invalid)) }
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
                        scope.launch { feature.setKokoroUrl(url) }
                        loadVoices(url)
                    }
                },
            ),
        )
    }
    RowDivider()
    KeyRow(
        title = stringResource(R.string.read_aloud_settings_kokoro_key),
        missing = stringResource(R.string.read_aloud_settings_kokoro_key_missing),
        privacy = null,
        configured = keyConfigured,
        onKey = { scope.launch { feature.setKokoroKey(it) } },
        onClear = { scope.launch { feature.clearKokoroKey() } },
    )
    RowDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_voice),
            style = MaterialTheme.typography.bodyLarge,
        )
        ExposedDropdownMenuBox(
            expanded = voicesOpen,
            onExpandedChange = { open ->
                voicesOpen = open && KokoroTts.baseUrl(storedUrl) != null
                val known = voices
                if (voicesOpen && (known == null || known.url != storedUrl || known is KokoroVoices.Failed)) {
                    loadVoices(storedUrl)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = voice ?: stringResource(R.string.read_aloud_settings_kokoro_voice_choose),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                enabled = KokoroTts.baseUrl(storedUrl) != null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voicesOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = voicesOpen, onDismissRequest = { voicesOpen = false }) {
                when (val known = voices?.takeIf { it.url == storedUrl }) {
                    is KokoroVoices.Loaded -> known.voices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = {
                                voicesOpen = false
                                scope.launch { feature.setKokoroVoice(choice) }
                            },
                        )
                    }
                    is KokoroVoices.Failed -> {
                        DropdownMenuItem(text = { Text(stringResource(known.message)) }, onClick = {}, enabled = false)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.read_aloud_settings_kokoro_voices_retry)) },
                            onClick = { loadVoices(storedUrl) },
                        )
                    }
                    else -> DropdownMenuItem(
                        text = { Text(stringResource(R.string.read_aloud_settings_kokoro_voices_loading)) },
                        onClick = {},
                        enabled = false,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.read_aloud_settings_kokoro_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun Throwable.voicesMessage(): Int = when (this) {
    is SpeechError.Network -> R.string.read_aloud_settings_kokoro_voices_unreachable
    is SpeechError.InvalidKey -> R.string.read_aloud_settings_kokoro_voices_refused
    is IllegalArgumentException -> R.string.read_aloud_settings_kokoro_url_invalid
    else -> R.string.read_aloud_settings_kokoro_voices_failed
}

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
