package com.chmouel.liseur.translate

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.data.settings.TranslationContextSize
import com.chmouel.liseur.providers.ServerConnections
import com.chmouel.liseur.providers.ServiceAccounts
import com.chmouel.liseur.providers.ServicesScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Translation with the [services] the build offers, the device's first.
 * The chosen one is resolved at use time from the settings; without a
 * stored choice, the fallback is never offered to the reader. A server
 * that is not listed, or a service this build lacks, falls back to device.
 */
internal class ServiceTranslate(
    private val settings: AppSettingsRepository,
    private val connections: ServerConnections,
    private val accounts: ServiceAccounts,
    val services: List<TranslationService>,
    /** Page translations kept on this phone, shown and cleared in the settings; opened when shown. */
    val saved: Lazy<SavedTranslations>? = null,
    /** The account's copy of them, which the settings' Clear can take too (ADR-0050). */
    val accountClears: AccountTranslationClears? = null,
    /** Has a full sync send a clear just recorded. */
    val requestFullSync: suspend () -> Unit = {},
    /** Whether the library offers the setup sheet; the Play build's. */
    override val offersSetup: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : TranslateFeature {
    override val isAvailable = true

    val service: Flow<TranslationService> = settings.settings.map(::resolve).distinctUntilChanged()

    /** The service the reader chose, by id; null until one is chosen, whatever [service] falls back to. */
    val provider: Flow<String?> = settings.settings.map { it.translationProvider }.distinctUntilChanged()

    override val chosen: StateFlow<Boolean> = provider.map { it != null }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val ready: StateFlow<Boolean> = settings.settings
        .map { s -> s.translationProvider?.let { resolve(s) } }
        .distinctUntilChanged()
        .flatMapLatest { it?.configured ?: flowOf(false) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), false)

    override val action: StateFlow<Int> = service.map { it.action }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), R.string.translation_action)

    override suspend fun refresh() = resolve(settings.settings.first()).refresh()

    /** The services in [answeredBy], as [PageSwap.by] names them, whose translations need a credit beside them. */
    fun credits(answeredBy: Set<String>): List<TranslationService> {
        val ids = answeredBy.mapNotNullTo(mutableSetOf(), ::pageIdentityService)
        return services.filter { it.credited && it.id in ids }
    }

    /** The server translation uses, when it uses one. */
    val server = settings.settings.map { s -> s.translationServerConnection.takeIf { resolve(s).id == ServerSettings.SERVER_PROVIDER } }
        .distinctUntilChanged()

    val servers = connections.servers

    /** What a translation depends on besides its passage and languages: a change asks again. */
    val choice: Flow<List<Any?>> = settings.settings.map(::chosen).distinctUntilChanged()

    private fun chosen(s: AppSettings): List<Any?> {
        val server = s.translationServerConnection
        return listOf(
            resolve(s).id,
            server,
            server?.let { s.translationModels[it.id] },
            identityOptions(s),
            s.translationGeminiModel,
            s.translationBookContext,
        )
    }

    /** How much of the book's earlier text goes with each sentence of a page, for the services that take it. */
    val context: Flow<TranslationContextSize> = settings.settings.map { it.translationContext }.distinctUntilChanged()

    suspend fun setContext(size: TranslationContextSize) = settings.setTranslationContext(size)

    /** Whether the service chosen now sends the book's earlier text with a sentence. */
    val takesContext: Flow<Boolean> = settings.settings.map { resolve(it).usesContext(it) }.distinctUntilChanged()

    /** What the reader wrote to tell an AI translator about the book; null for the default, empty for nothing. */
    val bookContext: Flow<String?> = settings.settings.map { it.translationBookContext }.distinctUntilChanged()

    /** Saves in the feature's scope, so a template typed as the screen closes is still saved. */
    fun setBookContext(template: String?) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { settings.setTranslationBookContext(template) }
    }

    /** Whether the service chosen now is told about the book. */
    val takesBook: Flow<Boolean> = settings.settings.map { resolve(it).usesBook(it) }.distinctUntilChanged()

    /** The language the reader picked to translate into; null follows the app's. */
    val target: Flow<String?> = settings.settings.map { it.translationTarget }.distinctUntilChanged()

    suspend fun setTarget(tag: String?) = settings.setTranslationTarget(tag)

    suspend fun choose(service: TranslationService) = settings.setTranslationProvider(service.id)

    suspend fun chooseServer(id: String) = settings.selectTranslationServer(id)

    /** Where [service] sends the text under the current settings, for "Translated by %s.". */
    suspend fun destination(service: TranslationService) = service.destination(settings.settings.first())

    private val requests = TranslationRequests { owner -> connections.generation(owner).value }

    /**
     * [passage] translated by [service], with what the reader chose to say
     * about the [book] it comes from, asked again when its address or key
     * changed while it was asked. Throws [TranslationError].
     */
    suspend fun translate(
        service: TranslationService,
        passage: String,
        source: String?,
        target: String,
        book: BookFacts? = null,
    ): String = requests.run({ service.owner(settings.settings.first()) }) {
        val about = TranslationBookContext.render(bookOf(service, settings.settings.first()), book)
        service.translate(passage, source, target, book = about)
    }

    /** A whole-book run never rebinds or falls back to an on-device translator. */
    suspend fun wholeBookIdentity(source: String?, target: String): String {
        val snapshot = settings.settings.first()
        val service = wholeBookService(snapshot)
        if (!service.configured.first()) throw TranslationError.NotSetUp()
        return pageIdentity(snapshot, service.id, service.model(snapshot), source, target, contextOf(service, snapshot), bookOf(service, snapshot))
    }

    suspend fun wholeBookConfiguration(source: String?, target: String): String {
        val snapshot = settings.settings.first()
        val service = wholeBookService(snapshot)
        if (!service.configured.first()) throw TranslationError.NotSetUp()
        val owner = service.owner(snapshot) ?: throw TranslationError.NotSetUp()
        return translationKey("${pinnedRecipe(snapshot, service, source, target)}\u0000${connections.generation(owner).value}")
    }

    suspend fun openPinned(source: String?, target: String, expectedIdentity: String? = null): SentenceTranslator {
        if (source != null && TranslationLanguages.same(source, target)) throw WholeBookSameLanguageException(target)
        val snapshot = settings.settings.first()
        val service = wholeBookService(snapshot)
        if (!service.configured.first()) throw TranslationError.NotSetUp()
        val identity = pageIdentity(snapshot, service.id, service.model(snapshot), source, target, contextOf(service, snapshot), bookOf(service, snapshot))
        if (expectedIdentity != null && identity != expectedIdentity) throw TranslationError.SettingsChanged()
        val owner = service.owner(snapshot) ?: throw TranslationError.NotSetUp()
        val generation = connections.generation(owner).value
        val recipe = pinnedRecipe(snapshot, service, source, target)
        val context = contextOf(service, snapshot)
        val template = bookOf(service, snapshot)

        suspend fun guard() {
            val current = settings.settings.first()
            if (current.translationProvider != service.id ||
                recipe != pinnedRecipe(current, service, source, target) ||
                connections.generation(owner).value != generation
            ) {
                throw TranslationError.SettingsChanged()
            }
        }

        guard()
        val run = service.openPinned(source, target, snapshot, ::guard)
        try {
            guard()
        } catch (e: Exception) {
            run.close()
            throw e
        }
        return object : SentenceTranslator {
            override val source = source
            override val target = target
            override val destination = service.destination(snapshot)

            private suspend fun check(wanted: String = identity) {
                guard()
                checkIdentity(wanted)
            }

            private fun checkIdentity(wanted: String) {
                if (wanted != identity) throw TranslationError.SettingsChanged()
            }

            override suspend fun answering(): String {
                check()
                return identity
            }

            override suspend fun contextSize(identity: String): TranslationContextSize {
                // These inputs are frozen; answering() and every submission check current settings.
                checkIdentity(identity)
                return context
            }

            override suspend fun book(identity: String, facts: BookFacts?): String? {
                checkIdentity(identity)
                return TranslationBookContext.render(template, facts)
            }

            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated {
                check(identity)
                val text = run.translate(sentence, context, TranslationBookContext.render(template, book))
                check(identity)
                return Translated(text, identity)
            }

            override val batches = run.batches

            override suspend fun translateBatch(sentences: List<String>, context: String?, identity: String, book: BookFacts?): List<Translated> {
                check(identity)
                val texts = run.translateBatch(sentences, context, TranslationBookContext.render(template, book))
                check(identity)
                if (texts.size != sentences.size) throw TranslationError.Malformed("wrong number of translations")
                return texts.map { Translated(it, identity) }
            }

            override fun cached(count: Int) = run.cached(count)

            override fun close() = run.close()
        }
    }

    /** Fetches model metadata only; an unadvertised price stays unknown. */
    suspend fun wholeBookPrice(): TextPrice? {
        val s = settings.settings.first()
        return wholeBookService(s).wholeBookPrice(s)
    }

    /** The actual prompt size, frozen for a preview without submitting any book text. */
    suspend fun wholeBookPrompt(source: String?, target: String): (SentenceRequest) -> Int {
        val s = settings.settings.first()
        val service = wholeBookService(s)
        return { request -> service.requestCharacters(s, source, target, request) }
    }

    private fun wholeBookService(s: AppSettings): TranslationService =
        services.firstOrNull { it.id == s.translationProvider && it.supportsPinned }
            ?.takeIf { it.model(s) != null && it.owner(s) != null }
            ?: throw TranslationError.NotSetUp()

    private fun pinnedRecipe(s: AppSettings, service: TranslationService, source: String?, target: String): List<Any?> =
        listOf(
            pageIdentity(s, service.id, service.model(s), source, target, contextOf(service, s), bookOf(service, s)),
            s.translationProvider,
            s.translationTarget,
            service.owner(s),
            s.translationServerConnection?.takeIf { service.id == ServerSettings.SERVER_PROVIDER }?.let {
                listOf(it.url, s.translationStates[it.id]?.keepAlive)
            },
        )

    /**
     * A translator that follows the settings: each sentence goes to the
     * service and model chosen when it is asked, so one picked from the
     * bar after a refusal takes over the run.
     */
    override suspend fun openPage(source: String?, target: String): SentenceTranslator? {
        // A book left translated before translation was opt-in waits for the reader to choose.
        if (settings.settings.first().translationProvider == null) return null

        class Bound(
            val identity: String,
            val service: TranslationService,
            val run: TranslationRun,
            destination: String?,
            val owner: String?,
            var choice: List<Any?>,
            val context: TranslationContextSize,
            val book: String?,
        ) {
            var destination by mutableStateOf(destination)
        }

        suspend fun wanted(): Triple<TranslationService, String, AppSettings> {
            // One read, so the service and the identity it is known by come from the same settings.
            val s = settings.settings.first()
            val service = resolve(s)
            val identity = pageIdentity(s, service.id, service.model(s), source, target, contextOf(service, s), bookOf(service, s))
            return Triple(service, identity, s)
        }

        // Opened last, after the suspending calls, so a cancelled bind holds nothing open.
        suspend fun bind(): Bound {
            val (service, identity, s) = wanted()
            val destination = service.destination(s)
            return Bound(
                identity,
                service,
                service.open(source, target, s),
                destination,
                service.owner(s),
                chosen(s),
                contextOf(service, s),
                bookOf(service, s),
            )
        }

        var bound by mutableStateOf(bind())

        suspend fun rebound(): Bound {
            val (service, identity, s) = wanted()
            if (identity != bound.identity) {
                val next = bind()
                bound.run.close()
                bound = next
            } else if (chosen(s) != bound.choice) {
                // Only what the bar shows changed, such as the server's name: the same run goes on.
                bound.choice = chosen(s)
                bound.destination = service.destination(s)
            }
            return bound
        }

        return object : SentenceTranslator {
            override val source = source
            override val target = target
            override val destination get() = bound.destination

            override suspend fun answering() = rebound().identity

            override suspend fun contextSize(identity: String) =
                rebound().takeIf { it.identity == identity }?.context ?: TranslationContextSize.OFF

            override suspend fun book(identity: String, facts: BookFacts?) =
                TranslationBookContext.render(rebound().takeIf { it.identity == identity }?.book, facts)

            override suspend fun translate(sentence: String, context: String?, identity: String, book: BookFacts?): Translated? {
                var moved = false
                // Judged by the settings the run asks with: a change while it is out binds again, and the new one is only asked under the identity looked up.
                val (current, text) = requests.run(::rebound, Bound::owner) {
                    moved = it.identity != identity
                    if (moved) "" else it.run.translate(sentence, context, TranslationBookContext.render(it.book, book))
                }
                return if (moved) null else Translated(text, current.identity)
            }

            override fun close() = bound.run.close()
        }
    }

    override suspend fun pageLanguages(declared: List<String>, app: Locale): PageTranslationModes.Mode? {
        val s = settings.settings.first()
        if (s.translationProvider == null) return null
        val service = resolve(s)
        service.refresh()
        val source = TranslationLanguages.source(declared)
        val target = TranslationLanguages.target(s.translationTarget, app)
        val step = TranslationStep.languages(service.configured.first(), service.detectsLanguage, source, target, service.targets(source))
        return PageTranslationModes.Mode(source, target).takeIf { step == TranslationStep.Translate }
    }

    override suspend fun translatesWholeBooks(): Boolean {
        val service = try {
            wholeBookService(settings.settings.first())
        } catch (_: TranslationError.NotSetUp) {
            return false
        }
        return service.configured.first()
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
    override fun SetupSheet(onDismiss: () -> Unit, onMore: () -> Unit) = TranslationSetupSheet(this, onDismiss, onMore)

    @Composable
    override fun Sheet(
        passage: String,
        declared: List<String>,
        onDismiss: () -> Unit,
        onTranslatePage: ((source: String?, target: String) -> Unit)?,
        book: suspend () -> BookFacts?,
    ) = TranslationSheet(this, passage, declared, onDismiss, onTranslatePage, book)

    @Composable
    override fun PageBar(
        translator: SentenceTranslator,
        state: PageTranslationState,
        answeredBy: Set<String>,
        theme: ReaderTheme,
        controls: Boolean,
        onRetry: () -> Unit,
        onStop: () -> Unit,
        modifier: Modifier,
    ) = PageTranslationBar(this, translator, state, answeredBy, theme, controls, onRetry, onStop, modifier)

    @Composable
    override fun PageCredit(answeredBy: Set<String>, theme: ReaderTheme, modifier: Modifier) {
        val credits = credits(answeredBy)
        if (credits.isEmpty()) return
        Column(modifier) { credits.forEach { it.Credit(theme.background, Modifier) } }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}

/** How much earlier text [service] takes with a sentence under [s]: none when it uses no context. */
internal fun contextOf(service: TranslationService, s: AppSettings): TranslationContextSize =
    if (service.usesContext(s)) s.translationContext else TranslationContextSize.OFF

/** The template of what [service] is told about the book under [s]; null when it is told nothing. */
internal fun bookOf(service: TranslationService, s: AppSettings): String? =
    if (service.usesBook(s)) TranslationBookContext.template(s.translationBookContext) else null

/**
 * What a page translation by [service] with [model] depends on, written to
 * be kept across versions: the server by its address, never its name, its
 * key, or a model another service would use. The server's options that
 * change a translation join it only once set, so a page translated before
 * they existed keeps its identity. How much earlier text goes with a
 * sentence, [context], joins after them, and only for a service that
 * sends it; so does the template of what it is told about the [book].
 */
internal fun pageIdentity(
    s: AppSettings,
    service: String,
    model: String?,
    source: String?,
    target: String,
    context: TranslationContextSize = TranslationContextSize.OFF,
    book: String? = null,
): String {
    val server = s.translationServerConnection?.takeIf { service == ServerSettings.SERVER_PROVIDER }
    val fields = listOf(IDENTITY_VERSION, TranslationPrompt.version(model), service, server?.id, model, source, target)
    val tuned = server?.let { identityOptions(s, model) }.orEmpty()
    val window = listOfNotNull(context.takeIf { it != TranslationContextSize.OFF }?.let { "context=${it.id}" })
    val about = listOfNotNull(book?.let { "book=${TranslationBookContext.identity(it)}" })
    return (fields + tuned + window + about).joinToString("\u0000") { it?.toString().orEmpty() }
}

/**
 * What of the chosen server's options changes its translations by [model]:
 * not how long it keeps the model loaded, a context TranslateGemma never
 * gets, nor whether the model refused a temperature. Null when none is set.
 */
private fun identityOptions(s: AppSettings, model: String? = null): List<Any?>? {
    val state = s.translationServerConnection?.let { s.translationStates[it.id] } ?: return null
    val context = state.context.takeIf { model == null || !TranslationPrompt.isTranslateGemma(model) }
    return listOf(state.temperature, context, state.instructions, state.maxTokens).takeIf { it.any { field -> field != null } }
}

/** The service a [pageIdentity] names; null for one that is not an identity. */
internal fun pageIdentityService(identity: String): String? = identity.split('\u0000').getOrNull(SERVICE_FIELD)

private const val SERVICE_FIELD = 2

// Raised when the fields of [pageIdentity] change.
private const val IDENTITY_VERSION = 1
