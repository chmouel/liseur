package com.chmouel.liseur.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ThemeMode
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.reader.chrome.BookSyncDialog
import com.chmouel.liseur.reader.chrome.PageTurner
import androidx.lifecycle.lifecycleScope
import com.chmouel.liseur.container
import com.chmouel.liseur.ui.theme.LiseurTheme
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.WidthClass
import com.chmouel.liseur.ui.widthClass
import com.chmouel.liseur.ui.ProvideEInk
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

    private val bookUrl: AbsoluteUrl? by lazy {
        (intent.getStringExtra(EXTRA_URL)?.toUri() ?: intent.data)?.toAbsoluteUrl()
    }

    private val bookId: String by lazy {
        intent.getStringExtra(EXTRA_ID) ?: checkNotNull(bookUrl).toString()
    }

    private val viewModel: ReaderViewModel by viewModels {
        ReaderViewModel.factory(checkNotNull(bookUrl), bookId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Publication doesn't survive process death, so saved navigator
        // fragment state can't be restored; drop it and reopen the book at
        // the persisted locator instead. Rotation is handled without
        // recreation via android:configChanges.
        super.onCreate(null)

        val url = bookUrl
        if (url == null) {
            finish()
            return
        }

        enableEdgeToEdge()
        // Reading is what the app should come back to next time it opens.
        lifecycleScope.launch { container.sessionState.setLeftFromReader(true) }
        lifecycleScope.launch {
            container.appSettings.settings.collect { volumeKeysTurnPages = it.volumeKeysTurnPages }
        }
        setContent {
            val settings by container.appSettings.settings.collectAsState(initial = AppSettings())
            ProvideEInk(settings.eInkMode) {
                LiseurTheme(
                    darkTheme = when (settings.themeMode) {
                        ThemeMode.SYSTEM -> isSystemInDarkTheme()
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                    },
                    dynamicColor = settings.dynamicColor,
                    monochrome = LocalEInk.current,
                ) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    // Hosted above the loading state so a note about a manual
                    // sync can show whatever screen the reader is on.
                    val bookSync by viewModel.bookSync.collectAsStateWithLifecycle()
                    BookSyncDialog(
                        state = bookSync,
                        onDismiss = viewModel::dismissBookSync,
                    )
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
                            val scrollMode by viewModel.scrollMode.collectAsStateWithLifecycle()
                            val columnMode = prefs.columnMode.effectiveFor(widthClass())
                            remember(s.navigatorFactory, columnMode, scrollMode) {
                                s.navigatorFactory.createFragmentFactory(
                                    initialLocator = viewModel.lastLocator ?: s.initialLocator,
                                    initialPreferences = prefs.toEpubPreferences(
                                        columnMode = columnMode,
                                        scroll = scrollMode,
                                    ),
                                    configuration = epubNavigatorConfiguration(
                                        columnMode = columnMode,
                                        scroll = scrollMode,
                                        onTextSelected = viewModel::onTextSelected,
                                    ),
                                ).also { supportFragmentManager.fragmentFactory = it }
                            }
                            ReaderScreen(
                                publication = s.publication,
                                prefsFlow = viewModel.prefs,
                                typographyIsOwnFlow = viewModel.typographyIsOwn,
                                progressFlow = viewModel.progress,
                                jumpBackFlow = viewModel.jumpBack,
                                catchUpFlow = viewModel.catchUp,
                                nextUpFlow = viewModel.nextUp,
                                onOpenNextUp = { next ->
                                    startActivity(intent(this@ReaderActivity, next.fileUrl, next.id))
                                    finish()
                                },
                                onDismissNextUp = viewModel::dismissNextUp,
                                onLocatorChanged = viewModel::onLocatorChanged,
                                onNavigatorChanged = { navigator = it },
                                keepScreenOnFlow = viewModel.keepScreenOn,
                                onKeepScreenOnChanged = viewModel::setKeepScreenOn,
                                scrollModeFlow = viewModel.scrollMode,
                                onScrollModeChanged = viewModel::setScrollMode,
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
                                        setTypographyIsOwn = viewModel::setTypographyIsOwn,
                                    )
                                },
                                onProgressAction = remember {
                                    ReaderProgressActions(
                                        cycleFooterMode = viewModel::cycleFooterMode,
                                        setFooterMode = viewModel::setFooterMode,
                                        onJump = viewModel::onJump,
                                        dismissJumpBack = viewModel::dismissJumpBack,
                                        acceptCatchUp = viewModel::acceptCatchUp,
                                        dismissCatchUp = viewModel::dismissCatchUp,
                                        chapterTicks = viewModel::chapterTicks,
                                        chapterTitleAtPosition = viewModel::chapterTitleAtPosition,
                                        positionAtProgression = viewModel::positionAtProgression,
                                        locatorAtPosition = viewModel::locatorAtPosition,
                                    )
                                },
                                annotationsFlow = viewModel.annotations,
                                searchFlow = viewModel.search,
                                bookmarkedFlow = viewModel.bookmarked,
                                selectionRequests = viewModel.selectionRequests,
                                onAnnotationAction = remember {
                                    ReaderAnnotationActions(
                                        highlight = viewModel::highlight,
                                        addNote = viewModel::addNote,
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
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Nothing was ever opened, so there is no view model to ask —
        // touching it here would build one around a URL that is not
        // there. See onCreate, which has already called finish().
        if (bookUrl == null) return
        // The activity is in front of the reader. The recorder also
        // waits until the publication is ready, so loading and failures
        // do not become reading time.
        viewModel.onReaderResumed()
    }

    override fun onPause() {
        if (bookUrl != null) {
            // Mark the reader inactive before FragmentActivity pauses the
            // navigator. Readium can publish a layout/restoration locator
            // from inside that pause; it is not a page the reader turned.
            // This also stops the reading clock the moment the book stops
            // being looked at, rather than counting the interruption.
            viewModel.onReaderPaused()
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the book is the moment the position is worth sending:
        // it is settled, and the reader is likely to pick up elsewhere.
        viewModel.onReaderStopped()
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
