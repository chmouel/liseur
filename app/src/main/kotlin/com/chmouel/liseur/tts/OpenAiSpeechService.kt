package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.LocalNetworkAccess
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.ui.settings.RowDivider
import okhttp3.HttpUrl
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A speech server of the reader's choice speaking OpenAI's speech API,
 * such as a self-hosted Kokoro: an address, a model and a voice, its key
 * being optional.
 */
internal class OpenAiSpeechService(
    private val keys: ServerKeys,
    private val settings: AppSettingsRepository,
    private val control: SessionControl,
    private val client: OpenAiTtsClient = OpenAiTtsClient(),
    // Saves outlive the settings screen: one made as it closes still completes.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val localNetwork: LocalNetworkAccess = LocalNetworkAccess.Unrestricted,
) : SpeechService {
    override val id = "openai"
    override val label = R.string.read_aloud_provider_openai
    override val summary = R.string.read_aloud_provider_openai_summary
    override val icon = Icons.Outlined.Dns

    /** Whether a key is saved for the server at [origin]. */
    fun keyConfigured(origin: String): StateFlow<Boolean> = keys.configured(origin)

    private val keyCommits = KeyCommits(scope)

    /** The server whose key could not be saved or removed, if any. */
    val keyFailure: StateFlow<String?> = keyCommits.failure

    // Address saves and model choices take turns with it, so a slow choice
    // never writes over a newer one or onto another server.
    private val settingsLock = Mutex()

    /** Bumped by every address save and model choice; a model choice is saved only if none came after it. */
    private val generation = AtomicInteger()

    /** The model choice still asking for its voices, if any; a reopened screen shows it and leaves it be. */
    class PendingModel(val url: String, val model: String, val settle: Boolean)

    private val pending = MutableStateFlow<PendingModel?>(null)
    val pendingModel: StateFlow<PendingModel?> = pending.asStateFlow()

    private val urlWrites = MutableStateFlow(0)

    /** How many address saves have not landed yet. */
    val savingUrls: StateFlow<Int> = urlWrites.asStateFlow()

    /** The current choice; an automatic voice change taken at an older one is dropped. */
    fun choice(): Int = generation.get()

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
        val key = keys.get(ServerKeys.origin(base))
        // One sentence at a time: a small self-hosted server only slows down with more.
        return SessionVoice(name, 1) { text ->
            requireAccess(base.toString())
            client.synthesize(base, key, text, name, model)
        }
    }

    /**
     * Saves the server's address, ending the session read from the old
     * one. A new address is marked unsettled until its own lists have
     * chosen its model and voice, see [settled].
     */
    fun commitUrl(url: String) {
        blockedUrl.value = null
        generation.incrementAndGet()
        urlWrites.update { it + 1 }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                settingsLock.withLock {
                    if (settings.settings.first().speechServerUrl.orEmpty() != url.trim()) {
                        control.stop()
                        settings.setSpeechServerUrlUnsettled(url)
                    }
                }
            } finally {
                urlWrites.update { it - 1 }
            }
        }
    }

    /**
     * Whether [url] was saved as a new server whose model and voice are
     * not yet chosen from its lists: its lists then replace a model or
     * voice it lacks, even after the screen or the app was closed.
     */
    suspend fun unsettled(url: String): Boolean = settings.settings.first().speechServerUnsettledUrl == url

    /**
     * Picks [model] on the server at [url]: asks for its voices, then saves
     * the model with one of them, in the service's scope. Saved only if the
     * address and the model choice are still the ones made here, and then
     * a new server counts as settled when [settle] (its model list loaded).
     */
    fun commitModel(
        url: String,
        model: String,
        changed: Boolean,
        settle: Boolean,
        done: (Result<List<String>>) -> Unit,
    ) {
        val choice = generation.incrementAndGet()
        val mine = PendingModel(url, model, settle)
        pending.value = mine
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                val before = voice.first()
                val result = voices(url, model)
                settingsLock.withLock {
                    val s = settings.settings.first()
                    if (s.speechServerUrl.orEmpty() != url || generation.get() != choice) return@withLock
                    val now = s.speechServerVoice.orEmpty()
                    // A voice tapped or typed meanwhile is the reader's choice.
                    val chosen = if (now != before) now else VoiceChoice.after(now, result.getOrNull(), changed)
                    setModelAndVoice(model, chosen)
                    if (settle && result.isSuccess) settings.settleSpeechServer(url)
                }
                result
            } finally {
                pending.compareAndSet(mine, null)
            }
            done(result)
        }
    }

    /**
     * Saves the [voice] the server's list kept for the saved model, and
     * when [settle], marks the new server at [url] settled, unless the
     * address, the model, or the [choice] it was asked at changed meanwhile.
     */
    suspend fun keepVoice(url: String, model: String, voice: String, choice: Int, settle: Boolean) {
        settingsLock.withLock {
            val s = settings.settings.first()
            if (s.speechServerUrl.orEmpty() != url || s.speechServerModel.orEmpty() != model) return
            if (generation.get() != choice) return
            if (s.speechServerVoice.orEmpty() != voice) setVoice(voice)
            if (settle) settings.settleSpeechServer(url)
        }
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

    /** Saves [key] for the server at [origin], ending the session read with the old one. */
    fun commitKey(origin: String, key: String, done: (Boolean) -> Unit) = keyCommits.submit(origin, {
        control.stop()
        keys.set(origin, key)
    }, done)

    /** Removes the key of the server at [origin]; other servers keep theirs. */
    fun commitKeyRemoval(origin: String, done: (Boolean) -> Unit) = keyCommits.submit(origin, {
        control.stop()
        keys.clear(origin)
    }, done)

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
            requireAccess(base.toString())
            Result.success(block(base, keys.get(ServerKeys.origin(base))))
        } catch (e: SpeechError) {
            Result.failure(e)
        }
    }

    private suspend fun requireAccess(url: String) {
        if (localNetwork.blocks(url)) {
            blockedUrl.value = url
            throw SpeechError.LocalNetworkBlocked()
        }
    }

    private val blockedUrl = MutableStateFlow<String?>(null)

    @Composable
    override fun AccessPrompt(theme: ReaderTheme) {
        val blocked by blockedUrl.collectAsStateWithLifecycle()
        if (blocked != null) SpeechLocalNetworkPrompt(localNetwork, blocked.orEmpty(), theme)
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

    var modelsJob: Job? = null
    var voicesJob: Job? = null

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenAiRows(feature: SpeechReadAloud, service: OpenAiSpeechService) {
    val storedUrl by service.url.collectAsState(initial = "")
    val accessAllowed = SpeechLocalNetworkPrompt(service.localNetwork, storedUrl)
    val storedModel by service.model.collectAsState(initial = "")
    val storedVoice by service.voice.collectAsState(initial = "")
    val chosenVoices by service.chosenVoices.collectAsState(initial = emptySet())
    val keyFailure by service.keyFailure.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var typed by remember { mutableStateOf(TextFieldValue(storedUrl)) }
    // The saved address the field last showed. A newer one replaces it there
    // unless the reader has typed or saved another since, so an earlier save
    // landing late never overwrites the field.
    var synced by remember { mutableStateOf(storedUrl) }
    val invalid = typed.text.isNotBlank() && OpenAiTts.baseUrl(typed.text) == null
    // An address saved here whose save has not landed yet: a key typed now is already its.
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val savingUrls by service.savingUrls.collectAsState()
    val shownUrl = pendingUrl ?: storedUrl
    val owner = OpenAiTts.baseUrl(shownUrl)?.let(ServerKeys::origin)
    val keyConfigured by remember(owner) { owner?.let(service::keyConfigured) ?: MutableStateFlow(false) }.collectAsState()
    val reachable = accessAllowed && OpenAiTts.baseUrl(storedUrl) != null
    var models by remember { mutableStateOf<Listing?>(null) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var voices by remember { mutableStateOf<Listing?>(null) }
    // A model picked whose voices are still being asked for: it is saved
    // together with one of them, never alone. The service holds it, so a
    // reopened screen still sees it.
    val pendingChoice by service.pendingModel.collectAsState()
    val pendingModel = pendingChoice?.takeIf { it.url == storedUrl }?.model
    // Bumped when the key is saved or removed, which the screen cannot otherwise see.
    var keyGeneration by remember { mutableIntStateOf(0) }
    val requests = remember { Requests() }
    val preview = rememberVoicePreview(feature)
    val shownModel = pendingModel ?: storedModel
    var presetsOpen by remember { mutableStateOf(false) }
    val urlFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    var keyFocusWanted by remember { mutableIntStateOf(0) }

    // Each list is fetched for one address (and voices for one model); a
    // reply to an older request is dropped. A fresh setup takes the first
    // model and voice, so it can read without another tap. On a new server
    // ([changed]) they replace a model or voice it lacks, and once they did
    // ([settle]), the server is no longer new.
    val loadVoices = { url: String, model: String, changed: Boolean, settle: Boolean ->
        val key = voiceKey(url, model)
        val id = ++requests.voices
        requests.voicesJob?.cancel()
        voices = Listing.Loading(key)
        requests.voicesJob = scope.launch {
            val choice = service.choice()
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
            if (voice != now || settle && result.isSuccess) {
                service.keepVoice(url, model, voice, choice, settle && result.isSuccess)
            }
        }
    }
    // The model is saved by the service, so a choice made as the screen closes is still saved.
    val chooseModel = { url: String, model: String, changed: Boolean, settle: Boolean ->
        preview.stop()
        val key = voiceKey(url, model)
        val id = ++requests.voices
        requests.voicesJob?.cancel()
        voices = Listing.Loading(key)
        service.commitModel(url, model, changed, settle) { result ->
            if (requests.voices == id) {
                voices = result.toListing(
                    key,
                    none = R.string.read_aloud_settings_server_voices_none,
                    failed = R.string.read_aloud_settings_server_voices_failed,
                    emptyIsOk = true,
                )
            }
        }
    }
    val loadModels = { url: String, changed: Boolean ->
        val id = ++requests.models
        val choice = service.choice()
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
            if (requests.models != id || service.choice() != choice || service.pendingModel.value != null) return@launch
            val first = listed?.firstOrNull()?.id
            when {
                first != null && (current.isBlank() || (changed && listed.none { it.id == current })) ->
                    chooseModel(url, first, changed, changed)
                // A new server stays new until its own model list is in.
                changed -> loadVoices(url, current, true, !listed.isNullOrEmpty())
            }
        }
    }
    val refresh = { url: String, changed: Boolean ->
        if (accessAllowed && OpenAiTts.baseUrl(url) != null) {
            loadModels(url, changed)
            // After a server change the voices wait for the models, which may replace the model.
            // A picked model still waiting for its voices is asked again, and saved with them.
            val pending = service.pendingModel.value?.takeIf { it.url == url }
            if (pending != null) {
                chooseModel(url, pending.model, true, pending.settle)
            } else if (!changed) {
                loadVoices(url, storedModel, false, false)
            }
        }
    }
    // Lists asked with the old key are dropped and asked again with the new one.
    val keySaved = { origin: String ->
        if (origin == OpenAiTts.baseUrl(storedUrl)?.let(ServerKeys::origin)) {
            requests.invalidate()
            keyGeneration++
            scope.launch { refresh(storedUrl, service.unsettled(storedUrl)) }
        }
    }
    LaunchedEffect(storedUrl, accessAllowed) {
        requests.invalidate()
        refresh(storedUrl, service.unsettled(storedUrl))
    }
    LaunchedEffect(storedUrl) {
        if (TypedField.follows(typed.text, synced, storedUrl, newer = pendingUrl != null)) {
            if (typed.text != storedUrl) typed = TextFieldValue(storedUrl)
            synced = storedUrl
        }
    }
    // Kept until every address save has landed, so an earlier one landing
    // first never hands the key field to a server no longer shown.
    LaunchedEffect(storedUrl, pendingUrl, savingUrls) {
        if (savingUrls == 0 && pendingUrl == storedUrl) pendingUrl = null
    }
    LaunchedEffect(keyFocusWanted) {
        if (keyFocusWanted > 0) runCatching { keyFocus.requestFocus() }
    }

    // Typing an address saves it on Done or when the field is left; an invalid one keeps its error.
    val saveUrl = { url: String ->
        if (url != (pendingUrl ?: storedUrl)) {
            pendingUrl = url
            requests.invalidate()
            service.commitUrl(url)
        }
    }
    val commitTyped = { if (!invalid) saveUrl(typed.text.trim()) }
    val latestCommitTyped by rememberUpdatedState(commitTyped)
    DisposableEffect(Unit) { onDispose { latestCommitTyped() } }
    val pick = { preset: SpeechServerPreset ->
        presetsOpen = false
        if (preset.example) {
            // Only an example: its host is selected for the reader's own, saved like a typed address.
            typed = TextFieldValue(preset.url, TextRange(preset.host.first, preset.host.last + 1))
            runCatching { urlFocus.requestFocus() }
        } else {
            // Leaving the fields first saves what they hold for the server they belong to.
            focus.clearFocus()
            typed = TextFieldValue(preset.url)
            saveUrl(preset.url)
            if (!service.keyConfigured(preset.origin).value) keyFocusWanted++
        }
    }
    val inUse = SpeechServerPresets.matching(shownUrl)?.takeIf { typed.text.trim() == shownUrl }
    val presetsLabel = stringResource(R.string.read_aloud_settings_server_presets)

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
        ExposedDropdownMenuBox(
            expanded = presetsOpen,
            onExpandedChange = { presetsOpen = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier
                    .fillMaxWidth()
                    // Typing in the field leaves the list closed: only its arrow opens it.
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = false)
                    .focusRequester(urlFocus)
                    .onLeaving { commitTyped() },
                singleLine = true,
                isError = invalid,
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
                    inUse != null -> {
                        { Text(stringResource(inUse.name)) }
                    }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!invalid) {
                            commitTyped()
                            focus.clearFocus()
                        }
                    },
                ),
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
    KeyRow(
        title = stringResource(R.string.read_aloud_settings_server_key),
        missing = stringResource(R.string.read_aloud_settings_server_key_missing),
        privacy = null,
        owner = owner,
        configured = keyConfigured,
        failed = owner != null && keyFailure == owner,
        onKey = { origin, key, done ->
            service.commitKey(origin, key) { ok ->
                done(ok)
                if (ok) keySaved(origin)
            }
        },
        onClear = { origin -> service.commitKeyRemoval(origin) { keySaved(origin) } },
        focusRequester = keyFocus,
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
        // The reader's own choice settles a new server once its voices are in.
        onSave = { model -> if (model != shownModel) chooseModel(storedUrl, model, true, true) },
        onRetry = { scope.launch { loadModels(storedUrl, service.unsettled(storedUrl)) } },
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
        onRetry = {
            val pending = pendingChoice?.takeIf { it.url == storedUrl }
            if (pending != null) {
                chooseModel(storedUrl, pending.model, true, pending.settle)
            } else {
                loadVoices(storedUrl, shownModel, false, false)
            }
        },
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

