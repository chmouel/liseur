package com.chmouel.liseur.tts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.LocalNetworkAccess
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.providers.ServerConnections
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A speech server of the reader's choice speaking OpenAI's speech API,
 * such as a self-hosted Kokoro: an address, a model and a voice, its key
 * being optional.
 */
internal class OpenAiSpeechService(
    private val connections: ServerConnections,
    private val settings: AppSettingsRepository,
    private val control: SessionControl,
    private val client: OpenAiTtsClient = OpenAiTtsClient(),
    // Saves outlive the settings screen: one made as it closes still completes.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : SpeechService {
    override val id = "openai"
    override val label = R.string.read_aloud_provider_openai
    override val summary = R.string.read_aloud_provider_openai_summary
    override val icon = Icons.Outlined.Dns

    val localNetwork: LocalNetworkAccess get() = connections.localNetwork

    /** Moves on with every change to the address or key of the server at [url]. */
    fun generation(url: String): StateFlow<Int> = connections.generation(ServerConnections.originOf(url).orEmpty())

    // Server choices and model choices take turns with it, so a slow choice
    // never writes over a newer one or onto another server.
    private val settingsLock = Mutex()

    /** Bumped by every server and model choice; a model choice is saved only if none came after it. */
    private val generation = AtomicInteger()

    /** The model choice still asking for its voices, if any; a reopened screen shows it and leaves it be. */
    class PendingModel(val url: String, val model: String, val settle: Boolean)

    private val pending = MutableStateFlow<PendingModel?>(null)
    val pendingModel: StateFlow<PendingModel?> = pending.asStateFlow()

    /** A choice made, and the server's address and key as they were when it was made. */
    data class Choice(val made: Int, val connection: Int)

    /** The current choice on the server at [url]; an automatic voice change taken at an older one is dropped. */
    fun choice(url: String): Choice = Choice(generation.get(), generation(url).value)

    /**
     * Runs [save] if [choice] is still the current one on the server at
     * [url]; a change to its address or key waits for it to finish.
     */
    private suspend fun <T> ifCurrent(url: String, choice: Choice, save: suspend () -> T): T? {
        if (generation.get() != choice.made) return null
        // Checked again once admitted: a newer choice may have come while it waited.
        return connections.unlessChanged(ServerConnections.originOf(url).orEmpty(), choice.connection) {
            if (generation.get() == choice.made) save() else null
        }
    }

    override fun owner(s: AppSettings): String? = ServerConnections.originOf(s.speechServerUrl.orEmpty())

    override val configured: Flow<Boolean> = settings.settings.map { s ->
        OpenAiTts.baseUrl(s.speechServerUrl.orEmpty()) != null &&
            !s.speechServerModel.isNullOrBlank() && !s.speechServerVoice.isNullOrBlank()
    }.distinctUntilChanged()

    /** The listed server read aloud uses, if any. */
    val server: Flow<ServerConnection?> = settings.settings.map { it.readAloudServerConnection }.distinctUntilChanged()

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
        val key = connections.key(ServerKeys.origin(base))
        // One sentence at a time: a small self-hosted server only slows down with more.
        return SessionVoice(name, 1) { text ->
            requireAccess(base.toString())
            client.synthesize(base, key, text, name, model)
        }
    }

    /**
     * Has read aloud use the listed server [id], ending the session read
     * from another. One just added is unsettled until its own lists have
     * chosen its model and voice, see [unsettled].
     */
    fun commitServer(serverId: String) {
        blockedUrl.value = null
        generation.incrementAndGet()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsLock.withLock {
                val s = settings.settings.first()
                if (s.readAloudProvider == id && s.readAloudServerConnection?.id == serverId) return@withLock
                control.stop()
                settings.selectReadAloudServer(serverId)
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
     * address, its key and the model choice are still the ones made here,
     * and then a new server counts as settled when [settle] (its model
     * list loaded).
     */
    fun commitModel(
        url: String,
        model: String,
        changed: Boolean,
        settle: Boolean,
        done: (Result<List<String>>) -> Unit,
    ) {
        generation.incrementAndGet()
        val choice = choice(url)
        val mine = PendingModel(url, model, settle)
        pending.value = mine
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                val before = voice.first()
                val result = voices(url, model)
                settingsLock.withLock {
                    val switch = ifCurrent(url, choice) {
                        val s = settings.settings.first()
                        if (s.speechServerUrl.orEmpty() != url) return@ifCurrent false
                        val now = s.speechServerVoice.orEmpty()
                        // A voice tapped or typed meanwhile is the reader's choice.
                        val chosen = if (now != before) now else VoiceChoice.after(now, result.getOrNull(), changed)
                        saveModelAndVoice(s, url, model, chosen).also {
                            if (settle && result.isSuccess) settings.settleSpeechServer(url)
                        }
                    }
                    // After the write: switching may list voices, which a key change must not wait for.
                    if (switch == true) control.switchVoice()
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
     * address, the model, or the [choice] it was asked at, key included,
     * changed meanwhile.
     */
    suspend fun keepVoice(url: String, model: String, voice: String, choice: Choice, settle: Boolean) {
        settingsLock.withLock {
            val switch = ifCurrent(url, choice) {
                val s = settings.settings.first()
                if (s.speechServerUrl.orEmpty() != url || s.speechServerModel.orEmpty() != model) return@ifCurrent false
                val changed = s.speechServerVoice.orEmpty() != voice
                if (changed) settings.setSpeechServerVoice(url, voice)
                if (settle) settings.settleSpeechServer(url)
                changed
            }
            if (switch == true) control.switchVoice()
        }
    }

    /**
     * Saves [model] with [voice] in one write, so a session never asks the
     * new model for the old model's voice. True when the session should
     * switch to them.
     */
    private suspend fun saveModelAndVoice(s: AppSettings, url: String, model: String, voice: String): Boolean {
        val changed = s.speechServerModel.orEmpty() != model.trim() || s.speechServerVoice.orEmpty() != voice.trim()
        settings.setSpeechServerModelAndVoice(url, model, voice)
        return changed
    }

    /**
     * Saves the [voice] the reader picked, also as the one for its
     * language, or for the one being read when its name does not say.
     */
    suspend fun setVoice(voice: String) {
        val before = settings.settings.first()
        val scope = scopeOf(before)
        val language = VoiceResolver.settingsLanguage(languagesOf(voice), control.sessionLanguage(this))
        if (scope == null || !remember(scope, language, voice)) {
            before.speechServerUrl?.let { settings.setSpeechServerVoice(it, voice) }
        }
        if (settings.settings.first() != before) control.switchVoice()
    }

    private fun scopeOf(s: AppSettings): VoiceScope? = scopeOf(s.speechServerUrl, s.speechServerModel)

    private fun scopeOf(url: String?, model: String?): VoiceScope? {
        val base = OpenAiTts.baseUrl(url.orEmpty()) ?: return null
        return VoiceScope(id, base.toString(), model?.trim()?.takeIf { it.isNotEmpty() } ?: return null)
    }

    /**
     * The voices offered for the saved server and model: those it lists,
     * narrowed by the voices chosen, with the saved one, which may be typed.
     * With no list, or none to be had, the voices typed, chosen or
     * remembered are all there is, and [VoiceCatalogue.failed] tells the
     * two apart.
     */
    override suspend fun catalogue(s: AppSettings): VoiceCatalogue? {
        val scope = scopeOf(s) ?: return null
        val url = s.speechServerUrl.orEmpty()
        val stored = s.speechServerVoice.orEmpty()
        // The server is slow to answer while it reads, so the last list it gave is kept.
        val listing = listedVoices?.takeIf { it.first == VoicesAsked(url, s.speechServerModel.orEmpty(), generation(url).value) }
            ?.second?.let { Result.success(it) }
            ?: voices(url, s.speechServerModel.orEmpty())
        val listed = listing.getOrNull().orEmpty()
        val ids = if (listed.isNotEmpty()) {
            VoiceLabel.offered(listed, s.speechServerVoices, stored) + stored
        } else {
            s.speechServerVoices.sorted() + stored + s.voicePreferences.filter(scope::owns).map { it.voice }
        }
        return VoiceCatalogue(
            scope = scope,
            voices = ids.filter { it.isNotBlank() }.distinct().map { CatalogueVoice(it, languagesOf(it)) },
            global = stored.takeIf { it.isNotBlank() },
            failed = listing.isFailure,
        )
    }

    override suspend fun remember(scope: VoiceScope, language: String?, voice: String): Boolean = settingsLock.withLock {
        settings.editReadAloudVoice {
            if (scopeOf(serverUrl, serverModel) != scope) return@editReadAloudVoice false
            setServerVoice(voice)
            language?.let { remember(scope.preference(it, voice)) }
            true
        }
    }

    @Composable
    override fun voiceLabel(voice: String): String = voiceChipLabel(VoiceLabel.of(voice))

    suspend fun setChosenVoices(voices: Set<String>) {
        settings.settings.first().speechServerUrl?.let { settings.setSpeechServerVoices(it, voices) }
    }

    /** The speech models the server at [url] lists, asked with the saved key. */
    suspend fun models(url: String): Result<List<SpeechModel>> =
        ask(url) { base, key -> OpenAiTts.speechModels(client.models(base, key)) }

    /** The voices [model] has on the server at [url], asked with the saved key. */
    suspend fun voices(url: String, model: String): Result<List<String>> {
        val asked = VoicesAsked(url, model, generation(url).value)
        return ask(url) { base, key -> client.voices(base, key, model) }.onSuccess {
            // A list asked with an address or key since changed is not kept.
            if (generation(url).value == asked.connection) listedVoices = asked to it
        }
    }

    private data class VoicesAsked(val url: String, val model: String, val connection: Int)

    /**
     * The last voices a server listed, by its address, model and key, so
     * the player's menu need not wait on a busy server.
     */
    private var listedVoices: Pair<VoicesAsked, List<String>>? = null

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
            Result.success(block(base, connections.key(ServerKeys.origin(base))))
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
    override fun SettingsRows(feature: SpeechReadAloud, onManageServices: () -> Unit) =
        OpenAiRows(feature, this, onManageServices)
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

internal fun priceText(price: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(price)

@Composable
private fun OpenAiRows(feature: SpeechReadAloud, service: OpenAiSpeechService, onManageServices: () -> Unit) {
    val server by service.server.collectAsState(initial = null)
    val storedUrl = server?.url.orEmpty()
    val accessAllowed = SpeechLocalNetworkPrompt(service.localNetwork, storedUrl)
    val storedModel by service.model.collectAsState(initial = "")
    val storedVoice by service.voice.collectAsState(initial = "")
    val chosenVoices by service.chosenVoices.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    // Moves on when the server's address or key changes on the Services page.
    val keyGeneration by remember(storedUrl) { service.generation(storedUrl) }.collectAsState()
    val reachable = accessAllowed && OpenAiTts.baseUrl(storedUrl) != null
    var models by remember { mutableStateOf<Listing?>(null) }
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var voices by remember { mutableStateOf<Listing?>(null) }
    // A model picked whose voices are still being asked for: it is saved
    // together with one of them, never alone. The service holds it, so a
    // reopened screen still sees it.
    val pendingChoice by service.pendingModel.collectAsState()
    val pendingModel = pendingChoice?.takeIf { it.url == storedUrl }?.model
    val requests = remember { Requests() }
    val preview = rememberVoicePreview(feature)
    val shownModel = pendingModel ?: storedModel

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
            val choice = service.choice(url)
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
        val choice = service.choice(url)
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
            if (requests.models != id || service.choice(url) != choice || service.pendingModel.value != null) return@launch
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
    // Lists asked with an old address or key are dropped and asked again.
    LaunchedEffect(storedUrl, accessAllowed, keyGeneration) {
        requests.invalidate()
        refresh(storedUrl, service.unsettled(storedUrl))
    }

    ServerLine(server, onManageServices)
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

/** The server in use, with the way to the Services page where it is set up. */
@Composable
private fun ServerLine(server: ServerConnection?, onManageServices: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.read_aloud_settings_server_url),
                style = MaterialTheme.typography.bodyLarge,
            )
            server?.let {
                Text(
                    text = it.host,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onManageServices) { Text(stringResource(R.string.services_manage)) }
    }
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

/** The language a voice's name says it speaks, as Kokoro's do; null when it says none. */
private fun languagesOf(voice: String): Set<String>? =
    VoiceLabel.of(voice).language?.let(SpeechLanguage::normalize)?.let(::setOf)
