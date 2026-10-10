package com.chmouel.liseur.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.RemoteServer
import com.chmouel.liseur.data.opds.GutenbergBrowse
import com.chmouel.liseur.data.opds.StarterCatalog
import com.chmouel.liseur.data.remote.BrowseCatalogRepository
import com.chmouel.liseur.data.remote.BrowseCategory
import com.chmouel.liseur.data.remote.BrowseListing
import com.chmouel.liseur.data.remote.BrowseOwned
import com.chmouel.liseur.data.remote.RemoteBook
import com.chmouel.liseur.data.remote.ServerKind
import com.chmouel.liseur.data.remote.SetupFailure
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseLibrariesState(
    val saved: List<RemoteServer> = emptyList(),
    val savedLoaded: Boolean = false,
    val serverId: Long? = null,
    val path: List<BrowseCategory> = emptyList(),
    val listing: BrowseListing = BrowseListing(),
    val owned: Map<String, BrowseOwned> = emptyMap(),
    val selecting: Boolean = false,
    val selected: Map<String, RemoteBook> = emptyMap(),
    val detail: RemoteBook? = null,
    val pending: List<RemoteBook>? = null,
    val formOpen: Boolean = false,
    val connecting: Boolean = false,
    val connectionFailure: SetupFailure? = null,
    /** A listing fetch is running; the previous listing stays on screen for a refresh. */
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    /** The next page of an OPDS section is being read. */
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    /** An add, category snapshot or removal is running. */
    val working: Boolean = false,
) {
    val server: RemoteServer? get() = saved.firstOrNull { it.id == serverId }

    fun canAdd(book: RemoteBook): Boolean = canAddBrowseBook(book, owned, server?.canDownload == true)

    val addable: List<RemoteBook> get() = listing.books.filter(::canAdd)

    val gutenbergSaved: Boolean get() = saved.any(GutenbergBrowse::isRoot)

    /** Back has a step to undo inside the browser before it leaves it. */
    val canGoBack: Boolean
        get() = pending != null || detail != null || selecting || formOpen ||
            path.isNotEmpty() || serverId != null
}

/** One-off outcomes, told once through a snackbar. */
sealed interface BrowseEvent {
    data class Added(val count: Int) : BrowseEvent
    data object AddFailed : BrowseEvent
    data object RemoveFailed : BrowseEvent
    data object CategoryIncomplete : BrowseEvent
    data object NothingToAdd : BrowseEvent
    data object ConnectFailed : BrowseEvent
}

internal fun canAddBrowseBook(book: RemoteBook, owned: Map<String, BrowseOwned>, canDownload: Boolean): Boolean =
    canDownload && book.downloadHref != null && book.remoteId !in owned

/** The size of [books] when every one of them says, otherwise null. */
internal fun browseKnownSize(books: Collection<RemoteBook>): Long? =
    books.takeIf { list -> list.isNotEmpty() && list.all { it.sizeBytes != null } }?.sumOf { it.sizeBytes ?: 0L }

/**
 * A short name for a saved catalog: its host, plus its path and query
 * when it has them. A query can pick which catalog an OPDS root serves,
 * so two saved at one path differ only there.
 */
internal fun browseCatalogName(baseUrl: String): String {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
    val host = uri?.host?.removePrefix("www.")
        ?: return baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val query = uri.rawQuery?.let(::shownQuery)?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    return host + port + path + query
}

private val CREDENTIAL_PARAM = Regex("key|token|pass|secret|auth|sig|session", RegexOption.IGNORE_CASE)

/** The query as shown on screen, with the value of anything that looks like a credential hidden. */
private fun shownQuery(raw: String): String = raw.split('&').filter { it.isNotEmpty() }.joinToString("&") { part ->
    val name = part.substringBefore('=')
    val decoded = runCatching { URLDecoder.decode(name, Charsets.UTF_8.name()) }.getOrDefault(name)
    if ('=' in part && CREDENTIAL_PARAM.containsMatchIn(decoded)) "$name=…" else part
}

internal fun BrowseListing.append(page: BrowseListing): BrowseListing = BrowseListing(
    categories = categories + page.categories,
    books = books + page.books,
    complete = complete && page.complete,
    nextPage = page.nextPage,
).unique()

/** The grid keys every entry by its id; a repeated one would crash it. */
internal fun BrowseListing.unique(): BrowseListing = copy(
    categories = categories.distinctBy { it.id },
    books = books.distinctBy { it.remoteId },
)

class BrowseLibrariesViewModel(private val catalogs: BrowseCatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow(BrowseLibrariesState())
    val state = _state.asStateFlow()

    private val _events = Channel<BrowseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var listingGeneration = 0L
    private val pagesAsked = mutableSetOf<String>()
    private var ownedJob: Job? = null
    private var categoryJob: Job? = null
    private var categoryGeneration = 0L
    private var connectJob: Job? = null
    private var connectionGeneration = 0L

    private fun abandonConnection() {
        connectionGeneration++
        connectJob?.cancel()
        connectJob = null
        _state.update { it.copy(connecting = false) }
    }

    private fun abandonCategory() {
        categoryGeneration++
        if (categoryJob?.isActive == true) {
            categoryJob?.cancel()
            _state.update { it.copy(working = false) }
        }
        categoryJob = null
    }

    init {
        viewModelScope.launch {
            catalogs.saved.collect { saved -> _state.update { it.copy(saved = saved, savedLoaded = true) } }
        }
    }

    fun openForm() = _state.update { it.copy(formOpen = true, connectionFailure = null) }

    fun closeForm() {
        abandonConnection()
        _state.update { it.copy(formOpen = false, connectionFailure = null) }
    }

    fun connect(
        kind: ServerKind,
        address: String,
        username: String,
        password: String,
        apiKey: String,
        deviceToken: String,
        allowHttp: Boolean,
    ) {
        if (_state.value.connecting) return
        val generation = connectionGeneration
        _state.update { it.copy(connecting = true, connectionFailure = null) }
        connectJob = viewModelScope.launch {
            try {
                val result = catalogs.connect(kind, address, username, password, apiKey, deviceToken, allowHttp)
                if (generation != connectionGeneration) return@launch
                if (result.id != null) {
                    _state.update { it.copy(formOpen = false, connecting = false) }
                    openServer(result.id)
                } else {
                    _state.update { it.copy(connecting = false, connectionFailure = result.failure) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != connectionGeneration) return@launch
                Log.e(TAG, "Could not save catalog", error)
                _state.update {
                    it.copy(
                        connecting = false,
                        connectionFailure = SetupFailure.Unreachable(error.message.orEmpty(), httpMayWork = false),
                    )
                }
            }
        }
    }

    /** Saves Project Gutenberg as an anonymous OPDS catalog and opens it. */
    fun connectGutenberg() {
        if (_state.value.connecting) return
        _state.value.saved.firstOrNull(GutenbergBrowse::isRoot)?.let { existing ->
            _state.update { it.copy(formOpen = false, connectionFailure = null) }
            openServer(existing.id)
            return
        }
        val generation = connectionGeneration
        _state.update { it.copy(connecting = true) }
        connectJob = viewModelScope.launch {
            val id = try {
                catalogs.connect(ServerKind.CUSTOM, StarterCatalog.BROWSE_URL, "", "", "", "", false).id
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not save Project Gutenberg", error)
                null
            }
            if (generation != connectionGeneration) return@launch
            _state.update {
                if (id != null) it.copy(connecting = false, formOpen = false, connectionFailure = null)
                else it.copy(connecting = false)
            }
            if (id != null) openServer(id) else _events.send(BrowseEvent.ConnectFailed)
        }
    }

    fun openServer(id: Long) {
        abandonCategory()
        _state.update {
            it.copy(
                serverId = id,
                path = emptyList(),
                listing = BrowseListing(),
                owned = emptyMap(),
                selecting = false,
                selected = emptyMap(),
                detail = null,
                pending = null,
            )
        }
        ownedJob?.cancel()
        ownedJob = viewModelScope.launch {
            catalogs.observeOwned(id).collect { owned ->
                _state.update { current ->
                    if (current.serverId != id) return@update current
                    current.copy(
                        owned = owned,
                        selected = current.selected.filterKeys { it !in owned },
                    )
                }
            }
        }
        load(keep = false, fresh = true)
    }

    fun openCategory(category: BrowseCategory) {
        abandonCategory()
        _state.update { it.copy(path = it.path + category, selecting = false, selected = emptyMap(), pending = null) }
        load(keep = false)
    }

    /** Jumps back to [depth] categories deep; 0 is the catalog's top. */
    fun openPath(depth: Int) {
        if (depth >= _state.value.path.size) return
        abandonCategory()
        _state.update { it.copy(path = it.path.take(depth), selecting = false, selected = emptyMap(), pending = null) }
        load(keep = false)
    }

    /** Leaving the browser: a catalog still being connected to is dropped. */
    fun leave() = abandonConnection()

    fun back(): Boolean {
        val current = _state.value
        when {
            current.pending != null -> _state.update { it.copy(pending = null) }
            current.detail != null -> _state.update { it.copy(detail = null) }
            current.selecting -> clearSelection()
            current.formOpen -> closeForm()
            current.path.isNotEmpty() -> openPath(current.path.size - 1)
            current.serverId != null -> closeServer()
            else -> {
                abandonConnection()
                return false
            }
        }
        return true
    }

    private fun closeServer() {
        abandonCategory()
        listingGeneration++
        loadJob?.cancel()
        moreJob?.cancel()
        ownedJob?.cancel()
        _state.update {
            it.copy(
                serverId = null,
                path = emptyList(),
                listing = BrowseListing(),
                owned = emptyMap(),
                selecting = false,
                selected = emptyMap(),
                pending = null,
                loading = false,
                loadFailed = false,
                loadingMore = false,
                moreFailed = false,
            )
        }
    }

    fun reload() = load(keep = true, fresh = true)

    private fun load(keep: Boolean, fresh: Boolean = false) {
        val serverId = _state.value.serverId ?: return
        val categoryId = _state.value.path.lastOrNull()?.id
        val generation = ++listingGeneration
        loadJob?.cancel()
        moreJob?.cancel()
        pagesAsked.clear()
        _state.update {
            it.copy(
                loading = true,
                loadFailed = false,
                loadingMore = false,
                moreFailed = false,
                listing = if (keep) it.listing else BrowseListing(),
            )
        }
        loadJob = viewModelScope.launch {
            val listing = try {
                catalogs.listing(serverId, categoryId, fresh = fresh)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not load catalog listing", error)
                null
            }
            _state.update { current ->
                if (generation != listingGeneration || current.serverId != serverId ||
                    current.path.lastOrNull()?.id != categoryId
                ) {
                    current
                } else if (listing == null) {
                    current.copy(loading = false, loadFailed = true)
                } else {
                    current.copy(loading = false, listing = listing.unique())
                }
            }
        }
    }

    /** Reads the next page of the open section, once per page. */
    fun loadMore() {
        val current = _state.value
        val serverId = current.serverId ?: return
        val next = current.listing.nextPage ?: return
        if (current.loading || current.loadingMore || !pagesAsked.add(next)) return
        val categoryId = current.path.lastOrNull()?.id
        val generation = listingGeneration
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        moreJob = viewModelScope.launch {
            val page = try {
                catalogs.listing(serverId, categoryId, next)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not load the next catalog page", error)
                null
            }
            if (page == null && generation == listingGeneration) pagesAsked.remove(next)
            _state.update { current ->
                if (generation != listingGeneration || current.serverId != serverId ||
                    current.path.lastOrNull()?.id != categoryId ||
                    current.listing.nextPage != next
                ) {
                    current
                } else if (page == null) {
                    current.copy(loadingMore = false, moreFailed = true)
                } else {
                    current.copy(loadingMore = false, listing = current.listing.append(page))
                }
            }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreFailed = false) }
        loadMore()
    }

    fun showDetail(book: RemoteBook) = _state.update { it.copy(detail = book) }

    fun dismissDetail() = _state.update { it.copy(detail = null) }

    fun startSelection(book: RemoteBook) = _state.update { current ->
        if (!current.canAdd(book)) return@update current
        current.copy(selecting = true, detail = null, selected = current.selected + (book.remoteId to book))
    }

    fun toggle(book: RemoteBook) = _state.update { current ->
        if (!current.canAdd(book)) return@update current
        val selected = if (book.remoteId in current.selected) {
            current.selected - book.remoteId
        } else {
            current.selected + (book.remoteId to book)
        }
        current.copy(selected = selected, selecting = selected.isNotEmpty())
    }

    /** Selects every book that can be added here, or clears the selection when all already are. */
    fun selectAll() = _state.update { current ->
        val addable = current.addable
        if (addable.isNotEmpty() && addable.all { it.remoteId in current.selected }) {
            current.copy(selected = emptyMap(), selecting = false)
        } else {
            current.copy(selected = addable.associateBy { it.remoteId }, selecting = addable.isNotEmpty())
        }
    }

    fun clearSelection() = _state.update { it.copy(selecting = false, selected = emptyMap()) }

    fun prepareSelected() {
        val current = _state.value
        val pending = current.selected.values.filter(current::canAdd)
        if (pending.isNotEmpty()) _state.update { it.copy(pending = pending) }
    }

    /** One book from the detail sheet: the sheet already showed what it costs, so no second question. */
    fun addNow(book: RemoteBook) {
        if (!_state.value.canAdd(book)) return
        _state.update { it.copy(detail = null) }
        add(listOf(book))
    }

    fun prepareCategory() {
        val current = _state.value
        val serverId = current.serverId ?: return
        val categoryId = current.path.lastOrNull()?.id ?: return
        if (current.server?.let(GutenbergBrowse::isRoot) == true &&
            GutenbergBrowse.isVirtual(categoryId) && !GutenbergBrowse.isShelf(categoryId)
        ) return
        if (current.working) return
        val generation = categoryGeneration
        _state.update { it.copy(working = true) }
        categoryJob = viewModelScope.launch {
            try {
                val snapshot = catalogs.categoryBooks(serverId, categoryId)
                if (generation != categoryGeneration || _state.value.serverId != serverId ||
                    _state.value.path.lastOrNull()?.id != categoryId
                ) return@launch
                val owned = _state.value.owned
                val canDownload = _state.value.server?.canDownload == true
                val pending = snapshot.books.filter { canAddBrowseBook(it, owned, canDownload) }
                _state.update {
                    it.copy(working = false, pending = pending.takeIf { books -> snapshot.complete && books.isNotEmpty() })
                }
                when {
                    !snapshot.complete -> _events.send(BrowseEvent.CategoryIncomplete)
                    pending.isEmpty() -> _events.send(BrowseEvent.NothingToAdd)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != categoryGeneration) return@launch
                Log.w(TAG, "Could not read category", error)
                _state.update { it.copy(working = false) }
                _events.send(BrowseEvent.CategoryIncomplete)
            }
        }
    }

    fun dismissPending() = _state.update { it.copy(pending = null) }

    fun confirmAdd() {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        add(pending)
    }

    private fun add(books: List<RemoteBook>) {
        val serverId = _state.value.serverId ?: return
        val path = _state.value.path
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                val count = catalogs.add(serverId, books)
                // The reader may have moved on while the add ran; a
                // selection made somewhere else is not this add's to clear.
                _state.update {
                    if (it.serverId == serverId && it.path == path) {
                        it.copy(working = false, selecting = false, selected = emptyMap())
                    } else {
                        it.copy(working = false)
                    }
                }
                _events.send(if (count > 0) BrowseEvent.Added(count) else BrowseEvent.NothingToAdd)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not add books", error)
                _state.update { it.copy(working = false) }
                _events.send(BrowseEvent.AddFailed)
            }
        }
    }

    fun remove(id: Long) {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                catalogs.remove(id)
                if (_state.value.serverId == id) closeServer()
                _state.update { it.copy(working = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not remove saved catalog", error)
                _state.update { it.copy(working = false) }
                _events.send(BrowseEvent.RemoveFailed)
            }
        }
    }

    companion object {
        private const val TAG = "BrowseLibraries"

        val Factory = viewModelFactory {
            initializer {
                BrowseLibrariesViewModel(checkNotNull(this[APPLICATION_KEY]).container.browseCatalog)
            }
        }
    }
}
