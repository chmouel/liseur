package com.chmouel.liseur.tts

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

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
    val preview = rememberVoicePreview(feature)

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
                            preview.stop()
                            scope.launch { feature.setGeminiVoice(choice) }
                        },
                    )
                }
            }
        }
        // Not played on every pick, as the OpenAI-compatible voices are:
        // each sample is a request billed to the reader's key.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewError(preview, Modifier.weight(1f))
            if (preview.playing != null) {
                TextButton(onClick = preview::stop) { Text(stringResource(R.string.read_aloud_settings_voice_stop)) }
            } else {
                val sample = sampleSentence()
                TextButton(onClick = { preview.play(sample(null), voice.id) }, enabled = configured) {
                    Text(stringResource(R.string.read_aloud_settings_voice_hear))
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
    val chosenVoices by feature.openAiChosenVoices.collectAsState(initial = emptySet())
    val keyConfigured by feature.openAiKeyConfigured.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var typed by remember(storedUrl) { mutableStateOf(storedUrl) }
    val invalid = typed.isNotBlank() && OpenAiTts.baseUrl(typed) == null
    val reachable = OpenAiTts.baseUrl(storedUrl) != null
    var models by remember { mutableStateOf<Listing?>(null) }
    var voices by remember { mutableStateOf<Listing?>(null) }
    val preview = rememberVoicePreview(feature)

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
    VoicePicker(
        stored = storedVoice,
        chosen = chosenVoices,
        enabled = reachable,
        listing = voices?.takeIf { it.url == storedUrl },
        preview = preview,
        onSave = { scope.launch { feature.setOpenAiVoice(it) } },
        onChoose = { scope.launch { feature.setOpenAiChosenVoices(it) } },
        onRetry = { loadVoices(storedUrl) },
    )
    RowDivider()
    TestConnectionRow(
        enabled = reachable,
        url = storedUrl,
        test = feature::testOpenAi,
        onLists = { url, check ->
            models = Result.success(check.models).toListing(
                url,
                none = R.string.read_aloud_settings_server_models_none,
                failed = R.string.read_aloud_settings_server_models_failed,
            )
            voices = Result.success(check.voices).toListing(
                url,
                none = R.string.read_aloud_settings_server_voices_none,
                failed = R.string.read_aloud_settings_server_voices_failed,
            )
        },
    )
    Text(
        text = stringResource(R.string.read_aloud_settings_server_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
}

private sealed interface ConnectionTest {
    data object Testing : ConnectionTest
    data class Passed(val models: Int, val voices: Int) : ConnectionTest
    data class Failed(@StringRes val message: Int) : ConnectionTest
}

/**
 * Tests the saved address, key, model and voice in one go and says what
 * came back. The lists it gets replace the ones on screen.
 */
@Composable
private fun TestConnectionRow(
    enabled: Boolean,
    url: String,
    test: suspend (String) -> Result<SpeechReadAloud.ServerCheck>,
    onLists: (String, SpeechReadAloud.ServerCheck) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember(url) { mutableStateOf<ConnectionTest?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (val s = state) {
                null -> ""
                ConnectionTest.Testing -> stringResource(R.string.read_aloud_settings_test_running)
                is ConnectionTest.Passed -> stringResource(
                    R.string.read_aloud_settings_test_passed,
                    pluralStringResource(R.plurals.read_aloud_settings_test_models, s.models, s.models),
                    pluralStringResource(R.plurals.read_aloud_settings_test_voices, s.voices, s.voices),
                )
                is ConnectionTest.Failed -> stringResource(s.message)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ConnectionTest.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = enabled && state != ConnectionTest.Testing,
            onClick = {
                state = ConnectionTest.Testing
                scope.launch {
                    val result = test(url)
                    result.onSuccess { onLists(url, it) }
                    state = result.fold(
                        onSuccess = { ConnectionTest.Passed(it.models.size, it.voices.size) },
                        onFailure = { ConnectionTest.Failed(previewMessage(it)) },
                    )
                }
            },
        ) {
            Text(stringResource(R.string.read_aloud_settings_test))
        }
    }
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

/**
 * One voice sample at a time, for the settings screen: which voice is
 * being heard, and why the last one could not be. Leaving the screen
 * silences it.
 */
@Stable
private class VoicePreview(private val scope: CoroutineScope, private val feature: SpeechReadAloud) {
    /** The voice being heard. */
    var playing by mutableStateOf<String?>(null)
        private set

    @get:StringRes
    var error by mutableStateOf<Int?>(null)
        private set

    private var job: Job? = null

    fun play(sample: String, voice: String) {
        job?.cancel()
        error = null
        playing = voice
        lateinit var mine: Job
        mine = scope.launch {
            val result = feature.preview(sample, voice)
            if (job !== mine) return@launch
            playing = null
            error = result.exceptionOrNull()?.let(::previewMessage)
        }
        job = mine
    }

    fun stop() {
        job?.cancel()
        job = null
        playing = null
    }
}

@Composable
private fun rememberVoicePreview(feature: SpeechReadAloud): VoicePreview {
    val scope = rememberCoroutineScope()
    return remember(feature) { VoicePreview(scope, feature) }
}

@StringRes
private fun previewMessage(error: Throwable): Int = when (error) {
    is SpeechError.Network -> R.string.read_aloud_settings_preview_unreachable
    is SpeechError.InvalidKey -> R.string.read_aloud_settings_preview_refused
    is SpeechError.InvalidVoice -> R.string.read_aloud_settings_preview_no_voice
    is SpeechError -> R.string.read_aloud_settings_preview_service
    else -> R.string.read_aloud_settings_preview_failed
}

@Composable
private fun PreviewError(preview: VoicePreview, modifier: Modifier = Modifier) {
    val error = preview.error ?: return
    Text(
        text = stringResource(error),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

/**
 * The sample sentence in a voice's language when Liseur has it in that
 * language, so a French voice is not heard reading English; otherwise in
 * the app's.
 */
@Composable
private fun sampleSentence(): (language: String?) -> String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return { language ->
        val localized = if (language == null) {
            context
        } else {
            context.createConfigurationContext(
                Configuration(configuration).apply { setLocale(Locale.forLanguageTag(language)) },
            )
        }
        localized.getString(R.string.read_aloud_settings_voice_sample)
    }
}

/**
 * The service's voices as chips, grouped by language: tapping one picks
 * it and plays a sample, tapping it again while it speaks silences it.
 * Below them, a field for a voice the service does not list.
 */
@Composable
private fun VoicePicker(
    stored: String,
    chosen: Set<String>,
    enabled: Boolean,
    listing: Listing?,
    preview: VoicePreview,
    onSave: (String) -> Unit,
    onChoose: (Set<String>) -> Unit,
    onRetry: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val listed = (listing as? Listing.Loaded)?.items.orEmpty()
    var choosing by remember { mutableStateOf(false) }
    var typed by remember(stored, listed) { mutableStateOf(stored.takeUnless { it in listed }.orEmpty()) }
    val locale = LocalConfiguration.current.locales[0]
    val sample = sampleSentence()

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = stringResource(R.string.read_aloud_settings_voice), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(
                if (listed.isEmpty()) {
                    R.string.read_aloud_settings_voice_type_detail
                } else {
                    R.string.read_aloud_settings_voice_pick_detail
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !enabled -> Unit
            listing is Listing.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(listing.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.read_aloud_settings_server_retry)) }
            }
            listing !is Listing.Loaded -> Text(
                text = stringResource(R.string.read_aloud_settings_server_voices_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            else -> VoiceLabel.grouped(VoiceLabel.offered(listed, chosen, stored)).forEach { (language, voices) ->
                if (language != null) LanguageHeader(language, locale)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = if (language == null) Modifier.padding(top = 8.dp) else Modifier,
                ) {
                    voices.forEach { voice ->
                        val speaking = preview.playing == voice.id
                        FilterChip(
                            selected = voice.id == stored,
                            onClick = {
                                if (speaking) {
                                    preview.stop()
                                } else {
                                    onSave(voice.id)
                                    preview.play(sample(language), voice.id)
                                }
                            },
                            label = { Text(voiceChipLabel(voice)) },
                            leadingIcon = {
                                Icon(
                                    if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
        }
        if (enabled && listed.size > 1) {
            TextButton(onClick = { choosing = true }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.read_aloud_settings_voices_choose))
            }
        }
        if (choosing) {
            ChooseVoicesDialog(
                listed = listed,
                chosen = chosen,
                preview = preview,
                onDismiss = { choosing = false },
                onSave = {
                    choosing = false
                    onChoose(it)
                },
            )
        }
        PreviewError(preview, Modifier.padding(top = 4.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.read_aloud_settings_voice_other)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focus.clearFocus()
                    val voice = typed.trim()
                    if (voice.isNotEmpty()) {
                        onSave(voice)
                        preview.play(sample(VoiceLabel.of(voice).language), voice)
                    }
                },
            ),
        )
    }
}

@Composable
internal fun LanguageHeader(language: String, locale: Locale, modifier: Modifier = Modifier) {
    Text(
        text = Locale.forLanguageTag(language).getDisplayName(locale),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Which of the service's voices to offer, each with a sample to hear.
 * With nothing chosen yet every voice starts ticked; saving all of them,
 * or none, offers them all.
 */
@Composable
private fun ChooseVoicesDialog(
    listed: List<String>,
    chosen: Set<String>,
    preview: VoicePreview,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val sample = sampleSentence()
    var ticked by remember { mutableStateOf(listed.filter { it in chosen }.toSet().ifEmpty { listed.toSet() }) }
    val all = ticked.size == listed.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.read_aloud_settings_voices_choose)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.read_aloud_settings_voices_choose_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { ticked = if (all) emptySet() else listed.toSet() }) {
                    Text(
                        stringResource(
                            if (all) R.string.read_aloud_settings_voices_untick_all else R.string.read_aloud_settings_voices_tick_all,
                        ),
                    )
                }
                PreviewError(preview)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    VoiceLabel.grouped(listed).forEach { (language, voices) ->
                        if (language != null) LanguageHeader(language, locale)
                        voices.forEach { voice ->
                            val speaking = preview.playing == voice.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = voice.id in ticked,
                                        role = Role.Checkbox,
                                        onValueChange = { ticked = if (it) ticked + voice.id else ticked - voice.id },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = voice.id in ticked, onCheckedChange = null)
                                Text(
                                    text = voiceChipLabel(voice),
                                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                                )
                                IconButton(
                                    onClick = {
                                        if (speaking) preview.stop() else preview.play(sample(language), voice.id)
                                    },
                                ) {
                                    Icon(
                                        if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = if (speaking) {
                                            stringResource(R.string.read_aloud_settings_voice_stop)
                                        } else {
                                            stringResource(R.string.read_aloud_settings_voice_hear_named, voice.name)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (all) emptySet() else ticked) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** "Bella · F", or just the name when the id says nothing of a gender. */
@Composable
internal fun voiceChipLabel(voice: VoiceLabel): String = when (voice.gender) {
    null -> voice.name
    VoiceLabel.Gender.FEMALE ->
        stringResource(R.string.read_aloud_voice_with_gender, voice.name, stringResource(R.string.read_aloud_voice_female))
    VoiceLabel.Gender.MALE ->
        stringResource(R.string.read_aloud_voice_with_gender, voice.name, stringResource(R.string.read_aloud_voice_male))
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
internal fun geminiVoiceLabel(voice: GeminiVoice): String =
    stringResource(R.string.read_aloud_settings_voice_choice, voice.id, stringResource(voice.style))
