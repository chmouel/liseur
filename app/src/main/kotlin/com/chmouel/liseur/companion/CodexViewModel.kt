package com.chmouel.liseur.companion

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
import com.chmouel.liseur.data.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Progress counts committed chapters, including empty ones; partial writes never count. */
enum class CodexStage { PREPARING, SCANNING, PAUSED, COMPLETE, FAILED }
data class CodexProgress(val stage: CodexStage = CodexStage.PREPARING, val completed: Int = 0, val total: Int = 0)
/** A character page's two views: what the companion says about them, and where the book names them. */
enum class CharacterPane { PROFILE, MENTIONS }

data class CodexDetail(
    val name: String,
    val other: String? = null,
    val passages: List<Passage<Locator>> = emptyList(),
    val loading: Boolean = true,
    val more: Boolean = false,
    val failed: Boolean = false,
    /** Committed chapters when [passages] were last read; later ones can only add rows after them. */
    val scanned: Int = 0,
    val pane: CharacterPane = CharacterPane.MENTIONS,
    /** Another name for [name], such as an alias from its profile, whose mentions are listed instead. */
    val focus: String? = null,
) {
    /** What the mentions are of. */
    val term: String get() = focus ?: name

    internal fun lists(other: CodexDetail?): Boolean =
        other != null && name == other.name && this.other == other.other && focus == other.focus
}
/** Codex's tabs; Story is there only while a companion service is chosen. */
enum class CodexTab { STORY, NAMES, RELATIONSHIPS }

data class CodexPanel(
    val ceiling: Int?,
    val titles: List<String?>,
    val placing: Boolean = true,
    val visible: Boolean = true,
    val query: String = "",
    val entries: List<CodexEntry> = emptyList(),
    val graph: CodexGraph? = null,
    val detail: CodexDetail? = null,
    /** The relationships query failed; the scan itself is unaffected. */
    val graphFailed: Boolean = false,
    /** The tab showing; kept here, with [search], across a peek. */
    val tab: CodexTab = CodexTab.NAMES,
    /** Where Codex was opened from, frozen for the session; null until placed, or when known only by its chapter. */
    val scope: AskingScope? = null,
    val search: String = query,
) {
    /** Whether Story's list is what shows; a character page over it, or a peek, pauses it. */
    val storyInFront: Boolean get() = visible && tab == CodexTab.STORY && detail == null
}

/**
 * The local codex belongs to the open reader, independently of any AI
 * provider. With one chosen it also hosts the character profiles and
 * Story, so it opens when either is there; the scan, names, relationships
 * and mentions only while the on-device codex is on.
 */
class CodexViewModel(
    private val bookUrl: String,
    private val store: CodexStore,
    settings: AppSettingsRepository,
    private val books: BookDao,
    private val fingerprints: BookFingerprintStore,
    profilesFlow: Flow<Boolean> = flowOf(false),
    foregroundFlow: Flow<Boolean>? = null,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.settings.map { it.codexEnabled }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Whether a companion service is chosen, so characters have a profile. */
    val profiles: StateFlow<Boolean> = profilesFlow.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _panel = MutableStateFlow<CodexPanel?>(null)
    val panel = _panel.asStateFlow()
    private val _progress = MutableStateFlow(CodexProgress())
    val progress = _progress.asStateFlow()
    private var publication: Publication? = null
    private var catalog: PublicationCatalog? = null
    private var identity: CodexBookRow? = null
    private var scan: Job? = null
    private var opening: Job? = null
    private var detailJob: Job? = null
    private var graphJob: Job? = null
    /** Names changed while [graphJob] was building; it runs once more instead of restarting. */
    private var graphStale = false
    private val scanLock = Mutex()
    private var foreground = true
    private var lastRefresh = 0L
    private var paused = false

    init {
        viewModelScope.launch {
            enabled.collect {
                if (it) return@collect
                pause()
                // Story and the profiles stay while there is a service for them.
                if (profiles.value) _panel.update { panel -> panel?.copy(tab = CodexTab.STORY) } else close()
            }
        }
        viewModelScope.launch {
            var had = profiles.value
            profiles.collect {
                if (had && !it && !enabled.value) close()
                had = it
            }
        }
        viewModelScope.launch {
            val active = foregroundFlow ?: ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
            active.collect {
                foreground = it
                if (!foreground) pause()
            }
        }
    }

    fun attach(publication: Publication) {
        if (this.publication != null) return
        this.publication = publication
        catalog = PublicationCatalog(publication)
    }

    /** Opens Codex at [place]; on [story] when nothing was selected, as from the menu, or when there is no scan. */
    fun open(place: Locator, translated: Boolean, selection: String? = null, story: Boolean = false) {
        val opened = catalog ?: return
        val tab = if (story && selection == null || !enabled.value) CodexTab.STORY else CodexTab.NAMES
        start(List(opened.map.chapterCount) { opened.map.titleOf(it) }, selection, tab, CharacterPane.MENTIONS) {
            opened.scopeOf(place, translated)
        }
    }

    /** Opens the page of the character [selection] names, selected at [place], on [pane]. */
    fun openCharacter(place: Locator, translated: Boolean, selection: String, pane: CharacterPane) {
        val opened = catalog ?: return
        val tab = if (enabled.value) CodexTab.NAMES else CodexTab.STORY
        start(List(opened.map.chapterCount) { opened.map.titleOf(it) }, selection, tab, pane) { opened.scopeOf(place, translated) }
    }

    /** Shows a new panel; [place] resolves the scope Codex is frozen to. */
    private fun start(
        titles: List<String?>,
        selection: String?,
        tab: CodexTab,
        pane: CharacterPane,
        place: suspend () -> AskingScope?,
    ) {
        opening?.cancel()
        detailJob?.cancel()
        graphJob?.cancel()
        paused = false
        val name = selection?.let(PassageText::name)?.let(CodexExtractor::storedName)
        val query = name ?: selection?.trim().orEmpty()
        // A profile is asked about whatever was selected; the lookup says when it cannot be a name.
        val subject = name ?: selection?.trim()?.takeIf { pane == CharacterPane.PROFILE && it.isNotEmpty() }
        _panel.value = CodexPanel(
            ceiling = null,
            titles = titles,
            query = query,
            detail = subject?.let { CodexDetail(it, pane = pane) },
            tab = tab,
        )
        opening = viewModelScope.launch {
            try {
                val placed = place()
                _panel.update { it?.copy(ceiling = placed?.lo, scope = placed, placing = false) }
                if (placed == null) return@launch
                if (identity != null && enabled.value) {
                    refresh()
                    if (_panel.value?.detail?.passages?.isEmpty() == true) loadMore()
                }
                if (!paused) resume()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _progress.update { it.copy(stage = CodexStage.FAILED) }
            }
        }
    }

    fun resume() {
        if (!enabled.value || !foreground || scan?.isActive == true) return
        paused = false
        if (identity != null && _progress.value.stage == CodexStage.COMPLETE) {
            viewModelScope.launch {
                refresh()
                val detail = _panel.value?.detail
                if (detail != null && detail.loading && detail.passages.isEmpty()) loadMore()
            }
            return
        }
        val publication = publication ?: return
        val map = catalog?.map ?: return
        _progress.update { it.copy(stage = CodexStage.PREPARING, total = map.chapterCount) }
        scan = viewModelScope.launch {
            scanLock.withLock {
                try {
                    val sha = checkNotNull(books.getByUrl(bookUrl)?.let { fingerprints.of(it) }?.sha256)
                    val book = CodexBookRow(bookUrl, CodexStore.identity(sha, map), map.chapterCount)
                    identity = book
                    val completed = store.prepare(book).toSet()
                    val first = (0 until book.total).firstOrNull { it !in completed } ?: book.total
                    _progress.value = CodexProgress(CodexStage.SCANNING, completed.size, book.total)
                    refresh()
                    if (_panel.value?.detail?.passages?.isEmpty() == true) loadMore()
                    val source = PublicationCodexSource(publication, map)
                    source.scan(first) { unit, passages ->
                        if (unit !in completed) {
                            val stats = withContext(Dispatchers.Default) { CodexExtractor.extract(unitText(passages)) }
                            store.save(book, unit, stats, passages)
                            _progress.update { it.copy(completed = it.completed + 1) }
                            // Chapters at or past the ceiling cannot change what the panel shows.
                            val ceiling = _panel.value?.ceiling
                            if (ceiling != null && unit < ceiling) {
                                val now = clock()
                                if (now - lastRefresh >= 250 || unit == ceiling - 1) {
                                    lastRefresh = now
                                    // The scan walks on IO; job bookkeeping stays on the main thread.
                                    withContext(Dispatchers.Main.immediate) { refresh(committed = true) }
                                }
                            }
                        }
                    }
                    _progress.update { it.copy(stage = CodexStage.COMPLETE) }
                    refresh()
                    val detail = _panel.value?.detail
                    if (detail != null && detail.passages.isEmpty() && !detail.failed) loadMore()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _progress.update { it.copy(stage = CodexStage.FAILED) }
                }
            }
        }
    }

    fun pause() {
        paused = true
        scan?.cancel()
        scan = null
        if (_progress.value.stage in listOf(CodexStage.PREPARING, CodexStage.SCANNING)) {
            _progress.update { it.copy(stage = CodexStage.PAUSED) }
        }
    }

    /** [committed]: a chapter below the ceiling just committed, so shared chapters may have moved even if no name changed. */
    private suspend fun refresh(committed: Boolean = false) {
        val book = identity ?: return
        val before = _panel.value ?: return
        val ceiling = before.ceiling ?: return
        val entries = store.entries(book, ceiling)
        val current = _panel.value ?: return
        if (current.ceiling != ceiling) return
        if (entries != current.entries) {
            // The previous map stays up until its replacement is ready.
            _panel.update { it?.copy(entries = entries, graphFailed = false) }
            if (current.tab == CodexTab.RELATIONSHIPS) rebuildGraph()
        } else if (current.tab == CodexTab.RELATIONSHIPS && !current.graphFailed && (committed || current.graph == null && graphJob?.isActive != true)) {
            rebuildGraph()
        }
        topUpDetail()
    }

    /** A detail read while scanning picks up chapters committed since, without a loading flash. */
    private fun topUpDetail() {
        val panel = _panel.value ?: return
        val detail = panel.detail ?: return
        val ceiling = panel.ceiling ?: return
        if (detail.loading || detail.failed || detail.more || detailJob?.isActive == true) return
        if (detail.scanned < ceiling && _progress.value.completed > detail.scanned) loadMore(quiet = true)
    }

    fun showGraph() {
        _panel.update { it?.copy(tab = CodexTab.RELATIONSHIPS, graphFailed = false) }
        rebuildGraph()
    }

    fun showNames() = _panel.update { it?.copy(tab = CodexTab.NAMES) }

    fun showStory() = _panel.update { it?.copy(tab = CodexTab.STORY, detail = null) }

    fun search(text: String) = _panel.update { it?.copy(search = text) }

    /** Builds the relationships from the current names; while one build runs, later changes queue a single rerun. */
    private fun rebuildGraph() {
        if (graphJob?.isActive == true) {
            graphStale = true
            return
        }
        graphJob = viewModelScope.launch {
            do {
                graphStale = false
                val panel = _panel.value ?: return@launch
                val ceiling = panel.ceiling ?: return@launch
                val book = identity ?: return@launch
                try {
                    val graph = store.graph(book, ceiling, panel.entries)
                    _panel.update { current ->
                        // An unchanged graph keeps its instance.
                        if (current?.ceiling == ceiling && current.graph != graph) current.copy(graph = graph, graphFailed = false) else current
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _panel.update { current -> if (current?.ceiling == ceiling) current.copy(graphFailed = true) else current }
                    return@launch
                }
            } while (graphStale && _panel.value?.tab == CodexTab.RELATIONSHIPS)
        }
    }

    /** Opens the page of [name], or of the pair it makes with [other]: on the profile while there is a service to ask. */
    fun detail(name: String, other: String? = null) {
        detailJob?.cancel()
        val pane = if (profiles.value) CharacterPane.PROFILE else CharacterPane.MENTIONS
        _panel.update { it?.copy(detail = CodexDetail(name, other, pane = pane, loading = enabled.value)) }
        if (enabled.value) loadMore()
    }

    fun showPane(pane: CharacterPane) = _panel.update { panel -> panel?.copy(detail = panel.detail?.copy(pane = pane)) }

    /** Lists the mentions of [alias], another name for the character shown, or of the character again when null. */
    fun focus(alias: String?) {
        val detail = _panel.value?.detail ?: return
        val focus = alias?.takeUnless { PassageText.fold(it) == PassageText.fold(detail.name) }
        detailJob?.cancel()
        _panel.update { panel ->
            panel?.copy(detail = panel.detail?.copy(focus = focus, pane = CharacterPane.MENTIONS, passages = emptyList(), more = false, failed = false, scanned = 0))
        }
        loadMore()
    }

    fun loadMore() = loadMore(quiet = false)

    private fun loadMore(quiet: Boolean) {
        val panel = _panel.value ?: return
        val detail = panel.detail ?: return
        val book = identity ?: return
        val ceiling = panel.ceiling ?: return
        var offset = detail.passages.size
        detailJob?.cancel()
        if (!quiet) _panel.update {
            val d = it?.detail
            if (it?.ceiling == ceiling && detail.lists(d)) {
                it.copy(detail = d!!.copy(loading = true, failed = false))
            } else it
        }
        detailJob = viewModelScope.launch {
            try {
                while (true) {
                    val scanned = _progress.value.completed
                    val page = store.mentions(book, ceiling, detail.term, detail.other, offset)
                    var applied = false
                    _panel.update { current ->
                        val d = current?.detail
                        applied = current != null && d != null && current.ceiling == ceiling &&
                            detail.lists(d) && d.passages.size == offset
                        if (current != null && d != null && applied) {
                            current.copy(detail = d.copy(passages = d.passages + page, loading = false, more = page.size == 50, scanned = scanned))
                        } else current
                    }
                    // A chapter committed during the read would otherwise wait for a refresh that may never come.
                    if (!applied || page.size == 50 || scanned >= ceiling || _progress.value.completed <= scanned) break
                    offset += page.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _panel.update { current ->
                    val d = current?.detail
                    if (current?.ceiling == ceiling && detail.lists(d)) {
                        current.copy(detail = d!!.copy(loading = false, failed = true))
                    } else current
                }
            }
        }
    }

    fun back() { detailJob?.cancel(); _panel.update { it?.copy(detail = null) } }
    fun hide() { _panel.update { it?.copy(visible = false) } }
    fun reveal() { _panel.update { it?.copy(visible = true) } }
    fun close() {
        opening?.cancel()
        detailJob?.cancel()
        graphJob?.cancel()
        _panel.value = null
    }

    companion object {
        internal fun <P> unitText(passages: List<Passage<P>>): String = buildString {
            passages.forEachIndexed { i, passage ->
                if (i > 0) append(if (CodexIndex.sameElement(passages[i - 1], passage)) " " else "\n\n")
                append(passage.text)
            }
        }

        fun factory(bookUrl: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                CodexViewModel(
                    bookUrl,
                    container.companionStore.codex,
                    container.appSettings,
                    container.database.bookDao(),
                    container.bookFingerprints,
                    profilesFlow = container.companionServer.choice.map { it != null },
                )
            }
        }
    }
}
