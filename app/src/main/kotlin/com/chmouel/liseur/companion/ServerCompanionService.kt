package com.chmouel.liseur.companion

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceOption
import com.chmouel.liseur.translate.OpenAiChatClient
import com.chmouel.liseur.translate.TextModel
import com.chmouel.liseur.translate.TextPrice
import com.chmouel.liseur.translate.TranslationError
import com.chmouel.liseur.tts.ListedField
import com.chmouel.liseur.tts.Listing
import com.chmouel.liseur.tts.OpenAiTts
import com.chmouel.liseur.tts.ServerKeys
import com.chmouel.liseur.tts.SpeechLocalNetworkPrompt
import com.chmouel.liseur.tts.priceText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A server listed on the Services page, asked through its OpenAI-compatible chat. */
internal class ServerCompanionService(
    private val connections: ServerConnections,
    private val settings: AppSettingsRepository,
    private val client: OpenAiChatClient = OpenAiChatClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : CompanionService {
    override val id = CompanionService.SERVER
    override val offered: Flow<Boolean> = flowOf(true)
    override val privacy = R.string.companion_settings_privacy

    @OptIn(ExperimentalCoroutinesApi::class)
    override val choice: Flow<CompanionChoice?> = settings.settings
        .map(::chosen)
        .distinctUntilChanged()
        .flatMapLatest { c ->
            if (c == null) flowOf(null) else connections.generation(c.origin).map { CompanionChoice(c.serverId, c.url, c.model, c.origin, it, id) }
        }
        .distinctUntilChanged()

    private data class Chosen(val serverId: String, val url: String, val model: String, val origin: String)

    private fun chosen(s: AppSettings): Chosen? {
        val server = s.companionServerConnection ?: return null
        val model = s.companionModel?.takeIf { it.isNotBlank() } ?: return null
        val base = OpenAiTts.baseUrl(server.url) ?: return null
        return Chosen(server.id, server.url, model, ServerKeys.origin(base))
    }

    override fun chat(choice: CompanionChoice): CompanionChat = CompanionChat { messages, beforeRequest ->
        val base = OpenAiTts.baseUrl(choice.url) ?: throw TranslationError.NotSetUp()
        if (connections.localNetwork.blocks(base.toString())) throw TranslationError.LocalNetworkBlocked()
        val generation = connections.generation(choice.origin)
        val noTemperature = settings.settings.first().companionStates[choice.serverId]?.let { it.noTemperature && it.model == choice.model } == true
        client.chat(
            base,
            connections.key(choice.origin),
            choice.model,
            messages.map { it.role to it.content },
            temperature = 0.0.takeUnless { noTemperature },
            beforeRequest = {
                if (generation.value != choice.generation) throw TranslationError.Changed()
                beforeRequest()
            },
            onTemperatureRefused = {
                // The answer came back; failing to remember this only means the next request retries.
                try {
                    settings.setCompanionServerModel(choice.url, choice.model, noTemperature = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not save a refused temperature: ${e.javaClass.simpleName}")
                }
            },
        )
    }

    /** Every listed server, as the Services page holds them. */
    val servers: Flow<ServerList> = connections.servers

    /** The listed server chosen for the companion, and the model on it, for the settings screen. */
    val chosenServer: Flow<ServerConnection?> = settings.settings.map { it.companionServerConnection }.distinctUntilChanged()

    val chosenModel: Flow<String> = settings.settings.map { it.companionModel.orEmpty() }.distinctUntilChanged()

    /** Has the companion use the listed server [id], over any other service. */
    suspend fun select(id: String) = settings.selectCompanionServer(id)

    suspend fun setModel(url: String, model: String) = settings.setCompanionServerModel(url, model)

    /** Saves [model] in the service's scope, so leaving the screen does not drop it. */
    fun commitModel(url: String, model: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { setModel(url, model) }
    }

    /** The models the server at [url] lists. */
    suspend fun models(url: String): Result<List<TextModel>> {
        val base = OpenAiTts.baseUrl(url) ?: return Result.failure(TranslationError.NotSetUp())
        return try {
            if (connections.localNetwork.blocks(base.toString())) throw TranslationError.LocalNetworkBlocked()
            Result.success(client.textModels(base, connections.key(ServerKeys.origin(base))))
        } catch (e: TranslationError) {
            Result.failure(e)
        }
    }

    @Composable
    override fun heading(): ServiceHeading {
        val chosen by chosenServer.collectAsState(initial = null)
        return ServiceHeading(
            Icons.Outlined.Dns,
            chosen?.name ?: stringResource(R.string.companion_settings_server_none),
            chosen?.host ?: stringResource(R.string.companion_settings_server_none_detail),
        )
    }

    @Composable
    override fun summary(): String? {
        val chosen by chosenServer.collectAsState(initial = null)
        val model by chosenModel.collectAsState(initial = "")
        return chosen?.takeIf { model.isNotBlank() }?.let { stringResource(R.string.companion_settings_entry_summary, it.name, model) }
    }

    @Composable
    override fun options(inUse: Boolean, pick: (suspend () -> Unit) -> Unit): List<ServiceOption> {
        val chosen by chosenServer.collectAsState(initial = null)
        val listed by servers.collectAsState(initial = ServerList.Empty)
        return listed.readable.orEmpty().map { connection ->
            ServiceOption(
                selected = inUse && connection.id == chosen?.id,
                icon = Icons.Outlined.Dns,
                headline = connection.name,
                supporting = connection.host,
                onClick = { pick { select(connection.id) } },
            )
        }
    }

    @Composable
    override fun SettingsRows(onManageServices: () -> Unit) {
        val chosen by chosenServer.collectAsState(initial = null)
        chosen?.let { connection -> ModelField(connection.url) }
    }

    @Composable
    private fun ModelField(url: String) {
        val stored by chosenModel.collectAsState(initial = "")
        val allowed = SpeechLocalNetworkPrompt(connections.localNetwork, url)
        val origin = ServerConnections.originOf(url).orEmpty()
        val generation by remember(origin) { connections.generation(origin) }.collectAsState()
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
                onFailure = {
                    Listing.Failed(
                        url,
                        when (it) {
                            is TranslationError.LocalNetworkBlocked -> R.string.server_local_network_blocked
                            is TranslationError.Network -> R.string.read_aloud_settings_server_unreachable
                            is TranslationError.InvalidKey -> R.string.read_aloud_settings_server_refused
                            else -> R.string.read_aloud_settings_server_models_failed
                        },
                    )
                },
            )
        }
        Box(Modifier.padding(top = 16.dp)) {
            ListedField(
                title = stringResource(R.string.companion_settings_model),
                placeholder = stringResource(R.string.read_aloud_settings_server_model_choose),
                loading = stringResource(R.string.read_aloud_settings_server_models_loading),
                stored = stored,
                enabled = true,
                listing = listing?.takeIf { it.url == url },
                onSave = { model -> commitModel(url, model) },
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
    }

    private companion object {
        const val TAG = "ServerCompanion"
    }
}
