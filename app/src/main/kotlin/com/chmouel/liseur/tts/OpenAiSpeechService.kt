package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Job
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
    override val summary = R.string.read_aloud_provider_openai_summary
    override val icon = Icons.Outlined.Dns

    val keyConfigured: StateFlow<Boolean> = keys.configured

    override val configured: Flow<Boolean> = settings.settings.map { s ->
        OpenAiTts.baseUrl(s.speechServerUrl.orEmpty()) != null &&
            !s.speechServerModel.isNullOrBlank() && !s.speechServerVoice.isNullOrBlank()
    }.distinctUntilChanged()

    val url: Flow<String> = settings.settings.map { it.speechServerUrl.orEmpty() }.distinctUntilChanged()

    val model: Flow<String> = settings.settings.map { it.speechServerModel.orEmpty() }.distinctUntilChanged()

    val voice: Flow<String> = settings.settings.map { it.speechServerVoice.orEmpty() }.distinctUntilChanged()

    @Composable
    override fun voiceName(): String = voice.collectAsState(initial = "").value

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

    /** Saves the server's address, ending the session read from the old one. */
    suspend fun setUrl(url: String) {
        if (settings.settings.first().speechServerUrl.orEmpty().trim() != url.trim()) control.stop()
        settings.setSpeechServerUrl(url)
    }

    /**
     * Saves [model] with [voice] in one write, then reads on with both, so
     * a session never asks the new model for the old model's voice.
     */
    suspend fun setModelAndVoice(model: String, voice: String) {
        val s = settings.settings.first()
        val changed = s.speechServerModel.orEmpty() != model.trim() || s.speechServerVoice.orEmpty() != voice.trim()
        settings.setSpeechServerModelAndVoice(model, voice)
        if (changed) control.switchVoice()
    }

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

    /** The speech models the server at [url] lists, asked with the saved key. */
    suspend fun models(url: String): Result<List<SpeechModel>> =
        ask(url) { base, key -> OpenAiTts.speechModels(client.models(base, key)) }

    /** The voices [model] has on the server at [url], asked with the saved key. */
    suspend fun voices(url: String, model: String): Result<List<String>> =
        ask(url) { base, key -> client.voices(base, key, model) }.onSuccess { listedVoices = (url to model) to it }

    /**
     * The last voices a server listed, by its address and model, so the
     * player's menu need not wait on a busy server.
     */
    private var listedVoices: Pair<Pair<String, String>, List<String>>? = null

    /** What the server answered when tested: its speech models, and the voices of the [model] tested. */
    class ServerCheck(val models: List<SpeechModel>, val model: String, val voices: List<String>)

    /**
     * Asks the server at [url] for its models and voices, then has it say
     * one word with the chosen model and voice when both are set, so a
     * wrong key, model or voice shows before a book is opened.
     */
    suspend fun test(url: String): Result<ServerCheck> {
        val s = settings.settings.first()
        return ask(url) { base, key ->
            val model = s.speechServerModel.orEmpty()
            val check = ServerCheck(OpenAiTts.speechModels(client.models(base, key)), model, client.voices(base, key, model))
            val voice = s.speechServerVoice?.takeIf { it.isNotBlank() }
            if (model.isNotBlank() && voice != null) client.synthesize(base, key, TEST_WORD, voice, model)
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
        val model by model.collectAsStateWithLifecycle("")
        val stored by voice.collectAsStateWithLifecycle("")
        val chosen by chosenVoices.collectAsStateWithLifecycle(emptySet())
        val locale = LocalConfiguration.current.locales[0]
        // The server is slow to answer while it reads, so the last list it gave is kept.
        var listed by remember(url, model) { mutableStateOf(listedVoices?.takeIf { it.first == url to model }?.second) }
        LaunchedEffect(url, model) { if (listed == null) listed = voices(url, model).getOrNull() }
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

internal object VoiceChoice {
    /**
     * The voice to keep once a model's [listed] voices arrive: the saved
     * one, unless none is saved, or the reader just [changed] model or
     * server and the new one does not list it; then the list's first, the
     * model's default. A voice the list lacks is otherwise kept, as it may
     * be typed or cloned. With no list there is nothing to pick from.
     */
    fun after(saved: String, listed: List<String>?, changed: Boolean): String = when {
        listed.isNullOrEmpty() -> saved
        saved.isBlank() -> listed.first()
        changed && saved !in listed -> listed.first()
        else -> saved
    }
}

/** The key of one model's voice list on one server. */
private fun voiceKey(url: String, model: String) = "$url\n$model"

/** The latest request for each list; a reply to an older one is dropped. */
private class Requests {
    var models = 0
    var voices = 0

    /** Bumped on every model choice, so a model list asked for earlier cannot undo it. */
    var selections = 0
    var modelsJob: Job? = null
    var voicesJob: Job? = null

    /** An address the reader just saved here, so its lists replace a model or voice it lacks. */
    var typedUrl: String? = null

    /** Drops every reply still on its way, as the address or key it was asked with is gone. */
    fun invalidate() {
        models++
        voices++
        modelsJob?.cancel()
        voicesJob?.cancel()
    }
}

private fun priceText(price: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(price)

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
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var voices by remember { mutableStateOf<Listing?>(null) }
    // A model picked whose voices are still being asked for: it is saved
    // together with one of them, never alone.
    var pendingModel by remember { mutableStateOf<String?>(null) }
    // Bumped when the key is saved or removed, which the screen cannot otherwise see.
    var keyGeneration by remember { mutableIntStateOf(0) }
    val requests = remember { Requests() }
    val preview = rememberVoicePreview(feature)
    val shownModel = pendingModel ?: storedModel

    // Each list is fetched for one address (and voices for one model); a
    // reply to an older request is dropped. A fresh setup takes the first
    // model and voice, so it can read without another tap.
    lateinit var chooseModel: (String, String, Boolean) -> Unit
    val loadVoices = { url: String, model: String, commit: Boolean, changed: Boolean ->
        val key = voiceKey(url, model)
        val id = ++requests.voices
        requests.voicesJob?.cancel()
        voices = Listing.Loading(key)
        requests.voicesJob = scope.launch {
            val before = service.voice.first()
            val result = service.voices(url, model)
            val now = service.voice.first()
            // Checked after the last wait, so nothing below acts on a superseded request.
            if (requests.voices != id) return@launch
            voices = result.toListing(
                key,
                none = R.string.read_aloud_settings_server_voices_none,
                failed = R.string.read_aloud_settings_server_voices_failed,
                emptyIsOk = true,
            )
            // A voice tapped or typed meanwhile is the reader's choice.
            val voice = if (now != before) now else VoiceChoice.after(now, result.getOrNull(), changed)
            if (commit) {
                if (pendingModel != model) return@launch
                service.setModelAndVoice(model, voice)
                pendingModel = null
            } else if (voice != now) {
                service.setVoice(voice)
            }
        }
    }
    chooseModel = { url: String, model: String, changed: Boolean ->
        requests.selections++
        preview.stop()
        pendingModel = model
        loadVoices(url, model, true, changed)
    }
    val loadModels = { url: String, changed: Boolean ->
        val id = ++requests.models
        val selection = requests.selections
        requests.modelsJob?.cancel()
        models = Listing.Loading(url)
        requests.modelsJob = scope.launch {
            val result = service.models(url)
            if (requests.models != id) return@launch
            val listed = result.getOrNull()
            models = result.map { list -> list.map { it.id } }.toListing(
                url,
                none = R.string.read_aloud_settings_server_models_none,
                failed = R.string.read_aloud_settings_server_models_failed,
            )
            prices = listed.orEmpty().mapNotNull { m -> m.pricePerMillionChars?.let { m.id to it } }.toMap()
            val current = service.model.first()
            // A model picked meanwhile is the reader's, whether still waiting for its voices or saved.
            if (requests.models != id || requests.selections != selection || pendingModel != null) return@launch
            val first = listed?.firstOrNull()?.id
            when {
                first != null && (current.isBlank() || (changed && listed.none { it.id == current })) ->
                    chooseModel(url, first, changed)
                changed -> loadVoices(url, current, false, true)
            }
        }
    }
    val refresh = { url: String, changed: Boolean ->
        if (OpenAiTts.baseUrl(url) != null) {
            loadModels(url, changed)
            // After a server change the voices wait for the models, which may replace the model.
            // A picked model still waiting for its voices keeps waiting, and is saved with them.
            val pending = pendingModel
            if (pending != null) {
                loadVoices(url, pending, true, true)
            } else if (!changed) {
                loadVoices(url, storedModel, false, false)
            }
        }
    }
    LaunchedEffect(storedUrl) {
        val changed = requests.typedUrl == storedUrl
        requests.typedUrl = null
        requests.invalidate()
        pendingModel = null
        refresh(storedUrl, changed)
    }

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
                        if (url != storedUrl) {
                            requests.typedUrl = url
                            requests.invalidate()
                        }
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
            requests.invalidate()
            keyGeneration++
            scope.launch {
                service.setKey(it)
                refresh(storedUrl, false)
            }
        },
        onClear = {
            requests.invalidate()
            keyGeneration++
            scope.launch {
                service.clearKey()
                refresh(storedUrl, false)
            }
        },
    )
    RowDivider()
    ListedField(
        title = stringResource(R.string.read_aloud_settings_server_model),
        placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
        loading = stringResource(R.string.read_aloud_settings_server_models_loading),
        stored = shownModel,
        enabled = reachable,
        listing = models?.takeIf { it.url == storedUrl },
        detail = { id ->
            prices[id]?.let {
                stringResource(R.string.read_aloud_settings_server_model_price, priceText(it, LocalConfiguration.current.locales[0]))
            }
        },
        onSave = { model -> if (model != shownModel) chooseModel(storedUrl, model, true) },
        onRetry = { loadModels(storedUrl, false) },
    )
    RowDivider()
    VoicePicker(
        stored = storedVoice,
        chosen = chosenVoices,
        // Until the picked model is saved, a voice would be asked of the old one.
        enabled = reachable && pendingModel == null,
        listing = voices?.takeIf { it.url == voiceKey(storedUrl, shownModel) },
        preview = preview,
        onSave = { scope.launch { service.setVoice(it) } },
        onChoose = { scope.launch { service.setChosenVoices(it) } },
        onRetry = { loadVoices(storedUrl, shownModel, pendingModel != null, pendingModel != null) },
    )
    RowDivider()
    TestConnectionRow(
        enabled = reachable && pendingModel == null,
        url = storedUrl,
        // A test speaks for this setup only; once any part of it changes, its answer is dropped.
        setup = listOf(storedUrl, storedModel, storedVoice, keyGeneration, pendingModel),
        test = service::test,
        onLists = { url, check ->
            models = Result.success(check.models.map { it.id }).toListing(
                url,
                none = R.string.read_aloud_settings_server_models_none,
                failed = R.string.read_aloud_settings_server_models_failed,
            )
            prices = check.models.mapNotNull { m -> m.pricePerMillionChars?.let { m.id to it } }.toMap()
            voices = Result.success(check.voices).toListing(
                voiceKey(url, check.model),
                none = R.string.read_aloud_settings_server_voices_none,
                failed = R.string.read_aloud_settings_server_voices_failed,
                emptyIsOk = true,
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
    setup: Any,
    test: suspend (String) -> Result<OpenAiSpeechService.ServerCheck>,
    onLists: (String, OpenAiSpeechService.ServerCheck) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember(setup) { mutableStateOf<ConnectionTest?>(null) }
    val current by rememberUpdatedState(setup)
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
                val tested = setup
                scope.launch {
                    val result = test(url)
                    if (current != tested) return@launch
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

