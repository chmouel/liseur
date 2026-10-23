package com.chmouel.liseur.translate

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.BuildConfig
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.data.settings.ServerTranslationState
import com.chmouel.liseur.data.settings.TranslationContextSize
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.tts.GradiumTts
import com.chmouel.liseur.tts.ListedField
import com.chmouel.liseur.tts.ListedMenu
import com.chmouel.liseur.tts.Listing
import com.chmouel.liseur.tts.OpenAiTts
import com.chmouel.liseur.tts.SpeechLocalNetworkPrompt
import com.chmouel.liseur.tts.priceText
import com.chmouel.liseur.tts.ServerKeys
import com.chmouel.liseur.ui.settings.SettingsExpandableGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A listed OpenAI-compatible server, with the model the reader chose on it for translation. */
internal class ServerTranslationService(
    private val connections: ServerConnections,
    private val settings: AppSettingsRepository,
    private val client: OpenAiChatClient = OpenAiChatClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TranslationService {
    override val id = ServerSettings.SERVER_PROVIDER
    override val label = R.string.translation_provider_server
    override val summary = R.string.translation_provider_server_summary
    override val icon = Icons.Outlined.Dns
    override val detectsLanguage = true
    override val supportsPinned = true

    /** The server translation uses, when it uses one that is listed. */
    val server: Flow<ServerConnection?> = settings.settings.map { it.translationServerConnection }.distinctUntilChanged()

    override val configured: Flow<Boolean> = settings.settings.map { model(it) != null }.distinctUntilChanged()

    override fun model(s: AppSettings): String? =
        s.translationServerConnection?.let { s.translationModels[it.id] }?.takeIf { it.isNotBlank() }

    override fun usesContext(s: AppSettings): Boolean {
        val model = model(s) ?: return false
        return !TranslationPrompt.isTranslateGemma(model) && tuning(s).context != false
    }

    // TranslateGemma's single message would translate what it is told about the book.
    override fun usesBook(s: AppSettings): Boolean = model(s)?.let { !TranslationPrompt.isTranslateGemma(it) } == true

    /** How the reader asked for the chosen server to be asked; all defaults when untouched. */
    private fun tuning(s: AppSettings): ServerTranslationState =
        s.translationServerConnection?.let { s.translationStates[it.id] } ?: ServerTranslationState()

    override fun destination(s: AppSettings): String? = s.translationServerConnection?.name

    override fun owner(s: AppSettings): String? =
        s.translationServerConnection?.let { ServerConnections.originOf(it.url) }

    override suspend fun targets(source: String?): Map<String, PairState>? = null

    override suspend fun sources(): Set<String>? = null

    override suspend fun translate(passage: String, source: String?, target: String, context: String?, book: String?): String =
        ask(settings.settings.first(), passage, source, target, context, book)

    override fun open(source: String?, target: String, s: AppSettings, preferences: BookTranslationPreferences): TranslationRun = object : TranslationRun {
        override suspend fun translate(sentence: String, context: String?, book: String?) = ask(s, sentence, source, target, context, book, preferences = preferences)
    }

    override suspend fun openPinned(source: String?, target: String, s: AppSettings, beforeRequest: suspend () -> Unit, preferences: BookTranslationPreferences): TranslationRun {
        val server = s.translationServerConnection ?: throw TranslationError.NotSetUp()
        val base = OpenAiTts.baseUrl(server.url) ?: throw TranslationError.NotSetUp()
        if (model(s) == null) throw TranslationError.NotSetUp()
        beforeRequest()
        val key = connections.key(ServerKeys.origin(base))
        beforeRequest()
        val gemma = TranslationPrompt.isTranslateGemma(model(s))
        return object : TranslationRun {
            private val diagnostics = ChatCacheDiagnostics()

            override suspend fun translate(sentence: String, context: String?, book: String?) =
                ask(s, sentence, source, target, context, book, preferences = preferences, pinned = true, frozenKey = key, beforeRequest = beforeRequest, usage = diagnostics::response)

            // TranslateGemma's trained format has room for one passage only, and a reply
            // cap the reader set for one sentence would cut every batch short.
            override val batches = !gemma && tuning(s).maxTokens == null

            override suspend fun translateBatch(sentences: List<String>, context: String?, book: String?): List<String> {
                check(batches)
                return ask(s, sentences, source, target, context, book, preferences = preferences, frozenKey = key, beforeRequest = beforeRequest, usage = diagnostics::response)
            }

            override fun cached(count: Int) = diagnostics.cached(count)

            override fun close() {
                val snapshot = diagnostics.snapshot()
                if (BuildConfig.DEBUG && (snapshot.responses > 0 || snapshot.localSentenceHits > 0)) Log.d("ChatCache", snapshot.toString())
            }
        }
    }

    override suspend fun wholeBookPrice(s: AppSettings): TextPrice? {
        val server = s.translationServerConnection ?: return null
        val model = model(s) ?: return null
        return models(server.url).getOrNull()?.firstOrNull { it.id == model }?.price
    }

    override fun requestCharacters(s: AppSettings, source: String?, target: String, request: SentenceRequest, preferences: BookTranslationPreferences): Int =
        translationRequestCharacters(model(s), source, target, request.sentence.text, request.context.takeIf { usesContext(s) }, request.book, listOfNotNull(tuning(s).instructions, preferences.instructions(listOf(request.sentence.text))).joinToString("\n").ifEmpty { null })

    private suspend fun ask(
        s: AppSettings,
        passage: String,
        source: String?,
        target: String,
        context: String?,
        book: String?,
        preferences: BookTranslationPreferences = BookTranslationPreferences(),
        pinned: Boolean = false,
        frozenKey: String? = null,
        beforeRequest: suspend () -> Unit = {},
        usage: ((ChatUsage) -> Unit)? = null,
    ): String {
        beforeRequest()
        val call = call(s)
        return client.translate(
            call.base,
            if (pinned) frozenKey else connections.key(ServerKeys.origin(call.base)),
            call.model,
            source,
            target,
            passage,
            context.takeIf { call.tuning.context != false },
            temperature = call.temperature(),
            instructions = listOfNotNull(call.tuning.instructions, preferences.instructions(listOf(passage))).joinToString("\n").ifEmpty { null },
            maxTokens = call.tuning.maxTokens,
            keepAlive = call.tuning.keepAlive,
            book = book,
            beforeRequest = beforeRequest,
            allowTransportRetry = !pinned,
            onTemperatureRefused = call::temperatureRefused,
            usage = usage,
        )
    }

    /** A pinned batch: always the [frozenKey], never a transport retry. */
    private suspend fun ask(
        s: AppSettings,
        sentences: List<String>,
        source: String?,
        target: String,
        context: String?,
        book: String?,
        preferences: BookTranslationPreferences = BookTranslationPreferences(),
        frozenKey: String?,
        beforeRequest: suspend () -> Unit,
        usage: (ChatUsage) -> Unit,
    ): List<String> {
        beforeRequest()
        val call = call(s)
        return client.translateBatch(
            call.base,
            frozenKey,
            call.model,
            source,
            target,
            sentences,
            context.takeIf { call.tuning.context != false },
            temperature = call.temperature(),
            instructions = listOfNotNull(call.tuning.instructions, preferences.instructions(sentences)).joinToString("\n").ifEmpty { null },
            maxTokens = call.tuning.maxTokens,
            keepAlive = call.tuning.keepAlive,
            book = book,
            beforeRequest = beforeRequest,
            allowTransportRetry = false,
            onTemperatureRefused = call::temperatureRefused,
            usage = usage,
        )
    }

    private suspend fun call(s: AppSettings): Call {
        val server = s.translationServerConnection ?: throw TranslationError.NotSetUp()
        val model = model(s) ?: throw TranslationError.NotSetUp()
        val base = OpenAiTts.baseUrl(server.url) ?: throw TranslationError.NotSetUp()
        if (connections.localNetwork.blocks(base.toString())) throw TranslationError.LocalNetworkBlocked()
        return Call(server, model, base, tuning(s))
    }

    private inner class Call(val server: ServerConnection, val model: String, val base: okhttp3.HttpUrl, val tuning: ServerTranslationState) {
        suspend fun temperature(): Double? = (tuning.temperature ?: 0.0).takeUnless { refusesTemperature(server.id, model) }

        suspend fun temperatureRefused() {
            // The translation came back; failing to remember this only means the next request retries.
            try {
                settings.editTranslationServer(server.url) { if (it.model == model) it.copy(noTemperature = true) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save a refused temperature: ${e.javaClass.simpleName}")
            }
        }
    }

    // Read afresh, as a page's run keeps the settings it was opened with and would otherwise be refused each sentence.
    private suspend fun refusesTemperature(server: String, model: String): Boolean =
        settings.settings.first().translationStates[server]?.let { it.noTemperature && it.model == model } == true

    /** Saves in the service's scope, so a model typed as the screen closes is still saved. */
    private fun commit(save: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { save() }
    }

    override val hasModels = true

    override fun savedModel(s: AppSettings, server: ServerConnection?): String =
        server?.let { s.translationModels[it.id] }.orEmpty()

    override fun readyWith(configured: Boolean, server: ServerConnection?, model: String) =
        server != null && model.isNotBlank()

    override suspend fun saveModel(server: ServerConnection?, model: String) {
        server?.let { settings.setTranslationServerModel(it.url, model) }
    }

    // A server found to write text is asked once per address and key; one
    // found speech-only is asked again, as it may gain a text model.
    private val offered = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * False for a speech-only server: Gradium, or one whose model list has
     * no model that writes text, such as Kokoro's. One that cannot be
     * asked, offline or behind local network access, is still offered.
     */
    override suspend fun offers(server: ServerConnection): Boolean {
        val base = OpenAiTts.baseUrl(server.url) ?: return true
        if (GradiumTts.isGradium(base)) return false
        val origin = ServerConnections.originOf(server.url) ?: return true
        val key = "${server.url}\u0000${connections.generation(origin).value}"
        offered[key]?.let { return it }
        val listed = models(server.url).getOrNull() ?: return true
        return listed.isNotEmpty().also { if (it) offered[key] = true }
    }

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit, onOpenPage: () -> Unit) {
        val server by server.collectAsState(initial = null)
        val url = server?.url ?: return
        val id = server?.id
        val stored by remember(id) { settings.settings.map { it.translationModels[id].orEmpty() } }
            .collectAsState(initial = "")
        val models = rememberModels(url)
        ListedField(
            title = stringResource(R.string.read_aloud_settings_server_model),
            placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
            loading = stringResource(R.string.read_aloud_settings_server_models_loading),
            stored = stored,
            enabled = true,
            listing = models.listing,
            onSave = { model -> commit { settings.setTranslationServerModel(url, model) } },
            onRetry = models.retry,
            detail = models.detail,
        )
    }

    @Composable
    override fun ModelMenu(server: ServerConnection?, model: String, onModel: (String) -> Unit, modifier: Modifier) {
        val url = server?.url ?: return
        val models = rememberModels(url)
        ListedMenu(
            value = model,
            onValueChange = onModel,
            label = stringResource(R.string.read_aloud_settings_server_model),
            placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
            loading = stringResource(R.string.read_aloud_settings_server_models_loading),
            enabled = true,
            listing = models.listing,
            onRetry = models.retry,
            detail = models.detail,
            modifier = modifier,
        )
    }

    private class Models(val listing: Listing?, val retry: () -> Unit, val detail: @Composable (String) -> String?)

    /** What [url] lists, asked once local network access allows it, with each model's price. */
    @Composable
    private fun rememberModels(url: String): Models {
        val allowed = SpeechLocalNetworkPrompt(connections.localNetwork, url)
        val origin = ServerConnections.originOf(url)
        // Moves on when the server's address or key changes on the Services page.
        val generation by remember(origin) { connections.generation(origin.orEmpty()) }.collectAsState()
        var listing by remember(url, generation) { mutableStateOf<Listing?>(null) }
        var prices by remember(url, generation) { mutableStateOf(emptyMap<String, TextPrice>()) }
        var asked by remember(url, generation) { mutableIntStateOf(0) }
        LaunchedEffect(url, generation, asked, allowed) {
            if (!allowed) return@LaunchedEffect
            listing = Listing.Loading(url)
            listing = models(url).fold(
                onSuccess = { models ->
                    prices = models.mapNotNull { m -> m.price?.let { m.id to it } }.toMap()
                    if (models.isEmpty()) {
                        Listing.Failed(url, R.string.read_aloud_settings_server_models_none)
                    } else {
                        Listing.Loaded(url, models.map { it.id })
                    }
                },
                onFailure = { Listing.Failed(url, listingMessage(it)) },
            )
        }
        return Models(
            listing = listing?.takeIf { it.url == url },
            retry = { asked++ },
            detail = { id ->
                prices[id]?.let {
                    val locale = LocalConfiguration.current.locales[0]
                    if (it.input == 0.0 && it.output == 0.0) {
                        stringResource(R.string.translation_settings_model_free)
                    } else {
                        stringResource(R.string.translation_settings_model_price, priceText(it.input, locale), priceText(it.output, locale))
                    }
                }
            },
        )
    }

    @Composable
    override fun AdvancedSettings() {
        val server by server.collectAsState(initial = null)
        val url = server?.url ?: return
        val id = server?.id
        val state by remember(id) { settings.settings.map { it.translationStates[id] ?: ServerTranslationState() } }
            .collectAsState(initial = null)
        val current = state ?: return
        var open by remember { mutableStateOf(false) }
        SettingsExpandableGroup(
            title = stringResource(R.string.settings_advanced),
            expanded = open,
            onExpandedChange = { open = it },
        ) {
            ServerTranslationOptions(
                state = current,
                translateGemma = current.model?.let(TranslationPrompt::isTranslateGemma) == true,
                onChange = { change -> commit { settings.editTranslationServer(url, change) } },
            )
        }
    }

    private suspend fun models(url: String): Result<List<TextModel>> {
        val base = OpenAiTts.baseUrl(url) ?: return Result.failure(TranslationError.NotSetUp())
        return try {
            if (connections.localNetwork.blocks(base.toString())) throw TranslationError.LocalNetworkBlocked()
            Result.success(client.textModels(base, connections.key(ServerKeys.origin(base))))
        } catch (e: TranslationError) {
            Result.failure(e)
        }
    }

    private fun listingMessage(error: Throwable): Int = when (error) {
        is TranslationError.LocalNetworkBlocked -> R.string.server_local_network_blocked
        is TranslationError.Network -> R.string.read_aloud_settings_server_unreachable
        is TranslationError.InvalidKey -> R.string.read_aloud_settings_server_refused
        else -> R.string.read_aloud_settings_server_models_failed
    }

    private companion object {
        const val TAG = "ServerTranslation"
    }
}
