package com.chmouel.liseur.translate

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.tts.ListedField
import com.chmouel.liseur.tts.Listing
import com.chmouel.liseur.tts.OpenAiTts
import com.chmouel.liseur.tts.SpeechLocalNetworkPrompt
import com.chmouel.liseur.tts.priceText
import com.chmouel.liseur.tts.ServerKeys
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

    /** The server translation uses, when it uses one that is listed. */
    val server: Flow<ServerConnection?> = settings.settings.map { it.translationServerConnection }.distinctUntilChanged()

    override val configured: Flow<Boolean> = settings.settings.map { model(it) != null }.distinctUntilChanged()

    override fun model(s: AppSettings): String? =
        s.translationServerConnection?.let { s.translationModels[it.id] }?.takeIf { it.isNotBlank() }

    override suspend fun destination(): String? = settings.settings.first().translationServerConnection?.name

    override fun owner(s: AppSettings): String? =
        s.translationServerConnection?.let { ServerConnections.originOf(it.url) }

    override suspend fun targets(source: String?): Map<String, PairState>? = null

    override suspend fun sources(): Set<String>? = null

    override suspend fun translate(passage: String, source: String?, target: String, context: String?): String =
        ask(settings.settings.first(), passage, source, target, context)

    override fun open(source: String?, target: String, s: AppSettings): TranslationRun = object : TranslationRun {
        override suspend fun translate(sentence: String, context: String?) = ask(s, sentence, source, target, context)
    }

    private suspend fun ask(s: AppSettings, passage: String, source: String?, target: String, context: String?): String {
        val server = s.translationServerConnection ?: throw TranslationError.NotSetUp()
        val model = model(s) ?: throw TranslationError.NotSetUp()
        val base = OpenAiTts.baseUrl(server.url) ?: throw TranslationError.NotSetUp()
        if (connections.localNetwork.blocks(base.toString())) throw TranslationError.LocalNetworkBlocked()
        return client.translate(base, connections.key(ServerKeys.origin(base)), model, source, target, passage, context)
    }

    /** Saves in the service's scope, so a model typed as the screen closes is still saved. */
    private fun commit(save: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { save() }
    }

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit) {
        val server by server.collectAsState(initial = null)
        val url = server?.url ?: return
        val id = server?.id
        val stored by remember(id) { settings.settings.map { it.translationModels[id].orEmpty() } }
            .collectAsState(initial = "")
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
        ListedField(
            title = stringResource(R.string.read_aloud_settings_server_model),
            placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
            loading = stringResource(R.string.read_aloud_settings_server_models_loading),
            stored = stored,
            enabled = true,
            listing = listing?.takeIf { it.url == url },
            onSave = { model -> commit { settings.setTranslationServerModel(url, model) } },
            onRetry = { asked++ },
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
}
