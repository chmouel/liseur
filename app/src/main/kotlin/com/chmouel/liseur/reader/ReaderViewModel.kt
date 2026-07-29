package com.chmouel.liseur.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.domain.FINISHED_PROGRESSION
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.domain.EPSILON
import com.chmouel.liseur.domain.ReadingStatus
import com.chmouel.liseur.domain.exportNotebookMarkdown
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.reader.progress.BookPositions
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.ReadingSpeedEstimator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.streamer.PublicationOpener
import java.util.UUID

class ReaderViewModel(
    private val bookUrl: AbsoluteUrl,
    /**
     * The book's permanent identity in the library, which for a book from
     * calibre-web is not the file it currently lives in — so where you got
     * to survives the download being removed and fetched again.
     */
    private val bookId: String,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val progressDao: ReadingProgressDao,
    private val annotationDao: BookAnnotationDao,
    private val library: LocalLibraryRepository,
    private val prefsRepo: ReaderPreferencesRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState

        data class Ready(
            val publication: Publication,
            val navigatorFactory: EpubNavigatorFactory,
            val initialLocator: Locator?,
        ) : UiState

        data class Failure(val message: String) : UiState
    }
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<ReaderProgress?>(null)

    /** Where the reader is in the book, once positions are computed. */
    val progress: StateFlow<ReaderProgress?> = _progress.asStateFlow()

    private val _jumpBack = MutableStateFlow<JumpBack?>(null)

    /** Offer to return to where reading was before the last jump. */
    val jumpBack: StateFlow<JumpBack?> = _jumpBack.asStateFlow()

    /** Highlights, notes and bookmarks in this book, in reading order. */
    val annotations: StateFlow<List<BookAnnotation>> = annotationDao.observe(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Raised when the reader selects text in the page. The navigator knows
     * where the selection is, so the screen picks the details up from there
     * rather than the view model carrying a reference to it.
     */
    private val _selectionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val selectionRequests: SharedFlow<Unit> = _selectionRequests.asSharedFlow()

    private var publication: Publication? = null
    private var bookPositions: BookPositions? = null
    private var speed = ReadingSpeedEstimator()
    private var jumpBackTimer: Job? = null

    val prefs: StateFlow<ReaderPrefs> = prefsRepo.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    /** Most recent position, used to persist progress and to survive recreation. */
    var lastLocator: Locator? = null
        private set

    init {
        open()
    }

    private fun open() {
        viewModelScope.launch {
            // Make sure saved preferences are loaded before the navigator is
            // created, so the book opens directly with the user's settings.
            prefsRepo.prefs.first()
            val asset = assetRetriever.retrieve(bookUrl).getOrElse {
                _state.value = UiState.Failure(it.message)
                return@launch
            }
            val publication = publicationOpener.open(asset, allowUserInteraction = false)
                .getOrElse {
                    asset.close()
                    _state.value = UiState.Failure(it.message)
                    return@launch
                }
            val stored = progressDao.get(bookId)
                ?.also { speed = ReadingSpeedEstimator(it.readingSpeed) }
            var initialLocator = stored?.locatorJson
                ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }

            // A position that came from another device is only a
            // percentage, so it no longer matches the locator saved here.
            // Turning it into a real place means laying the book out
            // before showing it: a moment's wait, but only on the first
            // open after syncing.
            val wanted = stored?.totalProgression
            if (wanted != null && !initialLocator.isAt(wanted)) {
                val positions = BookPositions.of(publication)
                bookPositions = positions
                positions.locatorAt(positions.positionAtProgression(wanted.toFloat()))
                    ?.let { initialLocator = it }
            }
            lastLocator = initialLocator
            library.markOpened(bookId)
            this@ReaderViewModel.publication = publication
            _state.value = UiState.Ready(
                publication = publication,
                navigatorFactory = EpubNavigatorFactory(publication),
                initialLocator = initialLocator,
            )
            // Computing positions parses the whole book, so it runs
            // after the reader is on screen.
            if (bookPositions == null) bookPositions = BookPositions.of(publication)
            lastLocator?.let { _progress.value = progressAt(it) }
        }
    }

    /** True when this locator already sits at [progression] within a page. */
    private fun Locator?.isAt(progression: Double): Boolean {
        val here = this?.locations?.totalProgression ?: return false
        return kotlin.math.abs(here - progression) < EPSILON
    }

    fun onLocatorChanged(locator: Locator) {
        lastLocator = locator
        val positions = bookPositions
        val totalProgression = locator.locations.totalProgression
        if (positions != null && positions.isUsable && totalProgression != null) {
            speed.record(
                position = totalProgression * positions.totalPositions,
                atMillis = System.currentTimeMillis(),
            )
        }
        _progress.value = progressAt(locator)
        viewModelScope.launch {
            progressDao.upsert(
                ReadingProgress(
                    bookUrl = bookId,
                    locatorJson = locator.toJSON().toString(),
                    totalProgression = locator.locations.totalProgression,
                    readingSpeed = speed.speed,
                    updatedAt = System.currentTimeMillis(),
                    status = ReadingStatus.forProgression(
                        locator.locations.totalProgression,
                    ).wireName,
                    syncedAt = progressDao.get(bookId)?.syncedAt,
                ),
            )
            // Reaching the end marks the book read, so the library shows it
            // as done and the app stops dropping you back into it. Marking
            // it unread by hand sticks: we only ever set this on the way in.
            if ((totalProgression ?: 0.0) >= FINISHED_PROGRESSION) {
                library.markFinishedOnce(bookId)
            }
        }
    }

    /** Called before jumping, so the reader can come back in one tap. */
    fun onJump() {
        val from = lastLocator ?: return
        val progress = _progress.value
        // The jump itself is not reading, and neither is finding your
        // way back, so it must not affect the speed estimate.
        speed.forgetLastPosition()
        _jumpBack.value = JumpBack(locator = from, position = progress?.position)
        jumpBackTimer?.cancel()
        jumpBackTimer = viewModelScope.launch {
            delay(JUMP_BACK_TIMEOUT_MS)
            _jumpBack.value = null
        }
    }

    fun dismissJumpBack() {
        jumpBackTimer?.cancel()
        _jumpBack.value = null
    }

    /** The locator for a position on the scrubber, numbered from 1. */
    fun locatorAtPosition(position: Int): Locator? = bookPositions?.locatorAt(position)

    /** The position matching a whole-book progression between 0 and 1. */
    fun positionAtProgression(progression: Float): Int =
        bookPositions?.positionAtProgression(progression) ?: 1

    /** The chapter title shown while dragging the scrubber. */
    fun chapterTitleAtPosition(position: Int): String? =
        bookPositions?.chapterAt(position)?.title

    /** Chapter starts as whole-book progressions, for the scrubber ticks. */
    fun chapterTicks(): List<Float> {
        val positions = bookPositions?.takeIf { it.isUsable } ?: return emptyList()
        val total = positions.totalPositions.toFloat()
        return positions.chapters
            .map { (it.firstPosition - 1) / total }
            .filter { it > 0f }
    }

    private fun progressAt(locator: Locator): ReaderProgress? {
        val positions = bookPositions?.takeIf { it.isUsable } ?: return null
        val publication = publication ?: return null
        val totalProgression = (locator.locations.totalProgression ?: 0.0).toFloat()
        val position = locator.locations.position
            ?: positions.positionAtProgression(totalProgression)
        val chapter = publication.readingOrder.indexOfFirstWithHref(locator.href)
            ?.let(positions::chapterOfResource)
            ?: positions.chapterAt(position)
        val chapterEnd = chapter?.lastPosition ?: positions.totalPositions
        return ReaderProgress(
            position = position.coerceIn(1, positions.totalPositions),
            totalPositions = positions.totalPositions,
            totalProgression = totalProgression,
            chapterTitle = chapter?.title,
            minutesLeftInChapter = speed.minutesFor((chapterEnd - position).toDouble()),
            minutesLeftInBook = speed.minutesFor((positions.totalPositions - position).toDouble()),
            isSpeedMeasured = speed.isMeasured,
        )
    }

    /** How an in-book search is going. */
    sealed interface SearchState {
        data object Idle : SearchState

        data class Running(val query: String, val hits: List<Locator>) : SearchState

        data class Done(
            val query: String,
            val hits: List<Locator>,
            val truncated: Boolean = false,
        ) : SearchState

        data class Failure(val message: String) : SearchState
    }

    private val _search = MutableStateFlow<SearchState>(SearchState.Idle)

    /** Results of searching inside the book. */
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Searches the whole book for [query].
     *
     * Results arrive resource by resource, so they are published as they
     * come in rather than after the last chapter has been read: on a long
     * book the first hits are usable well before the search finishes.
     */
    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _search.value = SearchState.Idle
            return
        }
        val publication = publication ?: return
        searchJob = viewModelScope.launch {
            _search.value = SearchState.Running(trimmed, emptyList())
            // A publication without a search service cannot be searched;
            // the reader is told rather than left with an empty result.
            val iterator = publication.search(trimmed) ?: run {
                _search.value = SearchState.Failure("")
                return@launch
            }
            val hits = mutableListOf<Locator>()
            try {
                while (true) {
                    val page = iterator.next().getOrElse {
                        _search.value = SearchState.Failure(it.message)
                        return@launch
                    } ?: break
                    hits += page.locators
                    if (hits.size >= MAX_SEARCH_HITS) {
                        _search.value = SearchState.Done(
                            query = trimmed,
                            hits = hits.take(MAX_SEARCH_HITS),
                            truncated = true,
                        )
                        return@launch
                    }
                    _search.value = SearchState.Running(trimmed, hits.toList())
                }
                _search.value = SearchState.Done(trimmed, hits.toList())
            } finally {
                iterator.close()
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _search.value = SearchState.Idle
    }

    /** Called by the navigator when the reader has selected some text. */
    fun onTextSelected() {
        _selectionRequests.tryEmit(Unit)
    }

    /** Marks a passage, or recolours a mark that is already there. */
    fun highlight(locator: Locator, tint: HighlightTint, existingId: String? = null) {
        val existing = existingId?.let { id -> annotations.value.firstOrNull { it.id == id } }
        save(
            annotation(locator, AnnotationKind.HIGHLIGHT).copy(
                id = existing?.id ?: UUID.randomUUID().toString(),
                note = existing?.note,
                tint = tint.name,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                kind = existing?.kind ?: AnnotationKind.HIGHLIGHT.name,
            ),
        )
    }

    /** Attaches the reader's own words to a passage. */
    fun addNote(locator: Locator, note: String, existingId: String? = null) {
        val existing = existingId?.let { id -> annotations.value.firstOrNull { it.id == id } }
        save(
            annotation(locator, AnnotationKind.NOTE).copy(
                id = existing?.id ?: UUID.randomUUID().toString(),
                note = note,
                tint = existing?.tint ?: HighlightTint.DEFAULT.name,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ),
        )
    }

    /** The mark covering this locator, if the reader tapped an existing one. */
    fun annotationAt(locator: Locator): BookAnnotation? {
        val progression = locator.locations.totalProgression ?: return null
        return annotations.value
            .filter { it.kind != AnnotationKind.BOOKMARK.name }
            .firstOrNull {
                val other = it.totalProgression ?: return@firstOrNull false
                kotlin.math.abs(other - progression) < EPSILON
            }
    }

    /** Puts a bookmark on the current page, or takes it off again. */
    fun toggleBookmark() {
        val locator = lastLocator ?: return
        val existing = bookmarkForCurrentPage()
        if (existing != null) {
            viewModelScope.launch { annotationDao.delete(existing) }
        } else {
            save(annotation(locator, AnnotationKind.BOOKMARK))
        }
    }

    /**
     * True when the page on screen is already bookmarked. It has to watch
     * the position as well as the marks, or the ribbon would stay out for
     * the rest of the book once a single page was bookmarked.
     */
    val bookmarked: StateFlow<Boolean> = combine(annotations, _progress) { list, _ ->
        list.any { it.kind == AnnotationKind.BOOKMARK.name && it.isHere() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun bookmarkForCurrentPage(): BookAnnotation? =
        annotations.value.firstOrNull {
            it.kind == AnnotationKind.BOOKMARK.name && it.isHere()
        }

    private fun BookAnnotation.isHere(): Boolean {
        val page = _progress.value?.position
        if (page != null && position != null) return position == page
        val here = lastLocator?.locations?.totalProgression ?: return false
        val there = totalProgression ?: return false
        return kotlin.math.abs(here - there) < EPSILON
    }

    fun remove(annotation: BookAnnotation) {
        viewModelScope.launch { annotationDao.delete(annotation) }
    }

    /** The notebook as Markdown, ready to be shared. */
    fun notebookMarkdown(): String =
        exportNotebookMarkdown(
            title = publication?.metadata?.title.orEmpty(),
            author = publication?.metadata?.authors?.firstOrNull()?.name,
            annotations = annotations.value,
        )

    private fun save(annotation: BookAnnotation) {
        viewModelScope.launch { annotationDao.upsert(annotation) }
    }

    private fun annotation(locator: Locator, kind: AnnotationKind): BookAnnotation {
        val progression = locator.locations.totalProgression
        val position = locator.locations.position
            ?: progression?.let { bookPositions?.positionAtProgression(it.toFloat()) }
        return BookAnnotation(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            kind = kind.name,
            locatorJson = locator.toJSON().toString(),
            text = locator.text.highlight?.takeIf { it.isNotBlank() },
            tint = if (kind == AnnotationKind.BOOKMARK) null else HighlightTint.DEFAULT.name,
            chapter = position?.let { chapterTitleAtPosition(it) } ?: locator.title,
            position = position,
            totalProgression = progression,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun setFont(font: ReaderFont) = viewModelScope.launch { prefsRepo.setFont(font) }

    fun setFontSize(size: Double) = viewModelScope.launch { prefsRepo.setFontSize(size) }

    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { prefsRepo.setTheme(theme) }

    fun setLineHeight(value: Double?) = viewModelScope.launch { prefsRepo.setLineHeight(value) }

    fun setPageMargins(value: Double?) = viewModelScope.launch { prefsRepo.setPageMargins(value) }

    fun setBrightness(value: Float?) = viewModelScope.launch { prefsRepo.setBrightness(value) }

    fun setPageTurnAnimation(enabled: Boolean) =
        viewModelScope.launch { prefsRepo.setPageTurnAnimation(enabled) }

    fun cycleFooterMode() = viewModelScope.launch {
        prefsRepo.setFooterMode(prefs.value.footerMode.next())
    }

    fun setFooterMode(mode: FooterMode) = viewModelScope.launch {
        prefsRepo.setFooterMode(mode)
    }

    override fun onCleared() {
        (_state.value as? UiState.Ready)?.publication?.close()
    }

    /** A place to return to after a jump, and the page it was on. */
    data class JumpBack(val locator: Locator, val position: Int?)

    companion object {
        private const val JUMP_BACK_TIMEOUT_MS = 30_000L

        /**
         * Enough hits that no real search runs out, few enough that a
         * one-letter query on a long book does not fill memory.
         */
        private const val MAX_SEARCH_HITS = 500

        fun factory(bookUrl: AbsoluteUrl, bookId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).container
                ReaderViewModel(
                    bookUrl = bookUrl,
                    bookId = bookId,
                    assetRetriever = container.assetRetriever,
                    publicationOpener = container.publicationOpener,
                    progressDao = container.database.readingProgressDao(),
                    annotationDao = container.database.annotationDao(),
                    library = container.libraryRepository,
                    prefsRepo = container.readerPreferences,
                )
            }
        }
    }
}
