package com.chmouel.liseur.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.ui.settings.RowDivider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Gemini's voices on the reader's own key. */
internal class GeminiSpeechService(
    private val keys: ApiKeyStore,
    private val settings: AppSettingsRepository,
    private val control: SessionControl,
    private val client: GeminiTtsClient = GeminiTtsClient(),
) : SpeechService {
    override val id = "gemini"
    override val label = R.string.read_aloud_provider_gemini

    val keyConfigured: StateFlow<Boolean> = keys.configured

    override val configured: Flow<Boolean> = keys.configured

    val voiceChoice: Flow<GeminiVoice> =
        settings.settings.map { GeminiVoice.of(it.readAloudVoice) }.distinctUntilChanged()

    override val voiceName: Flow<String> = voiceChoice.map { it.id }

    /** The speech model in use, the default when none is saved. */
    val model: Flow<String> =
        settings.settings.map { GeminiTts.modelOf(it.readAloudModel) }.distinctUntilChanged()

    override suspend fun voice(s: AppSettings, voice: String?): SessionVoice? {
        val key = keys.get() ?: return null
        val name = GeminiVoice.of(voice ?: s.readAloudVoice).id
        val model = GeminiTts.modelOf(s.readAloudModel)
        return SessionVoice(name, SpeechCache.MAX_CONCURRENT) { text -> client.synthesize(key, text, name, model) }
    }

    /** Saves the [key], ending the session read with the old one. */
    suspend fun setKey(key: String) {
        control.stop()
        keys.set(key)
    }

    suspend fun clearKey() {
        control.stop()
        keys.clear()
    }

    suspend fun setVoice(voice: GeminiVoice) {
        val changed = GeminiVoice.of(settings.settings.first().readAloudVoice) != voice
        settings.setReadAloudVoice(voice.id)
        if (changed) control.switchVoice()
    }

    suspend fun setModel(model: String) {
        val before = GeminiTts.modelOf(settings.settings.first().readAloudModel)
        settings.setReadAloudModel(model.trim())
        if (GeminiTts.modelOf(model) != before) control.switchVoice()
    }

    /** The speech models the saved key can use. */
    suspend fun models(): Result<List<String>> {
        val key = keys.get() ?: return Result.failure(IllegalStateException("No Gemini key"))
        return try {
            Result.success(client.models(key))
        } catch (e: SpeechError) {
            Result.failure(e)
        }
    }

    @Composable
    override fun voiceStatus(): Pair<String, String>? {
        val voice by voiceChoice.collectAsStateWithLifecycle(null)
        return voice?.let { geminiVoiceLabel(it) }?.let { it to it }
    }

    @Composable
    override fun VoiceMenuItems(onPicked: () -> Unit) {
        val scope = rememberCoroutineScope()
        val current by voiceChoice.collectAsStateWithLifecycle(null)
        GeminiVoice.entries.forEach { voice ->
            MenuItem(geminiVoiceLabel(voice), voice == current) {
                onPicked()
                scope.launch { setVoice(voice) }
            }
        }
    }

    @Composable
    override fun SettingsRows(feature: SpeechReadAloud) = GeminiRows(feature, this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiRows(feature: SpeechReadAloud, service: GeminiSpeechService) {
    val configured by service.keyConfigured.collectAsState()
    val voice by service.voiceChoice.collectAsState(initial = GeminiVoice.Default)
    val model by service.model.collectAsState(initial = GeminiTts.DEFAULT_MODEL)
    val scope = rememberCoroutineScope()
    var voicesOpen by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<Listing?>(null) }
    val preview = rememberVoicePreview(feature)

    val loadModels = {
        models = Listing.Loading(GEMINI_LISTING)
        scope.launch {
            models = service.models().toListing(
                GEMINI_LISTING,
                none = R.string.read_aloud_settings_gemini_models_none,
                failed = R.string.read_aloud_settings_gemini_models_failed,
            )
        }
    }
    LaunchedEffect(configured) {
        if (configured) loadModels() else models = null
    }

    KeyRow(
        title = stringResource(R.string.read_aloud_settings_key),
        missing = stringResource(R.string.read_aloud_settings_key_missing),
        privacy = stringResource(R.string.read_aloud_settings_privacy),
        configured = configured,
        onKey = {
            scope.launch {
                service.setKey(it)
                loadModels()
            }
        },
        onClear = { scope.launch { service.clearKey() } },
    )
    RowDivider()
    ListedField(
        title = stringResource(R.string.read_aloud_settings_server_model),
        placeholder = GeminiTts.DEFAULT_MODEL,
        loading = stringResource(R.string.read_aloud_settings_gemini_models_loading),
        stored = model,
        enabled = configured,
        listing = models,
        onSave = { scope.launch { service.setModel(it) } },
        onRetry = { loadModels() },
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
                            scope.launch { service.setVoice(choice) }
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

/** Gemini's lists have one address, so they are all kept under this one. */
private const val GEMINI_LISTING = "gemini"


@Composable
internal fun geminiVoiceLabel(voice: GeminiVoice): String =
    stringResource(R.string.read_aloud_settings_voice_choice, voice.id, stringResource(voice.style))
