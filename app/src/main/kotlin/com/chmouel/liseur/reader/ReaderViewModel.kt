package com.chmouel.liseur.reader

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.calibre.BookDownloadRepository
import com.chmouel.liseur.data.remote.PreviewOutcome
import com.chmouel.liseur.data.remote.RemoteAccountRepository
import com.chmouel.liseur.data.remote.ResolveOutcome
import com.chmouel.liseur.data.remote.SeriesExtrasRepository
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.db.BookAnnotationDao
import com.chmouel.liseur.data.db.BookDao
import com.chmouel.liseur.data.db.BookScreen
import com.chmouel.liseur.data.db.BookScreenDao
import com.chmouel.liseur.data.db.BookReadingMode
import com.chmouel.liseur.data.db.BookReadingModeDao
import com.chmouel.liseur.data.db.scrollsWith
import com.chmouel.liseur.data.db.keepsScreenOnWith
import com.chmouel.liseur.data.db.BookTypography
import com.chmouel.liseur.data.db.BookTypographyDao
import com.chmouel.liseur.data.db.withTypographyOf
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.ReadingProgressDao
import com.chmouel.liseur.data.library.LocalLibraryRepository
import com.chmouel.liseur.data.library.ReadingSessionManager
import com.chmouel.liseur.data.settings.AppSettingsRepository
import com.chmouel.liseur.data.settings.DefinitionTarget
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPreferencesRepository
import com.chmouel.liseur.data.settings.ReadingPaceRepository
import com.chmouel.liseur.sync.PositionSyncCoordinator
import com.chmouel.liseur.sync.SyncScope
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.domain.EPSILON
import com.chmouel.liseur.domain.SeriesExtras
import com.chmouel.liseur.domain.seriesIdForExtras
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.isSamePassage
import com.chmouel.liseur.domain.exportNotebookMarkdown
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.reader.annotations.locator
import com.chmouel.liseur.reader.annotations.markedPassage
import com.chmouel.liseur.ui.messageRes
import com.chmouel.liseur.reader.progress.BookPositions
import com.chmouel.liseur.reader.footnotes.FootnoteResolver
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.ReadingPace
import com.chmouel.liseur.reader.progress.ReadingSpeedEstimator
import com.chmouel.liseur.reader.progress.StableBookProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.use
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
    private val bookDao: BookDao,
    private val annotationDao: BookAnnotationDao,
    private val typographyDao: BookTypographyDao,
    private val bookScreenDao: BookScreenDao,
    private val readingModeDao: BookReadingModeDao,
    private val library: LocalLibraryRepository,
    private val prefsRepo: ReaderPreferencesRepository,
    private val readingPace: ReadingPaceRepository,
    private val positionSync: PositionSyncCoordinator,
    private val appSettings: AppSettingsRepository,
    private val positionPublisher: ReadingPositionPublisher,
    private val downloads: BookDownloadRepository,
    private val seriesExtras: SeriesExtrasRepository,
    private val remoteAccount: RemoteAccountRepository,
    sessionManager: ReadingSessionManager,
) : ViewModel() {

    private val sessions = sessionManager.recorder(bookId)
    private val timeSpent = sessionManager.observeTotal(bookId)

    /**
     * What the Define action needs before it opens a card or another app.
     *
     * @param target Where the Define action sends selected text.
     * @param enabled Whether the reader has agreed to online lookups.
     * @param baseUrl The dictionary site they chose.
     */
    data class DictionarySettings(
        val target: DefinitionTarget = DefinitionTarget.Default,
        val enabled: Boolean = false,
        val baseUrl: String = DictionaryUrl.DEFAULT_BASE_URL,
    )

    /** The dictionary's opt-in state, watched so the card reacts to it. */
    val dictionary: StateFlow<DictionarySettings> = appSettings.settings
        .map {
            DictionarySettings(
                it.definitionTarget,
                it.dictionaryLookupEnabled,
                it.dictionaryBaseUrl,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DictionarySettings())

    /** Turns online definitions on, from the card that asked for them. */
    fun enableDictionary() {
        viewModelScope.launch { appSettings.setDictionaryLookupEnabled(true) }
    }

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

    /**
     * Raised when the last page has been turned: the next volume, if
     * there is one, and how the series itself reads if there is not.
     *
     * Dismissing the next volume is remembered for as long as this book
     * is open. Turning back off the endpaper withdraws the offer without
     * undoing that the book is finished.
     */
    private val dismissedNextUp = MutableStateFlow(false)
    private val endpaperReached = MutableStateFlow(false)
    private val continueAfterDownload = MutableStateFlow(false)
    private val _seriesExtras = MutableStateFlow<SeriesExtras?>(null)
    private val _pendingOpen = MutableStateFlow<NextUp?>(null)

    /**
     * The next volume to open once it is ready, kept until a resumed
     * reader takes it. A one-shot event would vanish during rotation
     * and fire while the activity is stopped.
     */
    val pendingOpen: StateFlow<NextUp?> = _pendingOpen.asStateFlow()

    private val canDownload: StateFlow<Boolean> = remoteAccount.server
        .map { it?.canDownload == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val continuation: StateFlow<EndpaperContinuation?> = combine(
        combine(
            bookDao.observeAll(),
            progressDao.observeProgressions(),
            dismissedNextUp,
            endpaperReached,
            downloads.progress,
        ) { books, progressions, dismissed, endpaper, running ->
            ContinuationInputs(
                books = books,
                progressions = progressions
                    .mapNotNull { row -> row.totalProgression?.let { row.bookUrl to it } }
                    .toMap(),
                dismissed = dismissed,
                endpaperReached = endpaper,
                downloads = running.mapValues { (_, progress) ->
                    DownloadSnapshot(
                        queued = progress.queued,
                        fraction = progress.fraction,
                        running = !progress.queued,
                    )
                },
            )
        },
        canDownload,
        _seriesExtras,
        timeSpent,
    ) { inputs, allowed, extras, totalMs ->
        val current = inputs.books.firstOrNull { it.url == bookId } ?: return@combine null
        endpaperContinuation(
            current = current,
            library = inputs.books,
            progressions = inputs.progressions,
            dismissed = inputs.dismissed,
            endpaperReached = inputs.endpaperReached,
            downloads = inputs.downloads,
            canDownload = allowed,
            extras = extras,
            timeSpentMs = totalMs,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The last page was turned past. The book is finished from here. */
    fun onReachedEndpaper() {
        endpaperReached.value = true
        sessions.checkpoint()
        positionPublisher.completeBook(bookId)
    }

    /**
     * The reader turned back, went to the library, or otherwise left
     * the endpaper. A download already started keeps running; it just
     * will not open itself.
     */
    fun onLeftEndpaper() {
        endpaperReached.value = false
        continueAfterDownload.value = false
        _pendingOpen.value = null
    }

    /** The offer was declined. Either way it is done with. */
    fun dismissNextUp() {
        dismissedNextUp.value = true
        continueAfterDownload.value = false
        _pendingOpen.value = null
    }

    /**
     * The endpaper action was taken: open a file that is here, or fetch
     * one that is not. A download that cannot succeed is not started.
     */
    fun onContinueNext() {
        val next = continuation.value?.next ?: return
        when (next.availability) {
            is NextVolumeAvailability.Ready,
            NextVolumeAvailability.Queued,
            is NextVolumeAvailability.Downloading,
            -> {
                continueAfterDownload.value = true
            }
            NextVolumeAvailability.Remote, NextVolumeAvailability.Failed -> {
                if (!canDownload.value) return
                continueAfterDownload.value = true
                viewModelScope.launch {
                    val book = bookDao.getByUrl(next.id) ?: return@launch
                    downloads.enqueue(book)
                }
            }
            NextVolumeAvailability.Unavailable -> Unit
        }
    }

    /** The resumed reader opened the pending volume, so it is spent. */
    fun consumeOpenNext() {
        continueAfterDownload.value = false
        _pendingOpen.value = null
    }

    private data class ContinuationInputs(
        val books: List<Book>,
        val progressions: Map<String, Double>,
        val dismissed: Boolean,
        val endpaperReached: Boolean,
        val downloads: Map<String, DownloadSnapshot>,
    )

    /** An offer to continue where another device has read further. */
    data class CatchUp(val progression: Double, val position: Int?)

    private val _catchUp = MutableStateFlow<CatchUp?>(null)

    /** Raised when another device is further along than this page. */
    val catchUp: StateFlow<CatchUp?> = _catchUp.asStateFlow()

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

    /**
     * Where syncing this one book has got to, when someone has asked
     * about it from the Navigate screen.
     */
    sealed interface BookSync {
        data object Idle : BookSync
        data object Asking : BookSync

        /** Nothing left to do: a message, and then out of the way. */
        data class Note(val messageRes: Int) : BookSync
    }

    private val _bookSync = MutableStateFlow<BookSync>(BookSync.Idle)
    val bookSync: StateFlow<BookSync> = _bookSync.asStateFlow()

    /** Raised when a choice moves the reader somewhere else in the book. */
    private val _goTo = MutableSharedFlow<Locator>(extraBufferCapacity = 1)
    val goTo: SharedFlow<Locator> = _goTo.asSharedFlow()

    /**
     * A note to show over the page, and where it was referenced from.
     *
     * [link] is kept so the card can offer the way it used to be the only
     * way: opening the note where it actually lives, for the long ones and
     * the ones carrying a picture the card cannot draw.
     */
    data class Footnote(val html: String, val link: Link)

    private val _footnote = MutableStateFlow<Footnote?>(null)

    /** The note popped up over the page, if the reader tapped one. */
    val footnote: StateFlow<Footnote?> = _footnote.asStateFlow()

    fun showFootnote(html: String, link: Link) {
        _footnote.value = Footnote(html = html, link = link)
    }

    fun dismissFootnote() {
        _footnote.value = null
    }

    /**
     * The note [link] points at, if what it points at is a note.
     *
     * This is the half Readium does not do. It only recognises
     * `epub:type="noteref"` on the marker; every other spelling arrives here
     * with nothing but a link, so the target is fetched and judged on its own
     * content. Reading the resource blocks, so it is done off the main thread
     * inside the thing that blocks rather than at the call site.
     */
    suspend fun noteAt(link: Link): String? = withContext(Dispatchers.IO) {
        val publication = publication ?: return@withContext null
        val url = link.url()
        val fragment = url.fragment?.takeIf { it.isNotBlank() } ?: return@withContext null
        val resource = publication.get(url.removeFragment())
            ?: return@withContext null
        val html = resource.use { res ->
            res.read().getOrNull()?.decodeToString()
        } ?: return@withContext null
        FootnoteResolver.noteAt(html, fragment)
    }

    /** Whether this book can sync at all, so the action can stay hidden. */
    val syncable: StateFlow<Boolean> = flow { emit(positionSync.canSync(bookId)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Brings this book in step with the server, taking whichever side has
     * read further.
     *
     * There is no choosing: the further position wins outright, because
     * that is almost always the answer, and being asked to compare two
     * page numbers mid-book is a chore. Jumping ahead raises the same
     * "back to page N" pill as any other jump, so a deliberate reread is
     * one tap from being restored — and sending it again from there makes
     * this device the further one.
     */
    fun syncThisBook() {
        if (_bookSync.value == BookSync.Asking) return
        _bookSync.value = BookSync.Asking
        viewModelScope.launch {
            _bookSync.value = when (val outcome = positionSync.preview(bookId)) {
                PreviewOutcome.NotSynced -> BookSync.Note(R.string.reader_sync_book_not_synced)
                is PreviewOutcome.Failed -> BookSync.Note(outcome.reason.messageRes())
                is PreviewOutcome.Ready -> adoptFurthest(
                    outcome.preview,
                    progressDao.currentRevision(bookId) ?: 0,
                )
            }
        }
    }

    private suspend fun adoptFurthest(preview: SyncPreview, atRevision: Long): BookSync {
        val there = preview.remote
            ?: return BookSync.Note(R.string.reader_sync_book_no_remote)
        if (preview.agrees) return BookSync.Note(R.string.reader_sync_book_in_step)
        val here = preview.local ?: lastLocator?.locations?.totalProgression ?: 0.0
        val takeRemote = there > here
        return when (val outcome = positionSync.resolve(bookId, takeRemote, atRevision)) {
            is ResolveOutcome.Failed -> BookSync.Note(outcome.reason.messageRes())

            // A page was turned while this ran, so the answer was about
            // somewhere the reader no longer is.
            ResolveOutcome.Superseded -> BookSync.Note(R.string.reader_sync_book_moved)

            ResolveOutcome.Done -> {
                if (takeRemote) {
                    // The jump raises the way-back pill through onJump(),
                    // which is the whole announcement: where you are now,
                    // and one tap back to where you were.
                    progressDao.get(bookId)?.totalProgression?.let { goToProgression(it) }
                    BookSync.Idle
                } else {
                    BookSync.Note(R.string.reader_sync_book_sent)
                }
            }
        }
    }

    /** Puts the message away. */
    fun dismissBookSync() {
        _bookSync.value = BookSync.Idle
    }

    /**
     * Settles a disagreement an ordinary sync preserved, before the book
     * opens.
     *
     * The further side wins, so the book simply opens at the right page.
     * When that page came from the other device, the way-back pill is
     * raised over the first page shown: the announcement that the book
     * jumped ahead, and the one tap that undoes it for a reread.
     */
    private suspend fun settlePreservedConflict(publication: Publication) {
        val preview = positionSync.preservedConflict(bookId) ?: return
        val there = preview.remote ?: return
        val here = preview.local ?: 0.0
        val takeRemote = there > here
        val outcome = positionSync.resolve(
            bookId,
            takeRemote,
            progressDao.currentRevision(bookId) ?: 0,
        )
        // On failure the disagreement stays preserved and will be
        // settled on the next open; the book still opens now.
        if (outcome != ResolveOutcome.Done || !takeRemote) return
        if (bookPositions == null) bookPositions = BookPositions.of(publication)
        offerWayBack(here)
    }

    /**
     * Raises the way-back pill for a place the reader has not actually
     * been this session — the position this device held before a sync
     * moved it. [onJump] cannot do this: the book is not open yet, so
     * there is no last locator to remember.
     */
    private fun offerWayBack(progression: Double) {
        val positions = bookPositions?.takeIf { it.isUsable } ?: return
        val position = positions.positionAtProgression(progression.toFloat())
        val locator = positions.locatorAt(position) ?: return
        _jumpBack.value = JumpBack(locator = locator, position = position, fromSync = true)
        jumpBackTimer?.cancel()
        jumpBackTimer = viewModelScope.launch {
            delay(JUMP_BACK_TIMEOUT_MS)
            _jumpBack.value = null
        }
    }

    private suspend fun goToProgression(progression: Double) {
        val positions = bookPositions ?: BookPositions.of(publication ?: return)
            .also { bookPositions = it }
        if (!positions.isUsable) return
        positions.locatorAt(positions.positionAtProgression(progression.toFloat()))?.let {
            // Remember where we were before the destination overwrites it,
            // or the way back would point at the page just arrived on.
            onJump()
            lastLocator = it
            _progress.value = progressAt(it)
            _goTo.emit(it)
        }
    }

    private var publication: Publication? = null
    private var bookPositions: BookPositions? = null
    private var speed = ReadingSpeedEstimator()
    private var jumpBackTimer: Job? = null
    private var catchUpChecking = false
    private var catchUpDeclined: Double? = null
    private var readerActive = false

    private val ownTypography: StateFlow<BookTypography?> = typographyDao.observe(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether this book has been set apart from the shared settings. */
    val typographyIsOwn: StateFlow<Boolean> = ownTypography
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val prefs: StateFlow<ReaderPrefs> = prefsRepo.prefs
        .combine(ownTypography) { shared, own -> shared.withTypographyOf(own) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    /**
     * Whether this book holds the screen awake: the app-wide setting,
     * unless the book has been answered for on its own.
     */
    val keepScreenOn: StateFlow<Boolean> = appSettings.settings
        .map { it.keepScreenOn }
        .combine(bookScreenDao.observe(bookId)) { global, own -> own.keepsScreenOnWith(global) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Whether this book is read by scrolling: the app-wide setting,
     * unless the book has been answered for on its own.
     */
    val scrollMode: StateFlow<Boolean> = appSettings.settings
        .map { it.scrollMode }
        .combine(readingModeDao.observe(bookId)) { global, own -> own.scrollsWith(global) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Most recent position, used to persist progress and to survive recreation. */
    var lastLocator: Locator? = null
        private set

    init {
        viewModelScope.launch {
            positionPublisher.failures.collect { failedBook ->
                if (failedBook == bookId) {
                    _bookSync.value = BookSync.Note(R.string.reader_position_not_saved)
                }
            }
        }
        viewModelScope.launch {
            bookDao.observeAll()
                .map { books ->
                    val current = books.firstOrNull { it.url == bookId } ?: return@map null
                    seriesIdForExtras(current, books)
                }
                .distinctUntilChanged()
                .collect { seriesId ->
                    _seriesExtras.value = try {
                        seriesExtras.extras(seriesId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
        }
        viewModelScope.launch {
            combine(continuation, continueAfterDownload) { offer, auto ->
                readyToOpen(offer, auto)
            }.collect { next ->
                _pendingOpen.value = next
            }
        }
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
            // Ask the server where this book was left before deciding
            // where to open it. Bounded, and the answer is optional: a slow
            // or absent server delays the book by a moment at most, never
            // stops it opening.
            withTimeoutOrNull(OPEN_SYNC_TIMEOUT_MS) {
                runCatching { positionSync.request(SyncScope.Book(bookId)) }
            }

            // Sync preserves a disagreement rather than guessing across
            // devices in the background. Settle it now, while the book is
            // still a loading screen, so it opens at the further position
            // instead of being yanked there after arriving.
            settlePreservedConflict(publication)

            val stored = progressDao.get(bookId)
            speed = ReadingSpeedEstimator(
                learned = readingPace.pace(),
                bookPace = stored?.readingPace
                    ?: ReadingPace.Unknown,
            )
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
            // onResume arrives before a publication has necessarily
            // opened. Only now can foreground time be reading time.
            sessions.onReaderReady()
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
        // Fragment pause/resume and a viewport reflow can publish a new
        // current locator without any reader action. Treating that as a
        // page turn makes this device dirty just as another device's
        // position arrives, manufacturing a sync conflict.
        if (!readerActive) return
        val samePosition = lastLocator?.sameReadingPositionAs(locator) == true
        lastLocator = locator
        val stable = bookPositions?.resolve(locator)
        _progress.value = progressAt(locator, stable)
        // Readium recalculates totalProgression for a different viewport,
        // even when its stable resource position has not moved. Keep the
        // display current, but do not turn that layout detail into reading.
        if (samePosition) return
        val totalProgression = locator.locations.totalProgression
        if (stable != null) {
            val sample = speed.record(
                position = stable.coordinate,
                atMillis = SystemClock.elapsedRealtime(),
            )
            // A page that was really read teaches this reader's pace to
            // every book, not just this one.
            if (sample != null) viewModelScope.launch { readingPace.record(sample) }
        }
        // Capture the page's moment before suspendable position writes,
        // so database latency cannot become reading time.
        sessions.onPageTurned(totalProgression)
        val accepted = positionPublisher.publish(
            PositionUpdate(
                bookUrl = bookId,
                locatorJson = locator.toJSON().toString(),
                progression = locator.locations.totalProgression,
                readingSecondsPerPosition = speed.secondsPerPosition,
                readingPaceSamples = speed.pace.samples,
                readingPaceElapsedMs = speed.pace.elapsedMs,
                readingPaceEvidence = speed.pace.evidence,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        if (!accepted) {
            _bookSync.value = BookSync.Note(R.string.reader_position_not_saved)
        }
    }

    fun onReaderStopped() {
        if (!positionPublisher.closeBook(bookId)) {
            _bookSync.value = BookSync.Note(R.string.reader_position_not_saved)
        }
    }

    /** Whether two locators name the same stable place within a resource. */
    private fun Locator.sameReadingPositionAs(other: Locator): Boolean {
        if (href != other.href) return false
        val position = locations.position
        val otherPosition = other.locations.position
        if (position != null && otherPosition != null && position != otherPosition) return false

        val progression = locations.progression
        val otherProgression = other.locations.progression
        if (progression != null && otherProgression != null) {
            return kotlin.math.abs(progression - otherProgression) < LOCATOR_EPSILON
        }
        return position != null && otherPosition != null
    }

    /**
     * Called when the reader stops being looked at.
     *
     * The gap over a pause is not reading time. Nothing about a book
     * left open on a table distinguishes it from a very slow page, so
     * the clock is stopped here rather than guessed at afterwards.
     */
    fun onReaderPaused() {
        readerActive = false
        speed.forgetLastPosition()
        sessions.onPaused()
    }

    /**
     * Called when the reader comes back to the front.
     *
     * The counterpart to [onReaderPaused]. The recorder also waits for
     * [UiState.Ready], so loading and position-disagreement screens are
     * never counted merely because the activity is visible.
     */
    fun onReaderResumed() {
        readerActive = true
        sessions.onResumed()
        maybeOfferCatchUp()
    }

    /**
     * Quietly asks, on coming back to the front, whether another device
     * has read further, and offers its page rather than jumping there.
     *
     * An offer, not a report: every failure is silence, and declining
     * one place is remembered so the same pill does not chase the
     * reader around. Accepting goes through the same adopt-furthest
     * path as syncing the book by hand.
     */
    private fun maybeOfferCatchUp() {
        if (catchUpChecking || publication == null) return
        catchUpChecking = true
        viewModelScope.launch {
            try {
                val outcome = positionSync.preview(bookId)
                val preview = (outcome as? PreviewOutcome.Ready)?.preview ?: return@launch
                val there = preview.remote ?: return@launch
                val here = preview.local
                    ?: lastLocator?.locations?.totalProgression
                    ?: 0.0
                if (there <= here + EPSILON) return@launch
                // Declining an offer declines that place; only reading
                // done elsewhere since brings the pill back.
                if (catchUpDeclined?.let { kotlin.math.abs(it - there) < EPSILON } == true) {
                    return@launch
                }
                val positions = bookPositions?.takeIf { it.isUsable }
                _catchUp.value = CatchUp(
                    progression = there,
                    position = positions?.positionAtProgression(there.toFloat()),
                )
            } finally {
                catchUpChecking = false
            }
        }
    }

    /** Takes the offer: the further position wins, and the page turns. */
    fun acceptCatchUp() {
        if (_catchUp.value == null) return
        _catchUp.value = null
        viewModelScope.launch {
            val outcome = positionSync.resolve(
                bookId,
                takeRemote = true,
                atRevision = progressDao.currentRevision(bookId) ?: 0,
            )
            // Anything but a clean adoption leaves the book where it is;
            // the offer will simply be made again if it still stands.
            if (outcome != ResolveOutcome.Done) return@launch
            progressDao.get(bookId)?.totalProgression?.let { goToProgression(it) }
        }
    }

    /** Declines the offer, until the other device reads further still. */
    fun dismissCatchUp() {
        catchUpDeclined = _catchUp.value?.progression
        _catchUp.value = null
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
        val denominator = (positions.totalPositions - 1).coerceAtLeast(1).toFloat()
        return positions.chapters
            .map { (it.firstPosition - 1) / denominator }
            .filter { it > 0f }
    }

    private fun progressAt(
        locator: Locator,
        stable: StableBookProgress? = bookPositions?.resolve(locator),
    ): ReaderProgress? {
        val positions = bookPositions?.takeIf { it.isUsable } ?: return null
        val publication = publication ?: return null
        stable ?: return null
        val totalProgression = stable.progression.toFloat()
        val position = stable.position
        val chapter = publication.readingOrder.indexOfFirstWithHref(locator.href)
            ?.let(positions::chapterOfResource)
            ?: positions.chapterAt(position)
        val chapterEnd = chapter?.lastPosition ?: positions.totalPositions
        return ReaderProgress(
            position = position,
            totalPositions = positions.totalPositions,
            totalProgression = totalProgression,
            chapterTitle = chapter?.title,
            minutesLeftInChapter = speed.minutesFor(chapterEnd - stable.coordinate),
            minutesLeftInBook = speed.minutesFor(positions.totalPositions - stable.coordinate),
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

    /**
     * The mark covering this locator, if the reader selected one they
     * had already made.
     *
     * Compared by the words as well as the place: positions are only
     * accurate to about a page, so going by those alone made a second
     * highlight in the same chapter edit the first one instead of
     * joining it.
     */
    fun annotationAt(locator: Locator): BookAnnotation? {
        val selection = locator.markedPassage()
        return annotations.value
            .filter { it.kind != AnnotationKind.BOOKMARK.name }
            .firstOrNull { mark ->
                val other = mark.locator()?.markedPassage() ?: return@firstOrNull false
                isSamePassage(selection, other)
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

    /**
     * Sends a typography change wherever this book's settings live: to
     * the book's own set when it has been set apart, to the shared ones
     * otherwise. Read afresh each time rather than from the cached flow,
     * so a change made while the sheet is open cannot land in the wrong
     * place.
     */
    private fun typography(
        shared: suspend () -> Unit,
        own: (BookTypography) -> BookTypography,
    ) = viewModelScope.launch {
        val current = typographyDao.get(bookId)
        if (current == null) shared() else typographyDao.upsert(own(current))
    }

    /**
     * Sets this book apart, or hands it back to the shared settings.
     *
     * Setting it apart starts from how the book reads right now, so
     * nothing moves on screen at the moment you ask for it.
     */
    fun setTypographyIsOwn(own: Boolean) = viewModelScope.launch {
        if (own) {
            typographyDao.upsert(BookTypography.from(bookId, prefs.value))
        } else {
            typographyDao.clear(bookId)
        }
    }

    fun setFont(font: ReaderFont) = typography(
        shared = { prefsRepo.setFont(font) },
        own = { it.copy(font = font.id) },
    )

    fun setFontSize(size: Double) = typography(
        shared = { prefsRepo.setFontSize(size) },
        own = {
            it.copy(
                fontSize = size.coerceIn(ReaderPrefs.MIN_FONT_SIZE, ReaderPrefs.MAX_FONT_SIZE),
            )
        },
    )

    fun setTheme(theme: ReaderThemeChoice) = viewModelScope.launch { prefsRepo.setTheme(theme) }

    fun setLineHeight(value: Double?) = typography(
        shared = { prefsRepo.setLineHeight(value) },
        own = { it.copy(lineHeight = value) },
    )

    fun setPageMargins(value: Double?) = typography(
        shared = { prefsRepo.setPageMargins(value) },
        own = { it.copy(pageMargins = value) },
    )

    fun setBrightness(value: Float?) = viewModelScope.launch { prefsRepo.setBrightness(value) }

    fun setPageTurnAnimation(enabled: Boolean) =
        viewModelScope.launch { prefsRepo.setPageTurnAnimation(enabled) }

    /**
     * Answers the screen question for this book alone.
     *
     * The answer is always written, even when it agrees with Settings
     * today: a switch flipped by hand is a decision about this book, and
     * a later change to the app-wide setting has no business undoing it.
     * It also means nothing is read from the other store on the way,
     * so what was asked for is what gets stored.
     */
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch {
        bookScreenDao.upsert(BookScreen(bookUrl = bookId, keepScreenOn = enabled))
    }

    /**
     * Answers the scrolling question for this book alone, for the same
     * reason [setKeepScreenOn] does: the switch is reached from inside a
     * book, so it is about that book, and a later change to the app-wide
     * setting has no business undoing it.
     */
    fun setScrollMode(enabled: Boolean) = viewModelScope.launch {
        readingModeDao.upsert(BookReadingMode(bookUrl = bookId, scroll = enabled))
    }

    fun setColumnMode(mode: ColumnMode) =
        viewModelScope.launch { prefsRepo.setColumnMode(mode) }

    fun cycleFooterMode() = viewModelScope.launch {
        prefsRepo.setFooterMode(prefs.value.footerMode.next())
    }

    fun setFooterMode(mode: FooterMode) = viewModelScope.launch {
        prefsRepo.setFooterMode(mode)
    }

    override fun onCleared() {
        sessions.close()
        (_state.value as? UiState.Ready)?.publication?.close()
    }

    /** A place to return to after a jump, and the page it was on. */
    data class JumpBack(
        val locator: Locator,
        val position: Int?,
        val fromSync: Boolean = false,
    )

    companion object {
        private const val LOCATOR_EPSILON = 0.000001
        private const val JUMP_BACK_TIMEOUT_MS = 30_000L

        /**
         * How long opening a book will wait to hear where it was left
         * elsewhere. Long enough for a server on the same network, short
         * enough that a server which is not answering is not felt as the
         * app hanging.
         */
        private const val OPEN_SYNC_TIMEOUT_MS = 2_500L

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
                    bookDao = container.database.bookDao(),
                    annotationDao = container.database.annotationDao(),
                    typographyDao = container.database.typographyDao(),
                    bookScreenDao = container.database.bookScreenDao(),
                    readingModeDao = container.database.bookReadingModeDao(),
                    library = container.libraryRepository,
                    prefsRepo = container.readerPreferences,
                    readingPace = container.readingPace,
                    positionSync = container.positionSync,
                    appSettings = container.appSettings,
                    positionPublisher = container.readingPositions,
                    downloads = container.bookDownloads,
                    seriesExtras = container.seriesExtras,
                    remoteAccount = container.remoteAccount,
                    sessionManager = container.readingSessions,
                )
            }
        }
    }
}
