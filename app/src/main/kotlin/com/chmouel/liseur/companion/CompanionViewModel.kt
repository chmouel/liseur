package com.chmouel.liseur.companion

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.library.BookFingerprintStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * The reading companion for the open book: character profiles and background chapter recaps.
 *
 * Scoped to the reader activity, so it lives exactly as long as the book
 * is open: closing the book cancels any lookup and any recap under way,
 * and what was queued waits in the database for the next opening.
 */
class CompanionViewModel(
    private val bookUrl: String,
    private val server: CompanionServer,
    private val store: CompanionStore,
    private val books: BookDao,
    private val fingerprints: BookFingerprintStore,
) : ViewModel() {
    private val catalog = CompletableDeferred<PublicationCatalog>()
    private val contentSha = MutableStateFlow<String?>(null)
    private val placed = Mutex()
    private var scheduler: RecapScheduler<Locator>? = null
    // Set once attach() has found no content identity, so no scheduler
    // will ever start and shown() stops collecting.
    private var noScheduler = false

    // What the screen reported before the scheduler started, in order.
    private val pendingShown = mutableListOf<Pair<Locator, Boolean>>()
    private var lastOrigin: Locator? = null
    // The last place reported, kept even while untracked: placing it costs a read, done only once work needs it.
    private var lastPlace: Locator? = null

    private val choice: StateFlow<CompanionChoice?> = server.choice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val foreground: StateFlow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // The reader's choice for this book, over the switch in settings.
    private val recapsOn = combine(
        server.recapsEnabled,
        store.recapChoice(bookUrl).catch {
            Log.w(TAG, "No recap choice for this book: ${it.javaClass.simpleName}")
            emit(null)
        },
    ) { inSettings, here -> recapsEnabledFor(here, inSettings) }.distinctUntilChanged()

    // Keyed on what decides the identity, so an unrelated setting
    // changing does not cancel a recap that is under way.
    private val recapSettings: StateFlow<RecapSettings> =
        combine(choice, recapsOn, contentSha) { chosen, enabled, sha -> Triple(chosen, enabled, sha) }
            .distinctUntilChanged()
            .map { (chosen, enabled, sha) ->
                val setup = if (chosen != null && sha != null) {
                    RecapSetup(RecapIdentity(sha, chosen.serverId, chosen.model, Recaps.FORMAT), server.chat(chosen), chosen.limits.recapBatch)
                } else {
                    null
                }
                RecapSettings(enabled, setup)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecapSettings(false, null))

    private val lookups = CompanionLookups(
        scope = viewModelScope,
        catalog = { catalog.await() },
        choice = { server.current() },
        chatFor = server::chat,
        hints = ::hints,
        aside = { block -> scheduler?.aside(block) ?: block() },
        log = { Log.w(TAG, "Character lookup failed: ${CompanionError.describe(it)}") },
    )

    val panel: StateFlow<CompanionPanel<Locator>?> = lookups.panel

    // Follows the stored switch, and is true at once on Allow so the picks it makes are accepted.
    private val storyAllowed = MutableStateFlow(false).also { allowed ->
        viewModelScope.launch { server.storyAutoOk.distinctUntilChanged().collect { allowed.value = it } }
    }
    private val storyController = MutableStateFlow<StoryController?>(null)
    private var storyScope: AskingScope? = null

    /** Whether Codex has a Story tab: a service is chosen and the book has a content identity. */
    val storyAvailable: StateFlow<Boolean> = combine(choice, contentSha) { chosen, sha -> chosen != null && sha != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The Story tab while it is on screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val story: StateFlow<StoryView?> = storyController
        .flatMapLatest { it?.view ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Read from settings rather than [choice], which reports none until it first loads. */
    suspend fun hasChoice(): Boolean = server.current() != null

    /** Whether background recaps are on; null until a server and model are chosen. */
    val recapsHere: StateFlow<Boolean?> = combine(choice, recapsOn) { chosen, on -> on.takeIf { chosen != null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Turns background recaps on or off for this book alone. */
    fun setRecapsHere(enabled: Boolean) {
        viewModelScope.launch { quietly { store.setRecapChoice(bookUrl, enabled) } }
    }

    init {
        viewModelScope.launch {
            // A panel waiting on setup asks as soon as the reader chooses.
            choice.drop(1).filterNotNull().collect { lookups.setUp() }
        }
    }

    /** The book is open: lookups can read it, and recaps start once its identity is known. */
    fun attach(publication: Publication) {
        if (catalog.isCompleted) return
        val opened = PublicationCatalog(publication)
        catalog.complete(opened)
        viewModelScope.launch {
            val sha = try {
                books.getByUrl(bookUrl)?.let { fingerprints.of(it) }?.sha256
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No content identity for recaps: ${e.javaClass.simpleName}")
                null
            }
            // Without an identity nothing is queued or saved; lookups
            // still work, searching the earlier chapters directly.
            if (sha.isNullOrBlank()) {
                placed.withLock {
                    noScheduler = true
                    pendingShown.clear()
                }
                return@launch
            }
            contentSha.value = sha
            val started = RecapScheduler(viewModelScope, store.forBook(bookUrl, sha), opened, recapSettings, foreground, storyAllowed)
                .also { it.start() }
            storyController.value = StoryController(
                viewModelScope, store.forBook(bookUrl, sha), opened, started, choice, storyAllowed,
                setAllowed = {
                    storyAllowed.value = true
                    viewModelScope.launch { quietly { server.setStoryAutoOk(true) } }
                },
            ).also { controller -> storyScope?.let(controller::show) }
            placed.withLock {
                scheduler = started
                peeking(lastOrigin)
                for ((place, movement) in pendingShown) {
                    val lo = quietly { opened.scopeOf(place, translated = false)?.lo } ?: continue
                    started.moved(lo, movement)
                }
                pendingShown.clear()
            }
            // Work asked for while the place was untracked waits until the page on screen is placed.
            started.tracking.collect { on ->
                if (!on) return@collect
                placed.withLock {
                    val place = lastPlace ?: return@withLock
                    val lo = quietly { opened.scopeOf(place, translated = false)?.lo } ?: return@withLock
                    started.moved(lo, movement = false)
                }
            }
        }
    }

    /**
     * The reader is now at [place]. [movement] when they got there by
     * reading on rather than by a jump, so finishing a chapter that way
     * queues its recap.
     */
    fun shown(place: Locator, movement: Boolean) {
        viewModelScope.launch {
            // In order, so a quick run of page turns cannot be taken backwards.
            placed.withLock {
                if (noScheduler) return@withLock
                lastPlace = place
                val recaps = scheduler
                val opened = catalog.takeIf { it.isCompleted }?.getCompleted()
                if (recaps == null || opened == null) {
                    pendingShown += place to movement
                    return@withLock
                }
                // Placing the page costs a resource read, so it is done only
                // while some recap or summary could be sent; otherwise the
                // place is forgotten rather than left to go stale.
                if (!recaps.tracking.value) {
                    recaps.unplaced()
                    return@withLock
                }
                val lo = quietly { opened.scopeOf(place, translated = false)?.lo } ?: return@withLock
                recaps.moved(lo, movement)
            }
        }
    }

    /** A source is shown: nothing at or past [origin], where the reader asked, is recapped meanwhile. */
    fun peeking(origin: Locator?) {
        lastOrigin = origin
        val recaps = scheduler ?: return
        if (origin == null) {
            recaps.peeking(null)
            return
        }
        val opened = catalog.takeIf { it.isCompleted }?.getCompleted() ?: return
        // Held at the first chapter until the origin is placed, so nothing
        // slips through while it is.
        recaps.peeking(0)
        viewModelScope.launch {
            // Taken as translated: the origin may be on a translated page,
            // and that narrows what is sent rather than widening it.
            val lo = quietly { opened.scopeOf(origin, translated = true)?.lo } ?: 0
            if (lastOrigin === origin) recaps.peeking(lo)
        }
    }

    fun open(selection: String, place: Locator, translated: Boolean) = lookups.open(selection, place, translated)

    /** Shows the Story tab for [scope], the place Codex was opened from. */
    fun showStory(scope: AskingScope) {
        storyScope = scope
        storyController.value?.show(scope)
    }

    /** The Story tab left the screen; the work it asked for goes on. */
    fun hideStory() {
        storyScope = null
        storyController.value?.hide()
    }

    fun allowStory() = storyController.value?.allow()

    fun declineStory() = storyController.value?.decline()

    fun summarizeChapter(unit: Int) = storyController.value?.summarize(unit)

    fun summarizeMissing() = storyController.value?.summarizeMissing()

    fun writeStory() = storyController.value?.write()

    fun stopStory() = storyController.value?.stop()

    fun retry() = lookups.retry()

    fun choose(candidate: Candidate<Locator>) = lookups.choose(candidate)

    fun cancel() = lookups.cancel()

    fun hide() = lookups.hide()

    fun reveal() = lookups.reveal()

    fun close() = lookups.close()

    private suspend fun hints(chosen: CompanionChoice, asked: AskingScope, passages: List<Passage<Locator>>): List<RecapHint> {
        val sha = contentSha.value ?: return emptyList()
        val opened = catalog.await()
        val identity = RecapIdentity(sha, chosen.serverId, chosen.model, Recaps.FORMAT)
        val saved = quietly { store.forBook(bookUrl, sha).recaps(identity) } ?: return emptyList()
        val decoded = saved
            // A recap saved when the chapters were cut differently is not this chapter's.
            .filter { it.unit.index < opened.map.chapterCount && opened.map.unitKey(it.unit.index) == it.unit.key }
            .mapNotNull { row -> ChapterRecap.decode(row.json)?.let { row.unit.index to it } }
        return Recaps.hints(decoded, asked, passages)
    }

    private suspend fun <T> quietly(work: suspend () -> T): T? = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Companion recaps: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "Companion"

        fun factory(bookUrl: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                CompanionViewModel(
                    bookUrl = bookUrl,
                    server = container.companionServer,
                    store = container.companionStore,
                    books = container.database.bookDao(),
                    fingerprints = container.bookFingerprints,
                )
            }
        }
    }
}
