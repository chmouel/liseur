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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    // What the screen reported before the scheduler started, in order.
    private val pendingShown = mutableListOf<Pair<Locator, Boolean>>()
    private var lastOrigin: Locator? = null

    private val choice: StateFlow<CompanionChoice?> = server.choice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val foreground: StateFlow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Keyed on what decides the identity, so an unrelated setting
    // changing does not cancel a recap that is under way.
    private val recapSettings: StateFlow<RecapSettings> =
        combine(choice, server.recapsEnabled, contentSha) { chosen, enabled, sha -> Triple(chosen, enabled, sha) }
            .distinctUntilChanged()
            .map { (chosen, enabled, sha) ->
                val setup = if (chosen != null && sha != null) {
                    RecapSetup(RecapIdentity(sha, chosen.serverId, chosen.model, Recaps.FORMAT), server.chat(chosen))
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
        log = { Log.w(TAG, "Character lookup failed: ${it.javaClass.simpleName}") },
    )

    val panel: StateFlow<CompanionPanel<Locator>?> = lookups.panel

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
            if (sha.isNullOrBlank()) return@launch
            contentSha.value = sha
            val started = RecapScheduler(viewModelScope, store.forBook(bookUrl, sha), opened, recapSettings, foreground)
                .also { it.start() }
            peeking(lastOrigin)
            placed.withLock {
                scheduler = started
                for ((place, movement) in pendingShown) {
                    val lo = quietly { opened.scopeOf(place, translated = false)?.lo } ?: continue
                    started.moved(lo, movement)
                }
                pendingShown.clear()
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
                val recaps = scheduler
                val opened = catalog.takeIf { it.isCompleted }?.getCompleted()
                if (recaps == null || opened == null) {
                    pendingShown += place to movement
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
            val lo = quietly { opened.scopeOf(origin, translated = false)?.lo } ?: 0
            if (lastOrigin === origin) recaps.peeking(lo)
        }
    }

    fun open(selection: String, place: Locator, translated: Boolean) = lookups.open(selection, place, translated)

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
