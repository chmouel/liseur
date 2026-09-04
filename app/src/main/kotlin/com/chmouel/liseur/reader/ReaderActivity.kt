package com.chmouel.liseur.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.chmouel.liseur.data.settings.AppSettings
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.chmouel.liseur.reader.chrome.BookSyncDialog
import com.chmouel.liseur.reader.chrome.ExternalLinkDialog
import com.chmouel.liseur.reader.chrome.PageTurner
import androidx.lifecycle.lifecycleScope
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.Book
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.chmouel.liseur.data.settings.UploadPolicy
import com.chmouel.liseur.data.settings.readingCssFor
import com.chmouel.liseur.ui.reading.FineTypographyActions
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.ReadingProgression as PublicationReadingProgression
import com.chmouel.liseur.ui.UploadBookOfferDialog
import com.chmouel.liseur.ui.library.UploadDecision
import com.chmouel.liseur.ui.library.canUploadTo
import com.chmouel.liseur.ui.library.uploadOnOpen
import com.chmouel.liseur.ui.theme.LiseurTheme
import com.chmouel.liseur.ui.theme.isDark
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.WidthClass
import com.chmouel.liseur.ui.widthClass
import com.chmouel.liseur.ui.ProvideEInk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.toAbsoluteUrl

class ReaderActivity : FragmentActivity() {

    private var navigator: EpubNavigatorFragment? = null
    private var pageTurner: PageTurner? = null
    private var chromeVisible = false

    /**
     * Read once and kept: the key handler runs on every press, and reading
     * it from DataStore there would be both slow and pointlessly async.
     */
    private var volumeKeysTurnPages = true

    private val viewModel: ReaderViewModel by viewModels {
        val open = checkNotNull(target)
        ReaderViewModel.factory(open.url, open.id)
    }

    /**
     * The book to open and the name its reading is filed under.
     *
     * Not always the URI the intent carried: a book handed over by
     * another app is shelved first, and may end up being read from the
     * copy that put it in the library. Resolving before the view model
     * exists is what keeps the reader's place attached to the shelf
     * entry — filing the book afterwards would strand the position
     * under a URI the library knows nothing about.
     */
    private data class OpenTarget(val url: AbsoluteUrl, val id: String)

    private var target by mutableStateOf<OpenTarget?>(null)

    /** A just-shelved book waiting for the reader to answer for it. */
    private var pendingOffer by mutableStateOf<Book?>(null)

    /** The title of a book on its way up, while the note about it shows. */
    private var sendingNote by mutableStateOf<String?>(null)

    /** A link out of the book, waiting for the reader to allow it. */
    private var pendingExternalLink by mutableStateOf<AbsoluteUrl?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Publication doesn't survive process death, so saved navigator
        // fragment state can't be restored; drop it and reopen the book at
        // the persisted locator instead. Rotation is handled without
        // recreation via android:configChanges.
        super.onCreate(null)

        val fromLibrary = intent.getStringExtra(EXTRA_URL)
        val incoming = fromLibrary?.toUri() ?: intent.data
        val url = incoming?.toAbsoluteUrl()
        if (incoming == null || url == null) {
            finish()
            return
        }

        if (fromLibrary != null) {
            target = OpenTarget(url, intent.getStringExtra(EXTRA_ID) ?: url.toString())
        } else {
            // Handed over by another app. Shelve it, then read the copy
            // the library settled on. If that cannot be done the book is
            // still opened from where it came: failing to file a book is
            // no reason to refuse to show it.
            lifecycleScope.launch {
                val shelved = container.libraryRepository.importExternalBook(incoming)
                val openable = shelved?.openableUrl?.toUri()?.toAbsoluteUrl()
                target = if (shelved != null && openable != null) {
                    OpenTarget(openable, shelved.url)
                } else {
                    OpenTarget(url, url.toString())
                }
                // onResume has already come and gone with no book to
                // start, so the reader has to be started here instead.
                // Only when it is genuinely in front: any later resume
                // finds a settled target and does this itself, and the
                // recorder counts resumes rather than tolerating them.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    viewModel.onReaderResumed()
                }
                if (shelved != null) decideUpload(shelved)
            }
        }

        enableEdgeToEdge()
        // Reading is what the app should come back to next time it opens.
        lifecycleScope.launch { container.sessionState.setLeftFromReader(true) }
        lifecycleScope.launch {
            container.appSettings.settings.collect { volumeKeysTurnPages = it.volumeKeysTurnPages }
        }
        setContent {
            val settings by container.appSettings.settings.collectAsState(initial = AppSettings())
            val appIsDark = settings.themeMode.isDark()
            ProvideEInk(settings.eInkMode) {
                LiseurTheme(
                    darkTheme = appIsDark,
                    dynamicColor = settings.dynamicColor,
                    eInk = LocalEInk.current,
                    colorEInk = settings.colorEInk,
                ) {
                    // Nothing may touch the view model until the book has a
                    // name, because building it is what fixes that name.
                    if (target == null) {
                        ReaderLoadingScreen()
                        return@LiseurTheme
                    }
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    // Hosted above the loading state so a question or a note
                    // about a manual sync can show over whatever screen the
                    // reader is on.
                    val bookSync by viewModel.bookSync.collectAsStateWithLifecycle()
                    BookSyncDialog(
                        state = bookSync,
                        onResolve = viewModel::resolveBookSync,
                        onDismiss = viewModel::dismissBookSync,
                    )
                    // Only over a book that is actually on screen. Asked
                    // during the spinner it would be a question about a
                    // book the reader has not seen yet.
                    if (state is ReaderViewModel.UiState.Ready) {
                        pendingOffer?.let { book ->
                            UploadBookOfferDialog(
                                title = book.title,
                                onSend = { sendUp(book); pendingOffer = null },
                                onAlways = { sendUpAlways(book); pendingOffer = null },
                                onDismiss = { declineUpload(book); pendingOffer = null },
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when (val s = state) {
                            ReaderViewModel.UiState.Loading ->
                                ReaderLoadingScreen()

                            is ReaderViewModel.UiState.Failure ->
                                ReaderErrorScreen(message = s.message, onBack = ::finish)

                            is ReaderViewModel.UiState.Ready -> {
                                // The factory must be installed before AndroidFragment
                                // instantiates the navigator, hence remember {} and
                                // not SideEffect {}.
                                //
                                // Column count is baked into the ReadiumCSS
                                // properties the navigator is built with, so it is
                                // also a key: changing it rebuilds the factory here
                                // and ReaderScreen rebuilds the fragment, which
                                // reopens at lastLocator so the reader stays put.
                                // Rotating a tablet into a narrow window is the
                                // same event, which is why the width goes through
                                // effectiveFor() rather than being read once.
                                //
                                // Scrolling is a key for the same reason: whether
                                // page turns are disabled is fixed when the
                                // navigator is configured, and turning scrolling on
                                // has to take the sideways chapter jumps with it.
                                val prefs by viewModel.prefs.collectAsStateWithLifecycle()
                                val readingTheme = prefs.themeChoice.resolve(appIsDark)
                                val scrollMode by viewModel.scrollMode.collectAsStateWithLifecycle()
                                val columnMode = prefs.columnMode.effectiveFor(widthClass())
                                // A third key, for the same reason as the
                                // other two: a font family is declared when
                                // the navigator is configured, so a font
                                // imported while the book is open only
                                // reaches the page if the fragment is built
                                // again. The ids in order are the whole of
                                // what the declarations depend on.
                                val importedFonts by viewModel.importedFonts
                                    .collectAsStateWithLifecycle()
                                val fontKey = importedFonts.joinToString(",") { it.id }
                                // Read once, here, alongside the factory that is
                                // built from it, and handed to the screen as the
                                // point this navigator is being restored to.
                                // Reading it again later would not give the same
                                // answer: onLocatorChanged assigns lastLocator
                                // before it decides whether the position persists,
                                // so the navigator's own opening emissions move it.
                                val restoreTarget = remember(
                                    s.navigatorFactory,
                                    columnMode,
                                    scrollMode,
                                    fontKey,
                                ) {
                                    viewModel.lastLocator ?: s.initialLocator
                                }
                                // Every move the reader is sent on, counted
                                // where both the screen and the link listener
                                // below can say so. A tapped link is a jump
                                // like any other, and a layout change settling
                                // over it takes the reader back off the page
                                // they asked for. See IssuedMoves.
                                val moves = remember { IssuedMoves() }
                                // Kept as a value rather than computed
                                // inline, because ReaderScreen has to know
                                // exactly what this navigator was built
                                // with to tell an already-rendered
                                // preference from one still to submit.
                                // The same classification ReaderScreen
                                // makes, from the same publication:
                                // whether this book's stylesheet can
                                // honour the fine typography settings
                                // decides whether they are worth
                                // switching advanced styles on for, and
                                // the answer has to be the same in the
                                // preferences the navigator is built
                                // with as in the ones submitted later,
                                // or the book reflows once for nothing
                                // the moment it opens.
                                val readingCss = remember(s.publication) {
                                    readingCssFor(
                                        reflowable =
                                            s.publication.metadata.layout != Layout.FIXED,
                                        language = s.publication.metadata.language?.code,
                                        metadataRtl =
                                            when (s.publication.metadata.readingProgression) {
                                                PublicationReadingProgression.RTL -> true
                                                PublicationReadingProgression.LTR -> false
                                                null -> null
                                            },
                                    )
                                }
                                val initialPreferences = remember(
                                    s.navigatorFactory,
                                    columnMode,
                                    scrollMode,
                                    fontKey,
                                    readingCss,
                                ) {
                                    prefs.toEpubPreferences(
                                        theme = readingTheme,
                                        columnMode = columnMode,
                                        scroll = scrollMode,
                                        css = readingCss,
                                    )
                                }
                                remember(s.navigatorFactory, columnMode, scrollMode, fontKey) {
                                    s.navigatorFactory.createFragmentFactory(
                                        initialLocator = restoreTarget,
                                        initialPreferences = initialPreferences,
                                        // Without a listener the navigator answers
                                        // every link the same way — go there — and
                                        // a footnote costs the reader their page.
                                        listener = ReaderNavigatorListener(
                                            scope = lifecycleScope,
                                            noteAt = viewModel::noteAt,
                                            onFootnote = viewModel::showFootnote,
                                            onFollow = { link ->
                                                viewModel.onJump()
                                                navigator?.let { nav ->
                                                    val token = moves.issue(
                                                        from = nav.currentLocator.value
                                                            .restorePoint(),
                                                        to = s.publication
                                                            .locatorFromLink(link)
                                                            ?.destination(),
                                                        nowMs = SystemClock.elapsedRealtime(),
                                                    )
                                                    if (!nav.go(link, animated = false)) {
                                                        moves.cancel(token)
                                                    }
                                                }
                                            },
                                            onExternal = { pendingExternalLink = it },
                                        ),
                                        configuration = epubNavigatorConfiguration(
                                            columnMode = columnMode,
                                            scroll = scrollMode,
                                            userFonts = importedFonts,
                                            onTextSelected = viewModel::onTextSelected,
                                            onSelectionCleared = viewModel::onSelectionCleared,
                                        ),
                                    ).also { supportFragmentManager.fragmentFactory = it }
                                }
                                pendingExternalLink?.let { link ->
                                    ExternalLinkDialog(
                                        url = link.toString(),
                                        host = link.host ?: link.toString(),
                                        onOpen = { openExternally(it); pendingExternalLink = null },
                                        onDismiss = { pendingExternalLink = null },
                                    )
                                }
                                LaunchedEffect(viewModel) {
                                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                        viewModel.pendingOpen.collect { next ->
                                            if (next == null) return@collect
                                            val url = (next.availability as? NextVolumeAvailability.Ready)
                                                ?.fileUrl ?: return@collect
                                            viewModel.consumeOpenNext()
                                            startActivity(
                                                intent(this@ReaderActivity, url, next.id),
                                            )
                                            finish()
                                        }
                                    }
                                }
                                ReaderScreen(
                                    publication = s.publication,
                                    restoreTarget = restoreTarget,
                                    fontKey = fontKey,
                                    initialPreferences = initialPreferences,
                                    moves = moves,
                                    prefsFlow = viewModel.prefs,
                                    readingTheme = readingTheme,
                                    typographyIsOwnFlow = viewModel.typographyIsOwn,
                                    progressFlow = viewModel.progress,
                                    jumpBackFlow = viewModel.jumpBack,
                                    catchUpFlow = viewModel.catchUp,
                                    continuationFlow = viewModel.continuation,
                                    onContinueNext = viewModel::onContinueNext,
                                    onReachedEndpaper = viewModel::onReachedEndpaper,
                                    onLeftEndpaper = viewModel::onLeftEndpaper,
                                    onLocatorChanged = viewModel::onLocatorChanged,
                                    onNavigatorChanged = { navigator = it },
                                    keepScreenOnFlow = viewModel.keepScreenOn,
                                    onKeepScreenOnChanged = viewModel::setKeepScreenOn,
                                    scrollModeFlow = viewModel.scrollMode,
                                    onScrollModeChanged = viewModel::setScrollMode,
                                    tapZonesFlow = viewModel.tapZones,
                                    pinchToResizeFlow = viewModel.pinchToResize,
                                    // Dialogs of this activity's own,
                                    // drawn over the reader. The page
                                    // must not carry on scrolling under
                                    // a question nobody has answered.
                                    blockedByDialog = pendingExternalLink != null ||
                                        pendingOffer != null ||
                                        bookSync !is ReaderViewModel.BookSync.Idle,
                                    onPageTurnerChanged = { pageTurner = it },
                                    onChromeVisibleChanged = { chromeVisible = it },
                                    onPrefsAction = remember {
                                        ReaderPrefsActions(
                                            setFont = viewModel::setFont,
                                            setFontSize = viewModel::setFontSize,
                                            setTheme = viewModel::setTheme,
                                            setLineHeight = viewModel::setLineHeight,
                                            setPageMargins = viewModel::setPageMargins,
                                            setBrightness = viewModel::setBrightness,
                                            setPageTurnAnimation = viewModel::setPageTurnAnimation,
                                            setColumnMode = viewModel::setColumnMode,
                                            setAutoScrollSpeed = viewModel::setAutoScrollSpeed,
                                            setTypographyIsOwn = viewModel::setTypographyIsOwn,
                                            fineTypography = FineTypographyActions(
                                                onTextAlignChanged = viewModel::setTextAlign,
                                                onHyphensChanged = viewModel::setHyphens,
                                                onFontWeightChanged = viewModel::setFontWeight,
                                                onLetterSpacingChanged = viewModel::setLetterSpacing,
                                                onWordSpacingChanged = viewModel::setWordSpacing,
                                                onParagraphSpacingChanged =
                                                    viewModel::setParagraphSpacing,
                                            ),
                                        )
                                    },
                                    onProgressAction = remember {
                                        ReaderProgressActions(
                                            cycleFooterMode = viewModel::cycleFooterMode,
                                            setFooterMode = viewModel::setFooterMode,
                                            jumpFrom = viewModel::onJump,
                                            dismissJumpBack = viewModel::dismissJumpBack,
                                            acceptCatchUp = viewModel::acceptCatchUp,
                                            dismissCatchUp = viewModel::dismissCatchUp,
                                            chapterTicks = viewModel::chapterTicks,
                                            chapterTitleAtPosition = viewModel::chapterTitleAtPosition,
                                            positionAtProgression = viewModel::positionAtProgression,
                                            locatorAtPosition = viewModel::locatorAtPosition,
                                            goToPagePrompt = viewModel::goToPagePrompt,
                                            resolvePage = viewModel::resolvePage,
                                            currentPercent = viewModel::currentPercent,
                                            resolvePercent = viewModel::resolvePercent,
                                            locatorAtOrBeforeProgression =
                                                viewModel::locatorAtOrBeforeProgression,
                                            prepareLocator = viewModel::prepareLocator,
                                            onApproximateResume = viewModel::onApproximateResume,
                                        )
                                    },
                                    annotationsFlow = viewModel.annotations,
                                    searchFlow = viewModel.search,
                                    bookmarkedFlow = viewModel.bookmarked,
                                    selectionEvents = viewModel.selectionEvents,
                                    onSelectionDismissed = viewModel::onSelectionCleared,
                                    eInkDisplay = container.eInkDisplay,
                                    vendorRefresh = settings.vendorRefresh,
                                    onAnnotationAction = remember {
                                        ReaderAnnotationActions(
                                            highlight = viewModel::highlight,
                                            addNote = viewModel::addNote,
                                            saveBookNote = viewModel::saveBookNote,
                                            annotationAt = viewModel::annotationAt,
                                            toggleBookmark = viewModel::toggleBookmark,
                                            remove = viewModel::remove,
                                            notebookMarkdown = viewModel::notebookMarkdown,
                                        )
                                    },
                                    onSearchAction = remember {
                                        ReaderSearchActions(
                                            search = viewModel::search,
                                            clear = viewModel::clearSearch,
                                        )
                                    },
                                    syncableFlow = viewModel.syncable,
                                    dictionaryFlow = viewModel.dictionary,
                                    onEnableDictionary = viewModel::enableDictionary,
                                    footnoteFlow = viewModel.footnote,
                                    onDismissFootnote = viewModel::dismissFootnote,
                                    goTo = viewModel.goTo,
                                    onBookSyncAction = remember {
                                        ReaderBookSyncActions(
                                            start = viewModel::syncThisBook,
                                        )
                                    },
                                    onBack = ::finish,
                                )
                            }
                        }

                        SendingNote(
                            // Only once the book is up. Raised while the
                            // spinner is still turning, the note spends
                            // its few seconds behind a loading screen and
                            // is gone by the time anyone could read it.
                            title = sendingNote.takeIf {
                                state is ReaderViewModel.UiState.Ready
                            },
                            onDone = { sendingNote = null },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    /**
     * Sends a newly shelved book to the server when the reader has
     * asked for that to happen by itself.
     *
     * The library does the same for books that arrive while it is on
     * screen, but a reader who only ever opens books from a file manager
     * may not go there for days. The verdict comes from the one place
     * that holds it, so this cannot drift from what the shelf would do.
     */
    private suspend fun decideUpload(book: Book) {
        val policy = container.appSettings.settings.first().uploadPolicy
        val server = container.remoteAccount.current()
        val canUpload = canUploadTo(server, container.remoteRouter)
        when (
            uploadOnOpen(
                book = book,
                policy = policy,
                canUpload = canUpload,
                alreadyAnswered = container.uploadPrompts.wasAnswered(book.url),
            )
        ) {
            UploadDecision.SEND -> sendUp(book)
            // Held rather than shown: the reader tapped a book to read
            // it, so the book gets the screen first. The composition
            // raises this once the publication is actually up.
            UploadDecision.ASK -> pendingOffer = book
            UploadDecision.NOTHING -> Unit
        }
    }

    private fun sendUp(book: Book) {
        container.bookUploads.enqueue(book)
        container.uploadPrompts.answer(book.url)
        // "Sending", not "sent": this is queued work that may wait for a
        // network and may retry, and the note should not claim an
        // arrival it has no way of knowing about.
        sendingNote = book.title
    }

    private fun sendUpAlways(book: Book) {
        lifecycleScope.launch {
            container.appSettings.setUploadPolicy(UploadPolicy.ALWAYS)
        }
        sendUp(book)
    }

    private fun declineUpload(book: Book) {
        container.uploadPrompts.answer(book.url)
    }

    /**
     * Hands a link in a book to whatever the phone uses for links.
     *
     * Only ever reached through the dialog that named the host, so this is
     * the reader's decision being carried out and not the app's. A phone with
     * nothing to open it with says so rather than doing nothing.
     */
    private fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.reader_external_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Nothing was ever opened, or the book is still being shelved, so
        // there is no view model to ask — touching it here would build one
        // around a URL that is not settled yet. See onCreate, which has
        // already called finish() in the first case.
        if (target == null) return
        // The activity is in front of the reader. The recorder also
        // waits until the publication is ready, so loading and failures
        // do not become reading time.
        viewModel.onReaderResumed()
    }

    override fun onPause() {
        if (target != null) {
            // Mark the reader inactive before FragmentActivity pauses the
            // navigator. Readium can publish a layout/restoration locator
            // from inside that pause; it is not a page the reader turned.
            // This also stops the reading clock the moment the book stops
            // being looked at, rather than counting the interruption.
            viewModel.onReaderPaused()
        }
        super.onPause()
        // Leaving the book is the moment the position is worth sending:
        // it is settled, and the reader is likely to pick up elsewhere.
        // After super, so the held place the ON_PAUSE observers publish
        // is queued ahead of the push that carries it. Asked here rather
        // than in onStop because a process killed while paused never
        // gets there.
        if (target != null) viewModel.onReaderLeft()
    }

    /**
     * Hardware keys turn pages, like the Kindle app's optional setting.
     *
     * Volume is what an e-ink reader's page buttons send, and what the
     * setting is named after, so it stays behind that setting. Media
     * keys are deliberately not handled: a reader listening to something
     * while they read still wants the next track.
     *
     * The rest come from a keyboard, a cover or a D-pad, the things
     * tablets and e-ink readers get attached to, and are only taken on a
     * window at least [WidthClass.MEDIUM_MIN_DP] wide. A phone keeps the
     * keys it always had, whatever is plugged into it — arrows and Space
     * are how switch access and a keyboard move focus and press what
     * they land on, and that is not worth quietly taking over to save a
     * reader a tap.
     *
     * Even on a wide screen, arrows and Space are left alone whenever
     * the chrome is up and there is something to move focus between.
     *
     * Left and right follow the book rather than the screen, the same
     * way the tap zones do, so an RTL publication turns the way it reads.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val turner = pageTurner ?: return super.onKeyDown(keyCode, event)
        if (keyCode in VOLUME_KEYS) {
            if (!volumeKeysTurnPages) return super.onKeyDown(keyCode, event)
        } else if (!isWideEnoughForKeyboardPaging) {
            return super.onKeyDown(keyCode, event)
        }
        if (chromeVisible && keyCode in FOCUS_KEYS) {
            return super.onKeyDown(keyCode, event)
        }
        return when (keyCode) {
            in FORWARD_KEYS -> {
                turner.turn(forward = true)
                true
            }

            in BACK_KEYS -> {
                turner.turn(forward = false)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                turner.turn(forward = !isRtl)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                turner.turn(forward = isRtl)
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Whether this window is wide enough to take over a keyboard's
     * navigation keys. Read from the configuration rather than
     * remembered, so folding, rotating or splitting the screen is
     * answered by the next key press.
     */
    private val isWideEnoughForKeyboardPaging: Boolean
        get() = resources.configuration.screenWidthDp >= WidthClass.MEDIUM_MIN_DP

    /** Whether the open book reads right to left. */
    @OptIn(ExperimentalReadiumApi::class)
    private val isRtl: Boolean
        get() = navigator?.overflow?.value?.readingProgression == ReadingProgression.RTL

    /**
     * Swallowed so the volume keys do not also ring up the volume panel
     * on the way back out. Only the volume keys need this; the others
     * have nothing behind them to suppress.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        when {
            navigator != null && volumeKeysTurnPages && keyCode in VOLUME_KEYS -> true

            else -> super.onKeyUp(keyCode, event)
        }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_ID = "id"

        private val VOLUME_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
        )

        /**
         * Keys a keyboard uses to move focus and press what it lands on.
         * Turning pages with them is only safe while nothing is focusable.
         */
        private val FOCUS_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_SPACE,
        )

        /** Keys that mean "on" whichever way the book reads. */
        private val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE,
        )

        /** Keys that mean "back" whichever way the book reads. */
        private val BACK_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_UP,
        )

        fun intent(context: Context, url: String, id: String = url): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ID, id)
    }
}
