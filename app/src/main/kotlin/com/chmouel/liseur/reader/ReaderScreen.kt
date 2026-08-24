package com.chmouel.liseur.reader

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.compose.AndroidFragment
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.settings.DefinitionTarget
import com.chmouel.liseur.reader.annotations.BookmarkRibbon
import com.chmouel.liseur.reader.annotations.DECORATION_GROUP
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.reader.annotations.NoteDialog
import com.chmouel.liseur.reader.annotations.SelectionActions
import com.chmouel.liseur.reader.annotations.SelectionPopup
import com.chmouel.liseur.reader.annotations.locator
import com.chmouel.liseur.reader.annotations.lookUpExternally
import com.chmouel.liseur.reader.annotations.openDictionaryEntry
import com.chmouel.liseur.reader.annotations.shareText
import com.chmouel.liseur.reader.annotations.toDecorations
import com.chmouel.liseur.reader.dictionary.DefinitionSheet
import com.chmouel.liseur.reader.dictionary.WiktionaryClient
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.reader.chrome.CatchUpPill
import com.chmouel.liseur.reader.chrome.JumpBackPill
import com.chmouel.liseur.reader.chrome.PageTurnEffectState
import com.chmouel.liseur.reader.chrome.PageTurnOverlay
import com.chmouel.liseur.reader.chrome.PageTurner
import com.chmouel.liseur.reader.chrome.ReaderTapZones
import com.chmouel.liseur.reader.chrome.ReadingFooter
import com.chmouel.liseur.reader.chrome.FooterMetrics
import com.chmouel.liseur.reader.chrome.ScrollEdgeTurner
import com.chmouel.liseur.reader.chrome.visibleWebView
import com.chmouel.liseur.reader.chrome.layoutPasses
import com.chmouel.liseur.reader.chrome.ReadingScrubber
import com.chmouel.liseur.reader.chrome.ContentsScreen
import com.chmouel.liseur.reader.chrome.Endpaper
import com.chmouel.liseur.reader.chrome.FootnoteCard
import com.chmouel.liseur.reader.chrome.TypographySheet
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.reader.search.SearchScreen
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.eink.EInkDisplay
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.widthClass
import com.chmouel.liseur.ui.windowWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Duration of the gentle chrome show/hide animation. */
private const val CHROME_ANIM_MS = 300

// A reflow wait is measured in settleLayout() steps of two frames each.
// The first bound is how long a preference gets to start reflowing the
// page before it is taken not to reflow at all (a colour change never
// does); the second is how long a reflow gets to come to rest.
private const val REFLOW_START_POLLS = 8
private const val REFLOW_SETTLE_POLLS = 30
private const val VERIFY_ATTEMPTS = 3

// How long a run of selection reports has to go quiet before the passage
// is read back. Long enough to swallow the second report every action
// mode makes on the way up, and the stream of them a handle drag sends;
// short enough that the bar still feels like it answers the gesture.
private const val SELECTION_SETTLE_MS = 90L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalReadiumApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ReaderScreen(
    publication: Publication,
    prefsFlow: StateFlow<ReaderPrefs>,
    readingTheme: ReaderTheme,
    typographyIsOwnFlow: StateFlow<Boolean>,
    progressFlow: StateFlow<ReaderProgress?>,
    jumpBackFlow: StateFlow<ReaderViewModel.JumpBack?>,
    catchUpFlow: StateFlow<ReaderViewModel.CatchUp?>,
    continuationFlow: StateFlow<EndpaperContinuation?>,
    onContinueNext: () -> Unit,
    onReachedEndpaper: () -> Unit,
    onLeftEndpaper: () -> Unit,
    onLocatorChanged: (Locator, NavigatorPositionEvent) -> Unit,
    onNavigatorChanged: (EpubNavigatorFragment?) -> Unit,
    onPageTurnerChanged: (PageTurner?) -> Unit,
    onChromeVisibleChanged: (Boolean) -> Unit = {},
    onPrefsAction: ReaderPrefsActions,
    onProgressAction: ReaderProgressActions,
    annotationsFlow: StateFlow<List<BookAnnotation>>,
    searchFlow: StateFlow<ReaderViewModel.SearchState>,
    bookmarkedFlow: StateFlow<Boolean>,
    selectionEvents: SharedFlow<SelectionEvent>,
    onSelectionDismissed: () -> Unit,
    eInkDisplay: EInkDisplay,
    vendorRefresh: Boolean,
    onAnnotationAction: ReaderAnnotationActions,
    onSearchAction: ReaderSearchActions,
    syncableFlow: StateFlow<Boolean>,
    dictionaryFlow: StateFlow<ReaderViewModel.DictionarySettings>,
    onEnableDictionary: () -> Unit,
    footnoteFlow: StateFlow<ReaderViewModel.Footnote?>,
    onDismissFootnote: () -> Unit,
    keepScreenOnFlow: StateFlow<Boolean>,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    scrollModeFlow: StateFlow<Boolean>,
    onScrollModeChanged: (Boolean) -> Unit,
    goTo: SharedFlow<Locator>,
    onBookSyncAction: ReaderBookSyncActions,
    onBack: () -> Unit,
) {
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val navigatorNow by rememberUpdatedState(navigator)
    val readingThemeNow by rememberUpdatedState(readingTheme)
    var chromeVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var searchFor by remember { mutableStateOf<String?>(null) }
    var searchHit by remember { mutableStateOf<Locator?>(null) }
    var showTypography by remember { mutableStateOf(false) }
    val chromeVisibleNow by rememberUpdatedState(chromeVisible)
    val prefs by prefsFlow.collectAsStateWithLifecycle()
    val keepScreenOn by keepScreenOnFlow.collectAsStateWithLifecycle()
    val scrollMode by scrollModeFlow.collectAsStateWithLifecycle()
    val scrollModeNow by rememberUpdatedState(scrollMode)
    val columnMode = prefs.columnMode.effectiveFor(widthClass())

    // The activity decides what a keyboard's arrows mean, and the
    // answer depends on whether there is any chrome to move focus
    // around in.
    LaunchedEffect(chromeVisible) { onChromeVisibleChanged(chromeVisible) }
    val typographyIsOwn by typographyIsOwnFlow.collectAsStateWithLifecycle()
    val progress by progressFlow.collectAsStateWithLifecycle()
    val jumpBack by jumpBackFlow.collectAsStateWithLifecycle()
    val catchUp by catchUpFlow.collectAsStateWithLifecycle()
    val continuation by continuationFlow.collectAsStateWithLifecycle()
    val annotations by annotationsFlow.collectAsStateWithLifecycle()
    val searchState by searchFlow.collectAsStateWithLifecycle()
    val bookmarked by bookmarkedFlow.collectAsStateWithLifecycle()
    val dictionary by dictionaryFlow.collectAsStateWithLifecycle()
    val footnote by footnoteFlow.collectAsStateWithLifecycle()

    /*
     * Where the last touch landed, so a note can be shown beside the marker
     * that raised it.
     *
     * Readium reports no point for a tap on a footnote: the moment it
     * recognises a link it stops forwarding the gesture and starts resolving
     * the note. The touch is therefore caught here on the way down, on the
     * initial pass and without consuming anything, which leaves the web view
     * and the tap zones seeing exactly what they saw before.
     */
    var lastTouchY by remember { mutableStateOf<Float?>(null) }
    // The tracker below outlives every recomposition, so it cannot read
    // `footnote` directly — it would keep seeing whatever was true when it
    // started. This gives it a window onto the current value.
    val noteShowing by rememberUpdatedState(footnote != null)
    var selection by remember { mutableStateOf<ActiveSelection?>(null) }
    var noteFor by remember { mutableStateOf<ActiveSelection?>(null) }
    var defineWord by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val effectScope = rememberCoroutineScope()
    var pendingPositionEvent by remember { mutableStateOf<NavigatorPositionEvent?>(null) }

    // Open for as long as the page is being rebuilt underneath the reader.
    // See ReflowScope: a locator nobody has claimed while this is open is the
    // layout moving, not the reader.
    val reflow = remember { ReflowScope() }

    // A fixed-layout book places everything by absolute coordinates and has no
    // reflow to speak of; measuring what "fits" there means nothing.
    val fitWideContent = remember(publication) {
        publication.metadata.layout != Layout.FIXED
    }

    // The place the reader was at when a run of preference changes began.
    // A restore that lands slightly off must not become the anchor of the
    // next change, or each nudge of a slider walks the position a little
    // further from the page being read; the anchor is held until the
    // reader actually moves, and every reflow restores to the same spot.
    var reflowAnchor by remember { mutableStateOf<Locator?>(null) }

    suspend fun capture(nav: EpubNavigatorFragment, locator: Locator): Locator =
        onProgressAction.prepareLocator(ExactLocatorAnchor.capture(nav, locator))

    suspend fun settleLayout() {
        withFrameNanos { }
        withFrameNanos { }
    }

    // Waits for the WebView to finish reflowing after a preference lands.
    // Readium applies preferences as CSS inside the WebView on its own
    // schedule, so counting frames of the Compose clock says nothing
    // about it: the page keeps changing size while text is still moving.
    // Wait first for the layout to differ from what it was — bounded,
    // because a colour-only change never reflows at all — and then for
    // two consecutive readings to agree.
    suspend fun awaitReflowSettled(nav: EpubNavigatorFragment, before: String?) {
        var last = before
        var changed = before == null
        repeat(REFLOW_SETTLE_POLLS) { poll ->
            settleLayout()
            val now = ExactLocatorAnchor.layoutSignature(nav) ?: return
            if (!changed && now == last) {
                if (poll >= REFLOW_START_POLLS) return
            } else if (changed && now == last) {
                return
            } else {
                changed = true
                last = now
            }
        }
    }

    suspend fun navigate(
        nav: EpubNavigatorFragment,
        locator: Locator,
        event: NavigatorPositionEvent,
        verify: Boolean = false,
    ) {
        pendingPositionEvent = event
        nav.go(locator, animated = false)
        if (!verify || !ExactLocatorAnchor.isExact(locator)) return
        // The go above is carried out by a script the WebView runs when
        // it gets to it; asking too early answers for the page it is
        // still leaving. Look a few times before declaring it missed.
        repeat(VERIFY_ATTEMPTS) {
            settleLayout()
            if (ExactLocatorAnchor.verify(nav, locator)) return
        }
        val progression = locator.locations.totalProgression ?: return
        val fallback = onProgressAction.locatorAtOrBeforeProgression(progression) ?: return
        pendingPositionEvent = event
        nav.go(fallback, animated = false)
        onProgressAction.onApproximateResume()
    }

    fun navigateLater(
        locator: Locator,
        event: NavigatorPositionEvent,
        verify: Boolean = false,
    ) {
        val nav = navigatorNow ?: return
        effectScope.launch { navigate(nav, locator, event, verify) }
    }

    val eInk = LocalEInk.current
    val eInkNow by rememberUpdatedState(eInk)
    var showingEnd by remember { mutableStateOf(false) }
    val showingEndNow by rememberUpdatedState(showingEnd)
    var endpaperRtl by remember { mutableStateOf(false) }
    val pageTurnEffect = remember { PageTurnEffectState(effectScope) }
    val pageTurner = remember {
        PageTurner(
            effect = pageTurnEffect,
            // The sliding page is a snapshot dragged across the screen,
            // which is the one thing electronic paper cannot draw: it
            // arrives as a trail of half-erased pages. The instant jump
            // the preference already had is what e-paper wants anyway.
            // A scrolled book has no page to lift off, and reads the same
            // answer as whether its scrolling glides or jumps.
            isAnimated = { prefsFlow.value.pageTurnAnimation && !eInkNow },
            isEffectSuppressed = { chromeVisibleNow },
            isScrolling = { scrollModeNow },
            // Readium reads this off the book rather than the reader:
            // vertical text is always scrolled, because CSS columns
            // cannot paginate it, so a book can be scrolling here with
            // the setting switched off.
            isVerticalText = { navigatorNow?.settings?.value?.verticalText == true },
            showingEnd = { showingEndNow },
            onReachedEnd = {
                endpaperRtl = navigatorNow?.overflow?.value?.readingProgression ==
                    ReadingProgression.RTL
                showingEnd = true
                chromeVisible = false
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            },
            onLeaveEnd = {
                showingEnd = false
                onLeftEndpaper()
            },
        )
    }

    LaunchedEffect(showingEnd) {
        if (showingEnd) onReachedEndpaper()
    }

    LaunchedEffect(navigator, lifecycle) {
        onNavigatorChanged(navigator)
        val nav = navigator ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The navigator replays its current locator on subscription.
            // Subscribe afresh on every resume so a layout/restoration
            // emission is discarded too: merely bringing the reader back
            // must not look like this device turned a page and turn a
            // one-sided remote update into a conflict.
            nav.currentLocator.drop(1).collect { native ->
                val event = pendingPositionEvent
                    ?: if (reflow.active) {
                        NavigatorPositionEvent.PREFERENCE_REFLOW
                    } else {
                        NavigatorPositionEvent.READER_MOVEMENT
                    }
                pendingPositionEvent = null
                // Anywhere the reader actually goes ends the run of
                // preference changes the held anchor was covering.
                if (event != NavigatorPositionEvent.PREFERENCE_REFLOW) reflowAnchor = null
                onLocatorChanged(capture(nav, native), event)
            }
        }
    }

    // Asking the maker's screen controller to repaint the page the way
    // it repaints text.
    //
    // Both calls are made against the web view the book is actually
    // drawn in, because Onyx's are per view and the fragment root is not
    // the view that holds the prose. Readium builds a fresh web view for
    // each resource and swaps it in, so this cannot be said once at the
    // start; it is said again whenever the view on screen is not the one
    // spoken to last.
    //
    // Positions and layout passes are both only prompts to go and look.
    // Positions alone would miss the page the book opens at, which is
    // laid out after the position announcing it and may be the only page
    // a reader visits; layout alone would be at the mercy of a resource
    // swapped in without one. Neither costs anything it should not: the
    // common case is finding the same view again and stopping at a
    // reference comparison.
    LaunchedEffect(navigator, eInkDisplay, vendorRefresh) {
        val nav = navigator ?: return@LaunchedEffect
        if (!vendorRefresh || eInkDisplay.vendor == null) return@LaunchedEffect
        val root = nav.publicationView
        var spokenTo: WebView? = null
        merge(nav.currentLocator.map { }, layoutPasses(root)).collect {
            val web = visibleWebView(root) ?: return@collect
            if (web === spokenTo) return@collect
            spokenTo = web
            eInkDisplay.readingMode(web)
            eInkDisplay.optimizeWebView(web)
        }
    }

    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        val requested = onProgressAction.currentLocator() ?: return@LaunchedEffect
        if (!ExactLocatorAnchor.isExact(requested)) return@LaunchedEffect
        settleLayout()
        if (ExactLocatorAnchor.verify(nav, requested)) return@LaunchedEffect
        val progression = requested.locations.totalProgression ?: return@LaunchedEffect
        val fallback = onProgressAction.locatorAtOrBeforeProgression(progression)
            ?: return@LaunchedEffect
        pendingPositionEvent = NavigatorPositionEvent.FRAGMENT_RECREATION
        nav.go(fallback, animated = false)
        onProgressAction.onApproximateResume()
    }

    // Apply preference changes to the rendered book as they happen.
    // The column mode is filtered through the window width for the same
    // reason it is in ReaderActivity: a narrow window cannot carry a
    // choice made on a wide one, and the control to undo it is hidden.
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        // The factory already built this navigator with the current
        // preferences. Submitting that same value once more reflows the
        // freshly opened book and emits a second restoration locator,
        // which must not be recorded as a page the reader turned.
        //
        // The theme is combined in rather than read once because it can
        // change without the preferences changing at all: a reader on
        // the app's theme who leaves their phone to turn itself dark at
        // dusk has chosen nothing, and the open book should still follow.
        combine(
            prefsFlow,
            snapshotFlow { Triple(readingThemeNow, columnMode, scrollMode) },
        ) { p, (theme, columns, scrolling) ->
            p.toEpubPreferences(theme, columns, scrolling)
        }
            .drop(1)
            .collect {
                reflow.within {
                    val anchor = reflowAnchor ?: capture(nav, nav.currentLocator.value)
                    reflowAnchor = anchor
                    onLocatorChanged(anchor, NavigatorPositionEvent.PREFERENCE_REFLOW)
                    val before = ExactLocatorAnchor.layoutSignature(nav)
                    nav.submitPreferences(it)
                    awaitReflowSettled(nav, before)
                    // A table can be comfortable at 100% and too wide at 175%,
                    // so what fits has to be asked again once the new size has
                    // landed — and before the anchor is restored, since fitting
                    // it moves the text once more.
                    if (fitWideContent &&
                        WideContentFit.apply(nav) == WideContentFit.Result.CHANGED
                    ) {
                        awaitReflowSettled(nav, before)
                    }
                    navigate(
                        nav = nav,
                        locator = anchor,
                        event = NavigatorPositionEvent.PREFERENCE_REFLOW,
                        verify = true,
                    )
                }
            }
    }

    // Content wider than the page paints over the page after it rather than
    // being clipped, because in paginated mode the columns Readium sets
    // `overflow: visible` on are the pages themselves. Measure and constrain
    // what actually overflows, once per resource that gets laid out.
    //
    // Positions and layout passes are both only prompts to go and look, for
    // the same reasons the e-ink block above gives: a position alone misses
    // the page the book opens at, a layout pass alone can miss a resource
    // swapped in without one, and finding the same web view again costs a
    // reference comparison.
    LaunchedEffect(navigator, fitWideContent) {
        val nav = navigator ?: return@LaunchedEffect
        if (!fitWideContent) return@LaunchedEffect
        val root = nav.publicationView
        var fitted: WebView? = null
        merge(nav.currentLocator.map { }, layoutPasses(root)).collect {
            val web = visibleWebView(root) ?: return@collect
            if (web === fitted) return@collect
            fitted = web
            reflow.within {
                // The anchor is captured here rather than taken from
                // reflowAnchor: that one belongs to a run of preference
                // changes in the resource being left, and restoring to it
                // would carry the reader back out of the one they just
                // turned into.
                val anchor = capture(nav, nav.currentLocator.value)
                val before = ExactLocatorAnchor.layoutSignature(nav)
                if (WideContentFit.apply(nav) == WideContentFit.Result.CHANGED) {
                    awaitReflowSettled(nav, before)
                    navigate(
                        nav = nav,
                        locator = anchor,
                        event = NavigatorPositionEvent.PREFERENCE_REFLOW,
                        verify = true,
                    )
                }
            }
        }
    }

    // Picking the selection up from the navigator when it tells us the
    // reader made one, and turning it into a place to put the action bar.
    //
    // The web view reports a selection several times per gesture and
    // reading one back is not cheap, so a run of reports settles before it
    // is asked; see collectSettledSelection. That saves work the reader was
    // waiting on, which matters most on electronic paper — it does nothing
    // about the platform's own magnifier and selection handles, which an
    // app hosting a web view has no say over at all.
    LaunchedEffect(navigator, selectionEvents) {
        val nav = navigator ?: run {
            selection = null
            return@LaunchedEffect
        }
        selectionEvents.collectSettledSelection(
            settleMs = SELECTION_SETTLE_MS,
            read = { nav.currentSelection() },
            onSelection = { current ->
                selection = current?.let {
                    ActiveSelection(
                        locator = it.locator,
                        rect = it.rect,
                        existing = onAnnotationAction.annotationAt(it.locator),
                    )
                }
            },
        )
    }

    // Keep the hit you jumped to marked on the page, so the eye lands on
    // it rather than hunting the paragraph for the word that matched.
    LaunchedEffect(navigator, searchHit) {
        val nav = navigator ?: return@LaunchedEffect
        nav.applyDecorations(
            listOfNotNull(
                searchHit?.let {
                    Decoration(
                        id = "search-hit",
                        locator = it,
                        style = Decoration.Style.Highlight(
                            tint = SEARCH_HIT_TINT.toArgb(),
                            isActive = false,
                        ),
                    )
                },
            ),
            SEARCH_DECORATION_GROUP,
        )
    }

    // Draw the marks the reader has made over the page.
    LaunchedEffect(navigator, annotations) {
        val nav = navigator ?: return@LaunchedEffect
        nav.applyDecorations(annotations.toDecorations(), DECORATION_GROUP)
    }

    DisposableEffect(navigator) {
        val nav = navigator
        pageTurner.navigator = nav
        pageTurner.window = (view.context as? Activity)?.window
        pageTurner.publication = publication
        onPageTurnerChanged(if (nav != null) pageTurner else null)
        val listeners = nav?.let {
            listOf(
                ReaderTapZones(
                    navigator = it,
                    isChromeVisible = { chromeVisibleNow },
                    isScrolling = { scrollModeNow },
                    onTurnPage = pageTurner::turn,
                    onShowChrome = { chromeVisible = true },
                    onHideChrome = { chromeVisible = false },
                ),
                ScrollEdgeTurner(
                    navigator = it,
                    isScrolling = { scrollModeNow },
                    isVerticalText = { it.settings.value.verticalText },
                    onStepChapter = { forward -> pageTurner.stepChapter(forward) },
                ),
            ).onEach(it::addInputListener)
        }
        onDispose {
            if (nav != null) listeners?.forEach(nav::removeInputListener)
            pageTurner.navigator = null
            pageTurner.window = null
            pageTurner.publication = null
            onPageTurnerChanged(null)
            onNavigatorChanged(null)
        }
    }

    ImmersiveMode(hideSystemBars = !chromeVisible)
    ScreenBrightness(brightness = prefs.brightness)
    KeepScreenOn(enabled = keepScreenOn)

    Box(
        Modifier
            .fillMaxSize()
            .background(readingTheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // The card is drawn inside this box, so without this
                        // guard reading a note would move it: every touch on
                        // the card would become the new anchor and the card
                        // would jump out from under the finger scrolling it.
                        if (noteShowing) continue
                        event.changes.firstOrNull { it.pressed }
                            ?.let { lastTouchY = it.position.y }
                    }
                }
            },
    ) {
        // The page runs the whole screen in both modes. Readium's own
        // padding is switched off (see dimens.xml) because it is applied
        // after the page is laid out, which cuts the last line in half;
        // the small margin here is applied before the page loads, and is
        // the only thing between the text and the edges of the screen.
        //
        // The system bars used to be inset out of the way. They are
        // hidden while reading, so all that space bought was a band of
        // background at the top of every page; the top is not inset
        // now. The bottom of a paged book keeps clear of the footer
        // instead of the bars: the navigation-bar inset the footer
        // floats on — read ignoring visibility, so it is the same
        // whether the bars are shown — and above it the footer's own
        // derived height (FooterMetrics), which follows the system
        // font scale the way the fixed count of dp it replaces could
        // not. Neither figure moves when the chrome shows or hides, so
        // toggling the chrome still cannot reflow the page.
        //
        // The camera hole is the one thing still kept clear of a page
        // that is turned, because a line behind it is a line that never
        // comes back. The inset is the hole's own height on the hole's
        // own edge — zero on a phone that has none, which is all the
        // detection this needs, and no more than the hardware costs on a
        // phone that has one.
        //
        // A scrolled book keeps only the sides of it. Text moving up the
        // screen passes under a hole at the top and out again, so the
        // reader loses a word for a moment and gets it back; a hole on
        // the side, which is where it lands in landscape, sits over the
        // same end of every line and never gives any of them back.
        // Keyed on the column mode: the count is part of the ReadiumCSS
        // properties the navigator is constructed with and cannot be
        // changed on a live fragment, so the fragment is rebuilt instead.
        // The factory hands it lastLocator, so the page stays where it was.
        //
        // Scrolling is keyed for the same reason: whether a sideways
        // swipe turns a page is fixed at construction too, and it has to
        // stop turning them when the pages become one long column.
        key(columnMode, scrollMode) {
            // Derived, not measured: the reservation must exist before
            // the page lays out, or the last line is cut in half. The
            // line height is the very style the footer draws with, and
            // toDp() owns the sp-to-dp conversion so nonlinear font
            // scaling is honoured. A footer switched off reserves only
            // the page's own 12dp margin, same as the top edge — the
            // footer band and the inset under it go back to the text,
            // and the one reflow that costs is the settings change
            // itself.
            val footerLineHeight = MaterialTheme.typography.labelSmall.lineHeight
            val footerShowing = prefs.footerMode != FooterMode.NONE
            val footerReserve = FooterMetrics.reservedHeightDp(
                lineHeightDp = with(LocalDensity.current) {
                    if (footerLineHeight.isSp) {
                        footerLineHeight.toDp().value
                    } else {
                        FooterMetrics.FALLBACK_LINE_HEIGHT_SP.sp.toDp().value
                    }
                },
            ).dp
            AndroidFragment<EpubNavigatorFragment>(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (scrollMode) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                            )
                        } else {
                            Modifier
                                .windowInsetsPadding(WindowInsets.displayCutout)
                                .then(
                                    if (footerShowing) {
                                        Modifier.windowInsetsPadding(
                                            WindowInsets.navigationBarsIgnoringVisibility
                                                .only(WindowInsetsSides.Bottom),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(
                                    top = 12.dp,
                                    bottom = if (footerShowing) footerReserve else 12.dp,
                                )
                        },
                    ),
            ) { fragment ->
                navigator = fragment
                fragment.view?.let(::consumeInsetsForReadium)
            }
        }

        if (showingEnd) {
            Endpaper(
                title = publication.metadata.title.orEmpty(),
                author = publication.metadata.authors
                    .joinToString(", ") { it.name }
                    .ifBlank { null },
                theme = readingTheme,
                finished = continuation?.finished,
                timeSpentMs = continuation?.timeSpentMs,
                seriesName = continuation?.seriesName,
                finishedVolume = continuation?.finishedVolume,
                next = continuation?.next,
                missingIndex = continuation?.missingIndex,
                noNextInLibrary = continuation?.noNextInLibrary == true,
                seriesCompletion = continuation?.seriesCompletion,
                rtl = endpaperRtl,
                onTurnBack = {
                    showingEnd = false
                    onLeftEndpaper()
                },
                onLibrary = {
                    onLeftEndpaper()
                    onBack()
                },
                onOpenNext = onContinueNext,
            )
        }

        PageTurnOverlay(pageTurnEffect)

        if (!showingEnd) {
            BookmarkRibbon(
                bookmarked = bookmarked,
                theme = readingTheme,
                onToggle = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onAnnotationAction.toggleBookmark()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
        ) {
            if (!showingEnd) {
                jumpBack?.let { target ->
                    JumpBackPill(
                        position = target.position,
                        fromSync = target.fromSync,
                        excerpt = target.excerpt,
                        remoteAt = target.remoteAt,
                        confidence = target.confidence,
                        resumePosition = target.resumePosition,
                        theme = readingTheme,
                        onJumpBack = {
                            onProgressAction.dismissJumpBack()
                            navigateLater(target.locator, NavigatorPositionEvent.LOCAL_JUMP)
                        },
                        onDismiss = onProgressAction.dismissJumpBack,
                    )
                }
                // The way back outranks the way forward: a jump that just
                // happened is the fresher offer, and two pills is a quiz.
                if (jumpBack == null) {
                    catchUp?.let { offer ->
                        CatchUpPill(
                            position = offer.position,
                            excerpt = offer.excerpt,
                            remoteAt = offer.remoteAt,
                            confidence = offer.confidence,
                            theme = readingTheme,
                            onCatchUp = onProgressAction.acceptCatchUp,
                            onDismiss = onProgressAction.dismissCatchUp,
                        )
                    }
                }
                if (chromeVisible) {
                    ReadingScrubber(
                        progress = progress,
                        theme = readingTheme,
                        chapterTicks = remember(progress?.totalPositions) {
                            onProgressAction.chapterTicks()
                        },
                        titleAtPosition = onProgressAction.chapterTitleAtPosition,
                        positionAtProgression = onProgressAction.positionAtProgression,
                        onSeek = { position ->
                            onProgressAction.locatorAtPosition(position)?.let {
                                onProgressAction.onJump()
                                navigateLater(it, NavigatorPositionEvent.LOCAL_JUMP)
                            }
                        },
                    )
                } else if (!scrollMode && jumpBack == null && catchUp == null) {
                    // A scrolled page runs under this corner, so a footer
                    // left drawn there prints itself over the text. The
                    // figures are one tap away with the rest of the chrome.
                    ReadingFooter(
                        progress = progress,
                        mode = prefs.footerMode,
                        theme = readingTheme,
                        onCycleMode = onProgressAction.cycleFooterMode,
                    )
                }
            }
        }

        // Electronic paper cannot slide: it repaints in whole frames and
        // leaves the last one behind, so a 300ms slide arrives as a stack
        // of smeared bars. Snapping it into place is the same gesture,
        // drawn once.
        val chromeAnim = if (LocalEInk.current) 0 else CHROME_ANIM_MS
        AnimatedVisibility(
            visible = chromeVisible && !showingEnd,
            enter = slideInVertically(tween(chromeAnim)) { -it } + fadeIn(tween(chromeAnim)),
            exit = slideOutVertically(tween(chromeAnim)) { -it } + fadeOut(tween(chromeAnim)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = publication.metadata.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searchFor = "" }) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.reader_search),
                        )
                    }
                    // "Aa" is what a reader looks for and what every other
                    // reading app draws here, so it stays — but it is a
                    // picture of two letters, not a word, and TalkBack
                    // announcing "Aa" describes nothing. The button says
                    // what it opens instead.
                    val typographyLabel = stringResource(R.string.reader_typography)
                    IconButton(
                        onClick = { showTypography = true },
                        modifier = Modifier.semantics {
                            contentDescription = typographyLabel
                        },
                    ) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    IconButton(onClick = { showToc = true }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.List,
                            contentDescription = stringResource(R.string.reader_contents),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = readingTheme.background,
                    titleContentColor = readingTheme.foreground,
                    navigationIconContentColor = readingTheme.foreground,
                    actionIconContentColor = readingTheme.foreground,
                ),
            )
        }

        // Last inside the box, and so on top of everything in it: a note is
        // the thing the reader just asked for, and the page, the pills and
        // the chrome are all what they asked to be shown it over.
        footnote?.let { note ->
            FootnoteCard(
                html = note.html,
                theme = readingTheme,
                anchorY = lastTouchY,
                onGoToNote = {
                    onDismissFootnote()
                    onProgressAction.onJump()
                    navigator?.go(note.link, animated = false)
                },
                onDismiss = onDismissFootnote,
            )
        }
    }

    // Letting a selection go from our own bar rather than from the page.
    //
    // clearSelection() only asks the web view; the action mode is destroyed
    // a beat later, and the CLEARED that its destruction sends arrives
    // later still. A read started before the tap would land in that gap and
    // put the bar back up over a passage the reader has already dealt with,
    // so the same event is raised here first, where it cancels that read
    // instead of racing it.
    fun dismissSelection() {
        onSelectionDismissed()
        navigator?.clearSelection()
        selection = null
    }

    selection?.let { active ->
        SelectionPopup(
            offset = active.popupOffset(),
            activeTint = active.existing?.tint?.let(HighlightTint::fromName),
            actions = remember(active, dictionary) {
                SelectionActions(
                    onHighlight = { tint ->
                        onAnnotationAction.highlight(active.locator, tint, active.existing?.id)
                        dismissSelection()
                    },
                    onNote = {
                        noteFor = active
                        dismissSelection()
                    },
                    onSearch = {
                        searchFor = active.text
                        dismissSelection()
                    },
                    onLookUp = {
                        when (dictionary.target) {
                            DefinitionTarget.BUILT_IN -> defineWord = active.text
                            DefinitionTarget.EXTERNAL_APP -> {
                                context.lookUpExternally(active.text, dictionary.baseUrl)
                            }
                        }
                        dismissSelection()
                    },
                    onShare = {
                        context.shareText(active.text, publication.metadata.title)
                        dismissSelection()
                    },
                    onDelete = active.existing?.let { existing ->
                        {
                            onAnnotationAction.remove(existing)
                            dismissSelection()
                        }
                    },
                )
            },
            onDismiss = {
                // The bar and the selection go together: leaving the page
                // selected keeps the platform's handles alive and drawing
                // over words the reader has finished with.
                dismissSelection()
            },
        )
    }

    defineWord?.let { word ->
        DefinitionSheet(
            word = word,
            languages = remember(publication) {
                WiktionaryClient.languagesFor(publication.metadata.languages.firstOrNull())
            },
            enabled = dictionary.enabled,
            baseUrl = dictionary.baseUrl,
            onEnable = onEnableDictionary,
            onOpenInDictionaryApp = {
                context.lookUpExternally(it, dictionary.baseUrl)
                defineWord = null
            },
            onOpenInBrowser = {
                context.openDictionaryEntry(it, dictionary.baseUrl)
                defineWord = null
            },
            onDismiss = { defineWord = null },
        )
    }

    noteFor?.let { active ->
        NoteDialog(
            passage = active.text,
            initialNote = active.existing?.note.orEmpty(),
            onSave = { note ->
                onAnnotationAction.addNote(active.locator, note, active.existing?.id)
                noteFor = null
            },
            onDismiss = { noteFor = null },
        )
    }

    if (showTypography) {
        TypographySheet(
            prefs = prefs,
            readingTheme = readingTheme,
            typographyIsOwn = typographyIsOwn,
            onTypographyIsOwnChanged = onPrefsAction.setTypographyIsOwn,
            onFontSelected = onPrefsAction.setFont,
            onFontSizeChanged = onPrefsAction.setFontSize,
            onThemeSelected = onPrefsAction.setTheme,
            onLineHeightChanged = onPrefsAction.setLineHeight,
            onPageMarginsChanged = onPrefsAction.setPageMargins,
            onBrightnessChanged = onPrefsAction.setBrightness,
            onPageTurnAnimationChanged = onPrefsAction.setPageTurnAnimation,
            onFooterModeChanged = onProgressAction.setFooterMode,
            onColumnModeChanged = onPrefsAction.setColumnMode,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChanged = onKeepScreenOnChanged,
            scrollMode = scrollMode,
            onScrollModeChanged = onScrollModeChanged,
            onDismiss = { showTypography = false },
        )
    }

    BackHandler(enabled = showingEnd) {
        showingEnd = false
        onLeftEndpaper()
    }

    searchFor?.let { initial ->
        BackHandler {
            searchFor = null
            searchHit = null
            onSearchAction.clear()
        }
        SearchScreen(
            state = searchState,
            theme = readingTheme,
            initialQuery = initial,
            onSearch = onSearchAction.search,
            onHitSelected = { hit ->
                searchFor = null
                searchHit = hit
                chromeVisible = false
                onProgressAction.onJump()
                navigateLater(hit, NavigatorPositionEvent.LOCAL_JUMP)
            },
            onClose = {
                searchFor = null
                searchHit = null
                onSearchAction.clear()
            },
        )
    }

    // Taking the other device's position moves the reader there, which is
    // a jump like any other: the way back stays one tap away. The way back
    // is recorded before the move, so it is not done again here.
    LaunchedEffect(goTo) {
        goTo.collect { locator ->
            showingEnd = false
            onLeftEndpaper()
            showToc = false
            chromeVisible = false
            navigatorNow?.let { nav ->
                navigate(
                    nav = nav,
                    locator = locator,
                    event = NavigatorPositionEvent.REMOTE_ADOPTION,
                    verify = true,
                )
            }
        }
    }

    if (showToc) {
        BackHandler { showToc = false }
        val syncable by syncableFlow.collectAsStateWithLifecycle()
        val here = navigator?.currentLocator?.collectAsStateWithLifecycle()
        ContentsScreen(
            publication = publication,
            theme = readingTheme,
            currentHref = here?.value?.href?.toString(),
            annotations = annotations,
            onAnnotationSelected = { annotation ->
                annotation.locator()?.let {
                    showToc = false
                    chromeVisible = false
                    onProgressAction.onJump()
                    navigateLater(it, NavigatorPositionEvent.LOCAL_JUMP)
                }
            },
            onAnnotationDeleted = onAnnotationAction.remove,
            onExport = {
                context.shareText(
                    onAnnotationAction.notebookMarkdown(),
                    publication.metadata.title,
                )
            },
            onClose = { showToc = false },
            onSyncBook = if (syncable) onBookSyncAction.start else null,
            onEntrySelected = { link ->
                showToc = false
                chromeVisible = false
                publication.locatorFromLink(link)?.let {
                    onProgressAction.onJump()
                    navigateLater(it, NavigatorPositionEvent.LOCAL_JUMP)
                }
            },
        )
    }
}

/** A passage the reader has just selected, and any mark already on it. */
private data class ActiveSelection(
    val locator: Locator,
    val rect: RectF?,
    val existing: BookAnnotation?,
) {
    val text: String get() = locator.text.highlight.orEmpty()

    /**
     * Where to put the action bar: above the selection when it fits, below
     * it otherwise, so the words being acted on are never covered.
     */
    fun popupOffset(): IntOffset {
        val r = rect ?: return IntOffset(0, 0)
        val above = r.top - POPUP_HEIGHT_PX
        val y = if (above > 0) above else r.bottom + POPUP_GAP_PX
        return IntOffset(0, y.toInt())
    }
}

/** Decorations for search hits live apart from the reader's own marks. */
private const val SEARCH_DECORATION_GROUP = "search"
private val SEARCH_HIT_TINT = Color(0xFF80CBC4)

private const val POPUP_HEIGHT_PX = 160f
private const val POPUP_GAP_PX = 24f

/** Bundle of in-book search actions passed down to the reader chrome. */
class ReaderSearchActions(
    val search: (String) -> Unit,
    val clear: () -> Unit,
)

/** Bundle of annotation actions passed down to the reader chrome. */
class ReaderAnnotationActions(
    val highlight: (Locator, HighlightTint, String?) -> Unit,
    val addNote: (Locator, String, String?) -> Unit,
    val annotationAt: (Locator) -> BookAnnotation?,
    val toggleBookmark: () -> Unit,
    val remove: (BookAnnotation) -> Unit,
    val notebookMarkdown: () -> String,
)

/** Bundle of preference setters passed down to the reader chrome. */
class ReaderPrefsActions(
    val setFont: (ReaderFont) -> Unit,
    val setFontSize: (Double) -> Unit,
    val setTheme: (ReaderThemeChoice) -> Unit,
    val setLineHeight: (Double?) -> Unit,
    val setPageMargins: (Double?) -> Unit,
    val setBrightness: (Float?) -> Unit,
    val setPageTurnAnimation: (Boolean) -> Unit,
    val setColumnMode: (ColumnMode) -> Unit,
    val setTypographyIsOwn: (Boolean) -> Unit,
)

/** Bundle of progress and navigation actions for the reader chrome. */
class ReaderProgressActions(
    val cycleFooterMode: () -> Unit,
    val setFooterMode: (FooterMode) -> Unit,
    val onJump: () -> Unit,
    val dismissJumpBack: () -> Unit,
    val acceptCatchUp: () -> Unit,
    val dismissCatchUp: () -> Unit,
    val chapterTicks: () -> List<Float>,
    val chapterTitleAtPosition: (Int) -> String?,
    val positionAtProgression: (Float) -> Int,
    val locatorAtPosition: (Int) -> Locator?,
    val locatorAtOrBeforeProgression: (Double) -> Locator?,
    val prepareLocator: (Locator) -> Locator,
    val onApproximateResume: () -> Unit,
    val currentLocator: () -> Locator?,
)

/** Syncing this one book on purpose, from the Navigate screen. */
class ReaderBookSyncActions(
    val start: () -> Unit,
)

/** Hides the status and navigation bars while the chrome is hidden. */
@Composable
private fun ImmersiveMode(hideSystemBars: Boolean) {
    val view = LocalView.current
    LaunchedEffect(hideSystemBars) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideSystemBars) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Applies the brightness override to the reader window; null follows the system. */
@Composable
private fun ScreenBrightness(brightness: Float?) {
    val view = LocalView.current
    DisposableEffect(brightness) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.attributes = window.attributes.apply {
                screenBrightness = brightness ?: -1f
            }
        }
        onDispose {
            if (window != null) {
                window.attributes = window.attributes.apply { screenBrightness = -1f }
            }
        }
    }
}

/**
 * Keeps the reader window awake while [enabled].
 *
 * A window flag rather than a wake lock: the platform drops it whenever
 * the window is not in front, so nothing has to be released by hand when
 * the reader is backgrounded. Clearing it on dispose gives the device's
 * own screen timeout back the moment the reader is left.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
fun ReaderLoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        BusyIndicator()
    }
}

/**
 * A brief word that a book is on its way to the server.
 *
 * Only shown where the policy sends without asking. That path used to
 * be entirely silent, which is what made a book failing to arrive so
 * hard to notice — the reader had no reason to think anything had been
 * attempted at all.
 *
 * It leaves on its own because it asks nothing. The offer that does ask
 * is a dialog, and waits.
 */
@Composable
fun SendingNote(
    title: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(title) {
        if (title != null) {
            delay(SENDING_NOTE_MS)
            onDone()
        }
    }
    AnimatedVisibility(
        visible = title != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Snackbar(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.upload_sending, title.orEmpty()))
        }
    }
}

private const val SENDING_NOTE_MS = 4_000L

@Composable
fun ReaderErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.reader_open_failed),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.reader_back),
            )
        }
    }
}

/**
 * Stops the window insets at Readium's own view.
 *
 * Readium lays its web view out inside a `CoordinatorLayout`, which offsets
 * its children by whatever insets reach it. Compose does not consume insets
 * on behalf of the views it hosts, so the status bar's height is added under
 * the page: the web view ends up pushed down by that much, hanging as far
 * past the bottom of the pager that clips it. Readium paginates against the
 * web view's full height, so the text in that hidden strip — a line or two
 * of every page — is laid out and then never drawn, and the reader simply
 * loses it. The page is meant to run the whole screen, so nothing gets to
 * move it.
 */
private fun consumeInsetsForReadium(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, _ -> WindowInsetsCompat.CONSUMED }
    ViewCompat.requestApplyInsets(view)
}
