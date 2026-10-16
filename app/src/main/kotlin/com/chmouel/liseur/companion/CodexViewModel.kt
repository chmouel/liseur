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
data class CodexDetail(
    val name: String,
    val other: String? = null,
    val passages: List<Passage<Locator>> = emptyList(),
    val loading: Boolean = true,
    val more: Boolean = false,
    val failed: Boolean = false,
    /** Committed chapters when [passages] were last read; later ones can only add rows after them. */
    val scanned: Int = 0,
)
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
    /** The relationships tab is showing; kept here, with [search], across a peek. */
    val relationships: Boolean = false,
    val search: String = query,
)

/** The local codex belongs to the open reader, independently of any AI provider. */
class CodexViewModel(
    private val bookUrl: String,
    private val store: CodexStore,
    settings: AppSettingsRepository,
    private val books: BookDao,
    private val fingerprints: BookFingerprintStore,
    foregroundFlow: Flow<Boolean>? = null,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.settings.map { it.codexEnabled }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
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
            enabled.collect { if (!it) { pause(); close() } }
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

    fun open(place: Locator, translated: Boolean, selection: String? = null) {
        if (!enabled.value) return
        val opened = catalog ?: return
        start(null, List(opened.map.chapterCount) { opened.map.titleOf(it) }, selection) { opened.scopeOf(place, translated)?.lo }
    }

    fun openAt(ceiling: Int, titles: List<String?>, selection: String) {
        if (!enabled.value) return
        start(ceiling, titles, selection, place = null)
    }

    /** Shows a new panel; [place] resolves the ceiling when it is not already known. */
    private fun start(ceiling: Int?, titles: List<String?>, selection: String?, place: (suspend () -> Int?)?) {
        opening?.cancel()
        detailJob?.cancel()
        graphJob?.cancel()
        paused = false
        val name = selection?.let(PassageText::name)?.let(CodexExtractor::storedName)
        val query = name ?: selection?.trim().orEmpty()
        _panel.value = CodexPanel(
            ceiling = ceiling,
            titles = titles,
            placing = place != null,
            query = query,
            detail = name?.let { CodexDetail(it) },
        )
        opening = viewModelScope.launch {
            try {
                if (place != null) {
                    val placed = place()
                    _panel.update { it?.copy(ceiling = placed, placing = false) }
                    if (placed == null) return@launch
                }
                if (identity != null) {
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
                    val boundaries = PassageText.hash((0 until map.chapterCount).joinToString("|") { map.unitKey(it) })
                    val book = CodexBookRow(bookUrl, "$sha:${CodexStore.FORMAT}:${PassageText.VERSION}:$boundaries", map.chapterCount)
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
            if (current.relationships) rebuildGraph()
        } else if (current.relationships && !current.graphFailed && (committed || current.graph == null && graphJob?.isActive != true)) {
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
        _panel.update { it?.copy(relationships = true, graphFailed = false) }
        rebuildGraph()
    }

    fun showNames() = _panel.update { it?.copy(relationships = false) }

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
            } while (graphStale && _panel.value?.relationships == true)
        }
    }

    fun detail(name: String, other: String? = null) {
        detailJob?.cancel()
        _panel.update { it?.copy(detail = CodexDetail(name, other)) }
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
            if (it?.ceiling == ceiling && d?.name == detail.name && d.other == detail.other) {
                it.copy(detail = d.copy(loading = true, failed = false))
            } else it
        }
        detailJob = viewModelScope.launch {
            try {
                while (true) {
                    val scanned = _progress.value.completed
                    val page = store.mentions(book, ceiling, detail.name, detail.other, offset)
                    var applied = false
                    _panel.update { current ->
                        val d = current?.detail
                        applied = current != null && d != null && current.ceiling == ceiling &&
                            d.name == detail.name && d.other == detail.other && d.passages.size == offset
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
                    if (current?.ceiling == ceiling && d?.name == detail.name && d.other == detail.other) {
                        current.copy(detail = d.copy(loading = false, failed = true))
                    } else current
                }
            }
        }
    }

    fun back() { detailJob?.cancel(); _panel.update { it?.copy(detail = null) } }
    fun hide() { _panel.update { it?.copy(visible = false) } }
    fun reveal() { if (enabled.value) _panel.update { it?.copy(visible = true) } }
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
                CodexViewModel(bookUrl, container.companionStore.codex, container.appSettings, container.database.bookDao(), container.bookFingerprints)
            }
        }
    }
}
