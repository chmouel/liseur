package com.chmouel.liseur.tts

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
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
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.ui.settings.RowDivider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where the reader's Gemini key is kept. */
internal fun ApiKeyStore.Companion.gemini(context: Context) = ApiKeyStore(context, "gemini-key", "liseur.gemini.key")

/** Gemini's voices on the reader's own key. */
internal class GeminiSpeechService(
    private val account: GeminiAccount,
    private val settings: AppSettingsRepository,
    private val control: SessionControl,
    private val client: GeminiTtsClient = GeminiTtsClient(),
    // Saves outlive the settings screen: one made as it closes still completes.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : SpeechService {
    override val id = "gemini"
    override val label = R.string.read_aloud_provider_gemini
    override val summary = R.string.read_aloud_provider_gemini_summary
    override val icon = Icons.Outlined.Cloud

    val keyConfigured: StateFlow<Boolean> = account.configured

    /** Moves on with every key change on the Services page. */
    val keyGeneration: StateFlow<Int> = account.generation

    override val configured: Flow<Boolean> = account.configured

    override fun owner(s: AppSettings): String = GeminiAccount.OWNER

    val voiceChoice: Flow<GeminiVoice> =
        settings.settings.map { GeminiVoice.of(it.readAloudVoice) }.distinctUntilChanged()

    @Composable
    override fun voiceName(): String = voiceChoice.collectAsState(initial = GeminiVoice.Default).value.id

    /** The speech model in use, the default when none is saved. */
    val model: Flow<String> =
        settings.settings.map { GeminiTts.modelOf(it.readAloudModel) }.distinctUntilChanged()

    override suspend fun voice(s: AppSettings, voice: String?): SessionVoice? {
        val key = account.key() ?: return null
        val name = GeminiVoice.of(voice ?: s.readAloudVoice).id
        val model = GeminiTts.modelOf(s.readAloudModel)
        return SessionVoice(name, SpeechCache.MAX_CONCURRENT) { text -> client.synthesize(key, text, name, model) }
    }

    /** Saves [voice], also as the one for the language being read when there is a session. */
    suspend fun setVoice(voice: GeminiVoice) {
        val s = settings.settings.first()
        val model = GeminiTts.modelOf(s.readAloudModel)
        val language = VoiceResolver.settingsLanguage(GeminiLanguages.of(model), control.sessionLanguage(this))
        if (!remember(scopeOf(model), language, voice.id)) settings.setReadAloudVoice(voice.id)
        if (settings.settings.first() != s) control.switchVoice()
    }

    private fun scopeOf(model: String) = VoiceScope(id, "", model)

    override suspend fun catalogue(s: AppSettings): VoiceCatalogue? {
        if (account.key() == null) return null
        val model = GeminiTts.modelOf(s.readAloudModel)
        val languages = GeminiLanguages.of(model)
        return VoiceCatalogue(
            scope = scopeOf(model),
            voices = GeminiVoice.entries.map { CatalogueVoice(it.id, languages) },
            default = GeminiVoice.Default.id,
            global = GeminiVoice.of(s.readAloudVoice).id,
        )
    }

    override suspend fun remember(scope: VoiceScope, language: String?, voice: String): Boolean =
        settings.editReadAloudVoice {
            if (scope != scopeOf(GeminiTts.modelOf(geminiModel))) return@editReadAloudVoice false
            setGeminiVoice(voice)
            language?.let { remember(scope.preference(it, voice)) }
            true
        }

    @Composable
    override fun voiceLabel(voice: String): String = geminiVoiceLabel(GeminiVoice.of(voice))

    /** Saves [model] in the service's scope, so one typed as the screen closes is still saved. */
    fun commitModel(model: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { setModel(model) }
    }

    suspend fun setModel(model: String) {
        val before = GeminiTts.modelOf(settings.settings.first().readAloudModel)
        settings.setReadAloudModel(model.trim())
        if (GeminiTts.modelOf(model) != before) control.switchVoice()
    }

    /** The speech models the saved key can use. */
    suspend fun models(): Result<List<String>> {
        val key = account.key() ?: return Result.failure(IllegalStateException("No Gemini key"))
        return try {
            Result.success(client.models(key))
        } catch (e: SpeechError) {
            Result.failure(e)
        }
    }

    @Composable
    override fun SettingsRows(feature: SpeechReadAloud, onManageServices: () -> Unit) =
        GeminiRows(feature, this, onManageServices)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiRows(feature: SpeechReadAloud, service: GeminiSpeechService, onManageServices: () -> Unit) {
    val configured by service.keyConfigured.collectAsState()
    val keyGeneration by service.keyGeneration.collectAsState()
    val voice by service.voiceChoice.collectAsState(initial = GeminiVoice.Default)
    val model by service.model.collectAsState(initial = GeminiTts.DEFAULT_MODEL)
    val scope = rememberCoroutineScope()
    var voicesOpen by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<Listing?>(null) }
    var listing by remember { mutableStateOf<Job?>(null) }
    val preview = rememberVoicePreview(feature)

    // A listing asked with a key since replaced or removed is cancelled, and dropped if it answers anyway.
    val loadModels = {
        listing?.cancel()
        val asked = service.keyGeneration.value
        models = Listing.Loading(GEMINI_LISTING)
        listing = scope.launch {
            val listed = service.models().toListing(
                GEMINI_LISTING,
                none = R.string.read_aloud_settings_gemini_models_none,
                failed = R.string.read_aloud_settings_gemini_models_failed,
            )
            if (service.keyGeneration.value == asked) models = listed
        }
    }
    LaunchedEffect(configured, keyGeneration) {
        if (configured) {
            loadModels()
        } else {
            listing?.cancel()
            models = null
        }
    }

    // The key itself is set on the Services page, shared with every feature using Gemini.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.read_aloud_settings_key),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (configured) {
                    stringResource(R.string.read_aloud_settings_key_saved)
                } else {
                    stringResource(R.string.read_aloud_settings_key_missing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onManageServices) { Text(stringResource(R.string.services_manage)) }
    }
    Text(
        text = stringResource(R.string.read_aloud_settings_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
    RowDivider()
    ListedField(
        title = stringResource(R.string.read_aloud_settings_server_model),
        placeholder = GeminiTts.DEFAULT_MODEL,
        loading = stringResource(R.string.read_aloud_settings_gemini_models_loading),
        stored = model,
        enabled = configured,
        listing = models,
        onSave = { service.commitModel(it) },
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
