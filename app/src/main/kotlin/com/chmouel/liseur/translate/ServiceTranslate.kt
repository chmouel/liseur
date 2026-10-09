package com.chmouel.liseur.translate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceAccounts
import com.chmouel.liseur.providers.ServicesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Translation with the [services] the build offers, the device's first.
 * The chosen one is resolved at use time from the settings, so changing
 * it never rebuilds anything; a server that is not listed, or a service
 * this build lacks, falls back to the device.
 */
internal class ServiceTranslate(
    private val settings: AppSettingsRepository,
    private val connections: ServerConnections,
    private val accounts: ServiceAccounts,
    val services: List<TranslationService>,
    /** Page translations kept on this phone, shown and cleared in the settings; opened when shown. */
    val saved: Lazy<SavedTranslations>? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TranslateFeature {
    override val isAvailable = true

    val service: Flow<TranslationService> = settings.settings.map(::resolve).distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val ready: StateFlow<Boolean> = service.flatMapLatest { it.configured }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), false)

    override suspend fun refresh() = resolve(settings.settings.first()).refresh()

    /** The server translation uses, when it uses one. */
    val server = settings.settings.map { s -> s.translationServerConnection.takeIf { resolve(s).id == ServerSettings.SERVER_PROVIDER } }
        .distinctUntilChanged()

    val servers = connections.servers

    /** What a translation depends on besides its passage and languages: a change asks again. */
    val choice: Flow<List<Any?>> = settings.settings.map(::chosen).distinctUntilChanged()

    private fun chosen(s: AppSettings): List<Any?> {
        val server = s.translationServerConnection
        return listOf(resolve(s).id, server, server?.let { s.translationModels[it.id] }, s.translationGeminiModel)
    }

    /** The language the reader picked to translate into; null follows the app's. */
    val target: Flow<String?> = settings.settings.map { it.translationTarget }.distinctUntilChanged()

    suspend fun setTarget(tag: String?) = settings.setTranslationTarget(tag)

    suspend fun choose(service: TranslationService) = settings.setTranslationProvider(service.id)

    suspend fun chooseServer(id: String) = settings.selectTranslationServer(id)

    private val requests = TranslationRequests { owner -> connections.generation(owner).value }

    /**
     * [passage] translated by [service], asked again when its address or
     * key changed while it was asked. Throws [TranslationError].
     */
    suspend fun translate(service: TranslationService, passage: String, source: String?, target: String): String =
        requests.run({ service.owner(settings.settings.first()) }) { service.translate(passage, source, target) }

    /**
     * A translator that follows the settings: each sentence goes to the
     * service and model chosen when it is asked, so one picked from the
     * bar after a refusal takes over the run.
     */
    override suspend fun openPage(source: String?, target: String): SentenceTranslator {
        class Bound(
            val identity: String,
            val service: TranslationService,
            val run: TranslationRun,
            val destination: String?,
            val owner: String?,
        )

        suspend fun wanted(): Triple<TranslationService, String, AppSettings> {
            // One read, so the service and the identity it is known by come from the same settings.
            val s = settings.settings.first()
            val service = resolve(s)
            return Triple(service, pageIdentity(s, service.id, service.model(s), source, target), s)
        }

        // Opened last, after the suspending calls, so a cancelled bind holds nothing open.
        suspend fun bind(): Bound {
            val (service, identity, s) = wanted()
            val destination = service.destination()
            return Bound(identity, service, service.open(source, target, s), destination, service.owner(s))
        }

        var bound by mutableStateOf(bind())

        suspend fun rebound(): Bound {
            if (wanted().second != bound.identity) {
                val next = bind()
                bound.run.close()
                bound = next
            }
            return bound
        }

        return object : SentenceTranslator {
            override val source = source
            override val target = target
            override val destination get() = bound.destination

            override suspend fun answering() = rebound().identity

            override suspend fun translate(sentence: String, context: String?): Translated {
                // Judged by the settings the run asks with: a change while it is out binds again and asks the new one.
                val (current, text) = requests.run(::rebound, Bound::owner) { it.run.translate(sentence, context) }
                return Translated(text, current.identity)
            }

            override fun close() = bound.run.close()
        }
    }

    private fun resolve(s: AppSettings): TranslationService {
        val wanted = when (val provider = s.translationProvider) {
            ServerSettings.SERVER_PROVIDER -> provider.takeIf { s.translationServerConnection != null }
            else -> provider
        }
        return services.firstOrNull { it.id == wanted } ?: services.first()
    }

    @Composable
    fun ServicesPage(onBack: () -> Unit) = ServicesScreen(connections, accounts, onBack)

    @Composable
    override fun SettingsEntry(onClick: () -> Unit) = TranslationSettingsEntry(this, onClick)

    @Composable
    override fun SettingsScreen(onBack: () -> Unit, services: Boolean) = TranslationSettingsScreen(this, onBack, services)

    @Composable
    override fun Sheet(
        passage: String,
        declared: List<String>,
        onDismiss: () -> Unit,
        onTranslatePage: ((source: String?, target: String) -> Unit)?,
    ) = TranslationSheet(this, passage, declared, onDismiss, onTranslatePage)

    @Composable
    override fun PageBar(
        translator: SentenceTranslator,
        state: PageTranslationState,
        theme: ReaderTheme,
        controls: Boolean,
        onRetry: () -> Unit,
        onStop: () -> Unit,
        modifier: Modifier,
    ) = PageTranslationBar(this, translator, state, theme, controls, onRetry, onStop, modifier)

    @Composable
    override fun SelectionButton(onClick: () -> Unit) = TranslationSelectionButton(onClick)

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}

/**
 * What a page translation by [service] with [model] depends on, written to
 * be kept across versions: the server by its address, never its name, its
 * key, or a model another service would use.
 */
internal fun pageIdentity(s: AppSettings, service: String, model: String?, source: String?, target: String): String {
    val server = s.translationServerConnection?.takeIf { service == ServerSettings.SERVER_PROVIDER }
    return listOf(IDENTITY_VERSION, TranslationPrompt.VERSION, service, server?.id, model, source, target)
        .joinToString("\u0000") { it?.toString().orEmpty() }
}

// Raised when the fields of [pageIdentity] change.
private const val IDENTITY_VERSION = 1
