package com.chmouel.liseur.companion

import android.util.Log
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.translate.OpenAiChatClient
import com.chmouel.liseur.translate.TextModel
import com.chmouel.liseur.translate.TranslationError
import com.chmouel.liseur.tts.OpenAiTts
import com.chmouel.liseur.tts.ServerKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The server and model character profiles use, as chosen now, with
 * [generation] moving on when the server's address or key changes.
 */
data class CompanionChoice(val serverId: String, val url: String, val model: String, val origin: String, val generation: Int)

/**
 * The reading companion's access to the server the reader chose for them.
 * Each lookup and each recapped chapter keeps the [CompanionChoice] it
 * started with, and a request made after it changed fails with
 * [TranslationError.Changed] rather than mixing two servers or models.
 */
class CompanionServer internal constructor(
    private val connections: ServerConnections,
    private val settings: AppSettingsRepository,
    private val client: OpenAiChatClient = OpenAiChatClient(),
) {
    /** The current choice; null until a listed server and a model on it are chosen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val choice: Flow<CompanionChoice?> = settings.settings
        .map(::chosen)
        .distinctUntilChanged()
        .flatMapLatest { c ->
            if (c == null) flowOf(null) else connections.generation(c.origin).map { CompanionChoice(c.serverId, c.url, c.model, c.origin, it) }
        }
        .distinctUntilChanged()

    private data class Chosen(val serverId: String, val url: String, val model: String, val origin: String)

    private fun chosen(s: AppSettings): Chosen? {
        val server = s.companionServerConnection ?: return null
        val model = s.companionModel?.takeIf { it.isNotBlank() } ?: return null
        val base = OpenAiTts.baseUrl(server.url) ?: return null
        return Chosen(server.id, server.url, model, ServerKeys.origin(base))
    }

    /** The choice as it stands, read once. */
    suspend fun current(): CompanionChoice? = choice.first()

    /** Whether background recaps are on. */
    val recapsEnabled: Flow<Boolean> = settings.settings.map { it.companionRecaps }.distinctUntilChanged()

    /** A chat bound to [choice]: every request goes to that server and model, or fails. */
    fun chat(choice: CompanionChoice): CompanionChat = CompanionChat { messages, beforeRequest ->
        val base = OpenAiTts.baseUrl(choice.url) ?: throw TranslationError.NotSetUp()
        if (current() != choice) throw TranslationError.Changed()
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

    /** The listed server chosen for character profiles, and the model on it, for the settings screen. */
    val chosenServer: Flow<ServerConnection?> = settings.settings.map { it.companionServerConnection }.distinctUntilChanged()

    val chosenModel: Flow<String> = settings.settings.map { it.companionModel.orEmpty() }.distinctUntilChanged()

    internal val localNetwork get() = connections.localNetwork

    /** Moves on when a server's address or key changes on the Services page. */
    fun generation(origin: String): StateFlow<Int> = connections.generation(origin)

    suspend fun select(id: String) = settings.selectCompanionServer(id)

    suspend fun setModel(url: String, model: String) = settings.setCompanionServerModel(url, model)

    /** Turns background recaps on or off; off forgets every waiting chapter and stops the one under way. */
    suspend fun setRecaps(enabled: Boolean, store: CompanionStore) {
        settings.setCompanionRecaps(enabled)
        if (!enabled) store.stopRecaps()
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

    private companion object {
        const val TAG = "CompanionServer"
    }
}
