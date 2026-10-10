package com.chmouel.liseur.tts

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.providers.ServiceRow
import com.chmouel.liseur.providers.ServiceSheet
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.ConnectionRow
import com.chmouel.liseur.ui.settings.NestedPage
import com.chmouel.liseur.ui.settings.PaneBackButton
import com.chmouel.liseur.ui.settings.SettingsGroup
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/** Read aloud's row on the main settings screen: which service and voice, or that none is set up. */
@Composable
internal fun ReadAloudSettingsEntry(feature: SpeechReadAloud, onClick: () -> Unit) {
    val configured by feature.configured.collectAsState()
    val service by feature.service.collectAsState(initial = feature.services.first())
    val servers by feature.servers.collectAsState(initial = ServerList.Empty)
    val selectedServer by feature.selectedServer.collectAsState(initial = null)
    val voice = service.voiceName()
    val label = servers.readable?.firstOrNull { it.id == selectedServer }?.name ?: stringResource(service.label)
    ConnectionRow(
        icon = { Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null) },
        title = stringResource(R.string.read_aloud_settings_title),
        subtitle = if (configured && voice.isBlank()) {
            label
        } else if (configured) {
            stringResource(R.string.read_aloud_settings_entry_summary, label, voice)
        } else {
            stringResource(R.string.read_aloud_settings_entry_missing)
        },
        onClick = onClick,
    )
}

/**
 * Read aloud's own screen: which service makes the voice, when the build
 * offers more than one, and that service's own rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadAloudSettingsScreen(feature: SpeechReadAloud, onBack: () -> Unit) {
    // Opened in place, so it works the same from the reader's settings.
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) {
        BackHandler { managing = false }
        NestedPage { feature.ServicesPage(onBack = { managing = false }) }
        return
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val service by feature.service.collectAsState(initial = feature.services.first())
    val servers by feature.servers.collectAsState(initial = ServerList.Empty)
    val selectedServer by feature.selectedServer.collectAsState(initial = null)
    val server = servers.readable?.firstOrNull { it.id == selectedServer }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.read_aloud_settings_title)) },
                navigationIcon = { PaneBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                var picking by remember { mutableStateOf(false) }
                ServiceRow(service, server, onClick = { picking = true })
                if (picking) {
                    ServiceSheet(
                        services = feature.services,
                        servers = servers.readable.orEmpty(),
                        selected = service,
                        selectedServer = selectedServer,
                        onPick = {
                            picking = false
                            scope.launch { feature.setService(it) }
                        },
                        onPickServer = {
                            picking = false
                            feature.selectServer(it.id)
                        },
                        onManage = {
                            picking = false
                            managing = true
                        },
                        onDismiss = { picking = false },
                    )
                }
                SettingsGroup(server?.name ?: stringResource(service.label)) {
                    service.SettingsRows(feature, onManageServices = { managing = true })
                }
                SettingsGroup(stringResource(R.string.read_aloud_settings_playback)) {
                    SentencesPerRequestRow(feature)
                }
            }
        }
    }
}

/** How many sentences go in one request, from one to five; heard from the next start. */
@Composable
private fun SentencesPerRequestRow(feature: SpeechReadAloud) {
    val count by feature.sentencesPerRequest.collectAsState(initial = 1)
    val scope = rememberCoroutineScope()
    val choices = AppSettings.SENTENCES_PER_REQUEST.toList()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = stringResource(R.string.read_aloud_settings_sentences_per_request),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.read_aloud_settings_sentences_per_request_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            choices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = count == choice,
                    onClick = { scope.launch { feature.setSentencesPerRequest(choice) } },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) {
                    Text(choice.toString())
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(service: SpeechService, server: ServerConnection?, onClick: () -> Unit) {
    val configured by service.configured.collectAsState(initial = true)
    ServiceRow(
        icon = service.icon,
        overline = stringResource(R.string.read_aloud_settings_provider),
        headline = server?.name ?: stringResource(service.label),
        supporting = when {
            !configured -> stringResource(R.string.read_aloud_settings_entry_missing)
            server != null -> server.host
            else -> stringResource(service.summary)
        },
        changeLabel = stringResource(R.string.read_aloud_settings_provider_change),
        onClick = onClick,
    )
}

/** Every service, each listed server by name in place of the server kind, then the way to the Services page. */
@Composable
private fun ServiceSheet(
    services: List<SpeechService>,
    servers: List<ServerConnection>,
    selected: SpeechService,
    selectedServer: String?,
    onPick: (SpeechService) -> Unit,
    onPickServer: (ServerConnection) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = services.flatMap { service ->
        if (service.id == ServerSettings.SERVER_PROVIDER) {
            servers.map { server ->
                ServiceOption(
                    selected = server.id == selectedServer,
                    icon = service.icon,
                    headline = server.name,
                    supporting = server.host,
                    onClick = { onPickServer(server) },
                )
            }
        } else {
            listOf(
                ServiceOption(
                    selected = service == selected,
                    icon = service.icon,
                    headline = stringResource(service.label),
                    supporting = stringResource(service.summary),
                    onClick = { onPick(service) },
                ),
            )
        }
    }
    ServiceSheet(
        title = stringResource(R.string.read_aloud_settings_provider),
        options = options,
        manageDetail = if (servers.isEmpty()) stringResource(R.string.read_aloud_provider_openai_summary) else null,
        onManage = onManage,
        onDismiss = onDismiss,
    )
}

/** What a menu knows of one of the service's lists, for [url]. */
internal sealed interface Listing {
    val url: String

    data class Loading(override val url: String) : Listing
    data class Loaded(override val url: String, val items: List<String>) : Listing
    data class Failed(override val url: String, @StringRes val message: Int) : Listing
}

/**
 * A name the service knows: picked from the list it gave, or typed when
 * the list is missing or lacks it. A typed name is saved on Done, or when
 * the field is left, the screen included; left blank, it goes back to the
 * saved one. [detail] adds a line under a listed name, such as its price.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListedField(
    title: String,
    placeholder: String,
    loading: String,
    stored: String,
    enabled: Boolean,
    listing: Listing?,
    onSave: (String) -> Unit,
    onRetry: () -> Unit,
    detail: @Composable (String) -> String? = { null },
) {
    val focus = LocalFocusManager.current
    var typed by remember { mutableStateOf(stored) }
    // The saved name the field last showed: a name chosen for the reader,
    // such as a new server's first model, does not replace one being typed.
    var synced by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) {
        if (TypedField.follows(typed, synced, stored)) {
            typed = stored
            synced = stored
        }
    }
    var open by remember { mutableStateOf(false) }
    // Picking from the menu, Done and leaving the field may all follow one another: one name is saved once.
    var saved by remember(stored) { mutableStateOf<String?>(null) }
    val commit = {
        val name = typed.trim()
        if (name.isEmpty()) {
            typed = stored
        } else if (name != stored && name != saved) {
            saved = name
            onSave(name)
        }
    }
    val latestCommit by rememberUpdatedState(commit)
    DisposableEffect(Unit) { onDispose { latestCommit() } }

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
                        commit()
                        focus.clearFocus()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled)
                    .onLeaving { commit() },
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                when (listing) {
                    is Listing.Loaded -> listing.items.forEach { choice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(choice)
                                    detail(choice)?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                open = false
                                typed = choice
                                commit()
                                focus.clearFocus()
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
internal class VoicePreview(private val scope: CoroutineScope, private val feature: SpeechReadAloud) {
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
internal fun rememberVoicePreview(feature: SpeechReadAloud): VoicePreview {
    val scope = rememberCoroutineScope()
    return remember(feature) { VoicePreview(scope, feature) }
}

@StringRes
internal fun previewMessage(error: Throwable): Int = when (error) {
    is SpeechError.LocalNetworkBlocked -> R.string.server_local_network_blocked
    is SpeechError.Network -> R.string.read_aloud_settings_preview_unreachable
    is SpeechError.InvalidKey -> R.string.read_aloud_settings_preview_refused
    is SpeechError.InvalidVoice -> R.string.read_aloud_settings_preview_no_voice
    is SpeechError.TermsRequired -> R.string.read_aloud_settings_terms_required
    is SpeechError -> R.string.read_aloud_settings_preview_service
    else -> R.string.read_aloud_settings_preview_failed
}

@Composable
internal fun PreviewError(preview: VoicePreview, modifier: Modifier = Modifier) {
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
internal fun sampleSentence(): (language: String?) -> String {
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
internal fun VoicePicker(
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
    val name = Locale.forLanguageTag(language).getDisplayName(locale)
    Text(
        text = VoiceLabel.languageLabel(language, locale),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // The flag is for the eye; a screen reader says the name once.
        modifier = modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { contentDescription = name },
    )
}

/**
 * Which of the service's voices to offer, each with a sample to hear.
 * With nothing chosen yet every voice starts ticked; saving all of them,
 * or none, offers them all.
 */
@Composable
internal fun ChooseVoicesDialog(
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

/** An empty list is [none], unless [emptyIsOk]: a model with no preset voices takes a typed one. */
internal fun Result<List<String>>.toListing(
    url: String,
    @StringRes none: Int,
    @StringRes failed: Int,
    emptyIsOk: Boolean = false,
): Listing = fold(
    onSuccess = { if (it.isEmpty() && !emptyIsOk) Listing.Failed(url, none) else Listing.Loaded(url, it) },
    onFailure = {
        Listing.Failed(
            url,
            when (it) {
                is SpeechError.LocalNetworkBlocked -> R.string.server_local_network_blocked
                is SpeechError.Network -> R.string.read_aloud_settings_server_unreachable
                is SpeechError.InvalidKey -> R.string.read_aloud_settings_server_refused
                is SpeechError.TermsRequired -> R.string.read_aloud_settings_terms_required
                is IllegalArgumentException -> R.string.read_aloud_settings_server_url_invalid
                else -> failed
            },
        )
    },
)

/**
 * A key typed once and never shown again: whether one is saved, a masked
 * field to paste a new one, and a way to remove it. A key typed is saved
 * on Done or when the field is left, the screen included, and always to
 * the [owner] shown when it was typed: one key per server. With no
 * [owner] (no server address yet) there is nowhere to save one. A
 * screen that can make the [draft] moot, as by deleting its server,
 * holds it to drop it first.
 */
@Composable
internal fun KeyRow(
    title: String,
    missing: String,
    privacy: String?,
    owner: String?,
    configured: Boolean,
    failed: Boolean,
    onKey: (owner: String, key: String, done: (Boolean) -> Unit) -> Unit,
    onClear: (owner: String) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    // Plain remember on purpose: a key half pasted must not outlive the
    // screen in saved instance state.
    draft: KeyDraft = remember { KeyDraft() },
) {
    val focus = LocalFocusManager.current
    val save by rememberUpdatedState(onKey)
    LaunchedEffect(owner) { draft.follow(owner, save) }
    DisposableEffect(Unit) { onDispose { draft.commit(save) } }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = when {
                owner == null -> stringResource(R.string.read_aloud_settings_key_needs_url)
                configured -> stringResource(R.string.read_aloud_settings_key_saved)
                else -> missing
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.text,
            onValueChange = { draft.edit(it, owner) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .focusRequester(focusRequester)
                .onLeaving { draft.commit(save) },
            enabled = owner != null,
            singleLine = true,
            isError = failed,
            placeholder = {
                Text(
                    stringResource(
                        if (configured) R.string.read_aloud_settings_key_replace else R.string.read_aloud_settings_key_hint,
                    ),
                )
            },
            supportingText = if (failed) {
                { Text(stringResource(R.string.read_aloud_settings_key_not_saved)) }
            } else {
                null
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (draft.text.isNotBlank()) {
                        draft.commit(save)
                        focus.clearFocus()
                    }
                },
            ),
        )
        if (privacy != null || (configured && owner != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = privacy.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (configured && owner != null) {
                    TextButton(
                        onClick = {
                            draft.clear()
                            onClear(owner)
                        },
                    ) {
                        Text(stringResource(R.string.read_aloud_settings_key_clear))
                    }
                }
            }
        }
    }
}

/** Runs [onLeft] when focus leaves this field, not when it was never there. */
internal object TypedField {
    /**
     * Whether a field showing [typed] takes the newly [saved] value in
     * place of [synced], the saved one it last showed: only if the reader
     * has not typed since, nor saved a [newer] one still on its way.
     */
    fun follows(typed: String, synced: String, saved: String, newer: Boolean = false): Boolean =
        typed.trim().let { it == saved || !newer && it == synced }
}

internal fun Modifier.onLeaving(onLeft: () -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged {
        if (focused && !it.isFocused) onLeft()
        focused = it.isFocused
    }
}
