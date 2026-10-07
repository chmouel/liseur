package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.ui.settings.RowDivider
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/**
 * A speech server of the reader's choice speaking OpenAI's speech API,
 * such as a self-hosted Kokoro: an address, a model and a voice, its key
 * being optional.
 */
internal class OpenAiSpeechService(
    private val keys: ApiKeyStore,
    private val settings: AppSettingsRepository,
    private val control: SessionControl,
    private val client: OpenAiTtsClient = OpenAiTtsClient(),
) : SpeechService {
    override val id = "openai"
    override val label = R.string.read_aloud_provider_openai

    val keyConfigured: StateFlow<Boolean> = keys.configured

    override val configured: Flow<Boolean> = settings.settings.map { s ->
        OpenAiTts.baseUrl(s.speechServerUrl.orEmpty()) != null &&
            !s.speechServerModel.isNullOrBlank() && !s.speechServerVoice.isNullOrBlank()
    }.distinctUntilChanged()

    val url: Flow<String> = settings.settings.map { it.speechServerUrl.orEmpty() }.distinctUntilChanged()

    val model: Flow<String> = settings.settings.map { it.speechServerModel.orEmpty() }.distinctUntilChanged()

    val voice: Flow<String> = settings.settings.map { it.speechServerVoice.orEmpty() }.distinctUntilChanged()

    override val voiceName: Flow<String> = voice

    /** The voices the reader wants offered; empty offers all. */
    val chosenVoices: Flow<Set<String>> = settings.settings.map { it.speechServerVoices }.distinctUntilChanged()

    /** The server's host names it in a notice better than its kind does. */
    override fun noticeName(s: AppSettings): String? = OpenAiTts.baseUrl(s.speechServerUrl.orEmpty())?.host

    override suspend fun voice(s: AppSettings, voice: String?): SessionVoice? {
        val base = OpenAiTts.baseUrl(s.speechServerUrl.orEmpty()) ?: return null
        val model = s.speechServerModel?.takeIf { it.isNotBlank() } ?: return null
        val name = (voice ?: s.speechServerVoice)?.takeIf { it.isNotBlank() } ?: return null
        val key = keys.get()
        // One sentence at a time: a small self-hosted server only slows down with more.
        return SessionVoice(name, 1) { text -> client.synthesize(base, key, text, name, model) }
    }

    suspend fun setUrl(url: String) = settings.setSpeechServerUrl(url)

    suspend fun setModel(model: String) = settings.setSpeechServerModel(model)

    suspend fun setVoice(voice: String) {
        val changed = settings.settings.first().speechServerVoice != voice
        settings.setSpeechServerVoice(voice)
        if (changed) control.switchVoice()
    }

    suspend fun setChosenVoices(voices: Set<String>) = settings.setSpeechServerVoices(voices)

    /** Saves the server's [key], ending the session read with the old one. */
    suspend fun setKey(key: String) {
        control.stop()
        keys.set(key)
    }

    suspend fun clearKey() {
        control.stop()
        keys.clear()
    }

    /** The models the server at [url] lists that look like they speak, asked with the saved key. */
    suspend fun models(url: String): Result<List<String>> =
        ask(url) { base, key -> OpenAiTts.speechModels(client.models(base, key)) }

    /** The voices the server at [url] offers, asked with the saved key. */
    suspend fun voices(url: String): Result<List<String>> =
        ask(url) { base, key -> client.voices(base, key) }.onSuccess { listedVoices = url to it }

    /** The last voices a server listed, by its address, so the player's menu need not wait on a busy server. */
    private var listedVoices: Pair<String, List<String>>? = null

    /** What the server answered when tested: the speech models and the voices it lists. */
    class ServerCheck(val models: List<String>, val voices: List<String>)

    /**
     * Asks the server at [url] for its models and voices, then has it say
     * one word with the chosen model and voice when both are set, so a
     * wrong key, model or voice shows before a book is opened.
     */
    suspend fun test(url: String): Result<ServerCheck> {
        val s = settings.settings.first()
        return ask(url) { base, key ->
            val check = ServerCheck(OpenAiTts.speechModels(client.models(base, key)), client.voices(base, key))
            val model = s.speechServerModel?.takeIf { it.isNotBlank() }
            val voice = s.speechServerVoice?.takeIf { it.isNotBlank() }
            if (model != null && voice != null) client.synthesize(base, key, TEST_WORD, voice, model)
            check
        }
    }

    private suspend fun <T> ask(url: String, block: suspend (HttpUrl, String?) -> T): Result<T> {
        val base = OpenAiTts.baseUrl(url) ?: return Result.failure(IllegalArgumentException("Not a server address"))
        return try {
            Result.success(block(base, keys.get()))
        } catch (e: SpeechError) {
            Result.failure(e)
        }
    }

    @Composable
    override fun voiceStatus(): Pair<String, String>? {
        val id by voice.collectAsStateWithLifecycle("")
        val locale = LocalConfiguration.current.locales[0]
        return id.takeIf { it.isNotBlank() }?.let(VoiceLabel::of)?.let { voice ->
            val language = voice.language
            val name = language?.let { Locale.forLanguageTag(it).getDisplayName(locale) }
            val label = language?.let { VoiceLabel.languageLabel(it, locale) }
            listOfNotNull(voice.name, label).joinToString(" · ") to
                listOfNotNull(voice.name, name).joinToString(" · ")
        }
    }

    /** The voices the server lists, narrowed to the ones offered. */
    @Composable
    override fun VoiceMenuItems(onPicked: () -> Unit) {
        val scope = rememberCoroutineScope()
        val url by url.collectAsStateWithLifecycle("")
        val stored by voice.collectAsStateWithLifecycle("")
        val chosen by chosenVoices.collectAsStateWithLifecycle(emptySet())
        val locale = LocalConfiguration.current.locales[0]
        // The server is slow to answer while it reads, so the last list it gave is kept.
        var listed by remember(url) { mutableStateOf(listedVoices?.takeIf { it.first == url }?.second) }
        LaunchedEffect(url) { if (listed == null) listed = voices(url).getOrNull() }
        // Until the server answers, or if it cannot, the ones chosen are all there is to offer.
        val offered = listed?.let { VoiceLabel.offered(it, chosen, stored) }
            ?: (chosen + stored).filter { it.isNotBlank() }.sorted()
        VoiceLabel.grouped(offered).forEach { (language, voices) ->
            if (language != null) LanguageHeader(language, locale, Modifier.padding(horizontal = 12.dp))
            voices.forEach { voice ->
                MenuItem(voiceChipLabel(voice), voice.id == stored) {
                    onPicked()
                    scope.launch { setVoice(voice.id) }
                }
            }
        }
    }

    @Composable
    override fun SettingsRows(feature: SpeechReadAloud) = OpenAiRows(feature, this)
}

/** Said, not played, by a connection test: the shortest request that proves the model and voice work. */
private const val TEST_WORD = "Hello."

@Composable
private fun OpenAiRows(feature: SpeechReadAloud, service: OpenAiSpeechService) {
    val storedUrl by service.url.collectAsState(initial = "")
    val storedModel by service.model.collectAsState(initial = "")
    val storedVoice by service.voice.collectAsState(initial = "")
    val chosenVoices by service.chosenVoices.collectAsState(initial = emptySet())
    val keyConfigured by service.keyConfigured.collectAsState()
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
            val result = service.models(url)
            if (models?.url != url) return@launch
            models = result.toListing(
                url,
                none = R.string.read_aloud_settings_server_models_none,
                failed = R.string.read_aloud_settings_server_models_failed,
            )
            val first = result.getOrNull()?.firstOrNull()
            if (first != null && service.model.first().isBlank()) service.setModel(first)
        }
    }
    val loadVoices = { url: String ->
        voices = Listing.Loading(url)
        scope.launch {
            val result = service.voices(url)
            if (voices?.url != url) return@launch
            voices = result.toListing(
                url,
                none = R.string.read_aloud_settings_server_voices_none,
                failed = R.string.read_aloud_settings_server_voices_failed,
            )
            val first = result.getOrNull()?.firstOrNull()
            if (first != null && service.voice.first().isBlank()) service.setVoice(first)
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
                        scope.launch { service.setUrl(url) }
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
                service.setKey(it)
                refresh(storedUrl)
            }
        },
        onClear = {
            scope.launch {
                service.clearKey()
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
        onSave = { scope.launch { service.setModel(it) } },
        onRetry = { loadModels(storedUrl) },
    )
    RowDivider()
    VoicePicker(
        stored = storedVoice,
        chosen = chosenVoices,
        enabled = reachable,
        listing = voices?.takeIf { it.url == storedUrl },
        preview = preview,
        onSave = { scope.launch { service.setVoice(it) } },
        onChoose = { scope.launch { service.setChosenVoices(it) } },
        onRetry = { loadVoices(storedUrl) },
    )
    RowDivider()
    TestConnectionRow(
        enabled = reachable,
        url = storedUrl,
        test = service::test,
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
    test: suspend (String) -> Result<OpenAiSpeechService.ServerCheck>,
    onLists: (String, OpenAiSpeechService.ServerCheck) -> Unit,
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

