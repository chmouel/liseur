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
import android.os.SystemClock
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
import com.chmouel.liseur.reader.chrome.AdvancedSheet
import com.chmouel.liseur.reader.chrome.AutoScrollSpeed
import com.chmouel.liseur.reader.chrome.AutoScrollTicker
import com.chmouel.liseur.reader.chrome.JumpBackPill
import com.chmouel.liseur.reader.chrome.PageTurnEffectState
import com.chmouel.liseur.reader.chrome.PageTurnOverlay
import com.chmouel.liseur.reader.chrome.PageTurner
import com.chmouel.liseur.reader.chrome.ReaderTapZones
import com.chmouel.liseur.reader.chrome.ReadingFooter
import com.chmouel.liseur.reader.chrome.FooterMetrics
import com.chmouel.liseur.reader.chrome.HeldPlace
import com.chmouel.liseur.reader.chrome.ScrollEdgeTurner
import com.chmouel.liseur.reader.chrome.visibleWebView
import com.chmouel.liseur.reader.chrome.visibleWebViewCache
import com.chmouel.liseur.reader.chrome.layoutPasses
import com.chmouel.liseur.reader.chrome.ReadingScrubber
import com.chmouel.liseur.reader.chrome.ContentsScreen
import com.chmouel.liseur.reader.chrome.Endpaper
import com.chmouel.liseur.reader.chrome.FootnoteCard
import com.chmouel.liseur.reader.chrome.TypographySheet
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.reader.progress.OpeningRestoration
import com.chmouel.liseur.reader.progress.OpeningRestorationVerdict
import com.chmouel.liseur.reader.progress.RestorePoint
import com.chmouel.liseur.reader.progress.ScrollProgression
import com.chmouel.liseur.reader.search.SearchScreen
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.eink.EInkDisplay
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.widthClass
import com.chmouel.liseur.ui.windowWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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

// How long auto-scroll waits for the next chapter to actually arrive
// after asking for it. Generous, because a large chapter on a slow
// device takes a moment, and bounded, because a page that cannot be
// stopped is worse than a page that stops early.
private const val CHAPTER_ARRIVAL_MS = 5_000L

// The look the last lines of a chapter get before the page moves on,
// however fast or slow the reader has it set.
private const val MIN_CHAPTER_DWELL_MS = 400L
private const val MAX_CHAPTER_DWELL_MS = 8_000L

// How often a self-scrolling page writes down where it has got to. It
// is what stands between a reader who backgrounds the app mid-scroll
// and a place a paragraph or two behind them; short enough that the
// loss is a line, long enough that a document round trip every so often
// costs nothing.
private const val AUTO_SCROLL_SAVE_NANOS = 2_000_000_000L

// How often a book scrolled by hand is looked in on, and so the most a
// place kept for the pause can be behind the reader. The same interval
// auto-scroll writes at, for the same reason: it is a line or two of
// loss against a document round trip, and only a page that moved since
// the last look is worth one.
private const val SCROLL_PLACE_POLL_MS = 2_000L

/**
 * Which sheet is open over the page, if any.
 *
 * One value rather than a flag each, because they are not independent:
 * Advanced is reached *from* typography and closes back to it, and two
 * modal sheets composed at once is a state the reader can neither see
 * nor get out of cleanly.
 */
private enum class ReaderSheet { NONE, TYPOGRAPHY, ADVANCED }

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalReadiumApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ReaderScreen(
    publication: Publication,
    /**
     * Where this navigator is being reopened to, snapshotted when it was
     * built. Null for a book with no saved position.
     */
    restoreTarget: Locator?,
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
    // The activity draws dialogs of its own over this screen — a link
    // out of the book, a sync, an offer to send the book up — and a tap
    // on a link never reaches the tap zones, so the screen would
    // otherwise carry on scrolling behind one.
    blockedByDialog: Boolean = false,
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
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }
    val chromeVisibleNow by rememberUpdatedState(chromeVisible)
    val prefs by prefsFlow.collectAsStateWithLifecycle()
    val keepScreenOn by keepScreenOnFlow.collectAsStateWithLifecycle()
    val scrollMode by scrollModeFlow.collectAsStateWithLifecycle()
    // Readium reads vertical text off the book rather than off the
    // reader: it cannot paginate lines that run down the page, so such a
    // book is scrolled whatever the setting says. Everything that asks
    // "is this book scrolled" has to ask it this way, or a vertical book
    // gets tap zones that turn pages it has not got.
    var verticalText by remember { mutableStateOf(false) }
    val effectiveScrolling = scrollMode || verticalText
    val effectiveScrollingNow by rememberUpdatedState(effectiveScrolling)
    var autoScrollArmed by remember { mutableStateOf(false) }
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
    var fingerDown by remember { mutableStateOf(false) }
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

    // Closed while the book is still being reopened. One per navigator,
    // because a column or scroll mode change builds a new one and it
    // restores again. See OpeningRestoration.
    val gate = remember(navigator) {
        OpeningRestoration(
            target = restoreTarget?.restorePoint(exact = ExactLocatorAnchor.isExact(restoreTarget)),
            timeoutMs = OpeningRestoration.DEFAULT_TIMEOUT_MS,
        )
    }
    val gateOpenedAt = remember(navigator) { SystemClock.elapsedRealtime() }

    // The deadline runs on a clock rather than on emissions: a navigator
    // that falls silent while gated would otherwise never be released,
    // and a reading session would quietly stop being saved.
    LaunchedEffect(navigator) {
        delay(OpeningRestoration.DEFAULT_TIMEOUT_MS)
        gate.onDeadline()
    }

    // Open for as long as the page is being rebuilt underneath the reader.
    // See ReflowScope: a locator nobody has claimed while this is open is the
    // layout moving, not the reader.
    val reflow = remember { ReflowScope() }

    // A fixed-layout book places everything by absolute coordinates: nothing
    // reflows, so measuring what "fits" means nothing, and the document does
    // not move under the viewport, so a fraction of its length says nothing
    // about where the reader is either.
    val reflowableText = remember(publication) {
        publication.metadata.layout != Layout.FIXED
    }

    // The place the reader was at when a run of preference changes began.
    // A restore that lands slightly off must not become the anchor of the
    // next change, or each nudge of a slider walks the position a little
    // further from the page being read; the anchor is held until the
    // reader actually moves, and every reflow restores to the same spot.
    var reflowAnchor by remember { mutableStateOf<Locator?>(null) }

    // The place a scrolled book was last measured at, kept for the
    // moment the reader leaves. Everything that measures one and
    // everything that supersedes one goes through here; see [HeldPlace].
    val heldPlace = remember { HeldPlace() }

    suspend fun capture(nav: EpubNavigatorFragment, locator: Locator): Locator =
        onProgressAction.prepareLocator(ExactLocatorAnchor.capture(nav, locator))

    /**
     * Where a scrolled chapter has got to, as a locator worth saving.
     *
     * Readium's own answer names a place — a selector and the words at
     * the top of the screen — but no distance: `findFirstVisibleLocator`
     * carries neither a progression nor a position, and everything
     * downstream reads a locator without one as the start of its
     * resource. So the distance is measured here, in the terms
     * `ScrollProgression` explains, and a place that cannot be given one
     * is not saved at all: a tick's delay costs the reader a line, a
     * number that is wrong costs them the chapter.
     *
     * The distance and the anchor are asked for back to back, because
     * those are the two that have to agree; the answer Readium gives
     * first is only used for the resource it names, which is the one
     * actually on screen and may be ahead of the one it has published.
     * Everything the navigator last published is dropped rather than
     * carried: its position and its words belong to wherever it last
     * stopped, and the capture is about to supply the ones true now.
     *
     * Every step is a question put to the document, and the reader may
     * have left the chapter while it was answering. A place dropped
     * costs a tick and no more; a place kept would file the old
     * chapter's as the reader's place in the new one. A page being
     * rebuilt is not a page the reader has moved through at all, so the
     * question is not put to it while a reflow is open.
     */
    suspend fun scrolledPlace(nav: EpubNavigatorFragment): Locator? {
        if (!reflowableText || reflow.active) return null
        val asking = nav.currentLocator.value.href
        val displayed = try {
            nav.firstVisibleElementLocator()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        val progression = ScrollProgression
            .of(nav, vertical = nav.settings.value.verticalText)
            ?: return null
        val measured = displayed.copy(
            locations = Locator.Locations(progression = progression),
            text = Locator.Text(),
        )
        val captured = capture(nav, measured)
        if (captured.href != asking || nav.currentLocator.value.href != asking) return null
        // The capture reaches into the document too, and swallows its
        // own failures to do it. Cancellation is not a failure to
        // swallow: a place written down after the loop that asked for it
        // has gone is a place nobody is standing in.
        currentCoroutineContext().ensureActive()
        return captured
    }

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
        // If the book is still opening, this supersedes the restoration
        // — but only once the reader is actually there. The go below is
        // asynchronous and the marker set with it is single use, so an
        // emission still in flight from the pre-restore position would
        // otherwise take that marker and be saved as the move.
        gate.onNavigationIssued(locator.restorePoint())
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
        gate.onNavigationIssued(fallback.restorePoint())
        pendingPositionEvent = event
        nav.go(fallback, animated = false)
        onProgressAction.onApproximateResume()
    }

    suspend fun openingExactAnchorArrived(nav: EpubNavigatorFragment, locator: Locator): Boolean {
        val elapsedMs = SystemClock.elapsedRealtime() - gateOpenedAt
        val budgetMs = OpeningRestoration.exactOpenVerifyBudgetMs(elapsedMs)
        // A budget already spent still buys one look. Two frames cost
        // nothing against the gate's remaining second, and degrading the
        // reader to an approximate page without ever asking whether the
        // exact one was there would be worse than the single attempt
        // this replaced.
        if (budgetMs <= 0L) {
            settleLayout()
            return ExactLocatorAnchor.verify(nav, locator)
        }
        // This is the same asynchronous WebView race as navigate(), but
        // a cold open is the slowest layout the reader asks for. Poll
        // for a bounded stretch before degrading to the approximate
        // locator. The budget helper keeps this below the opening
        // gate's fail-open deadline: with the current constants it caps
        // polling at 3000ms and never past 4000ms from gate creation,
        // leaving at least 1000ms of the 5000ms gate for the fallback
        // navigation to be issued while the gate is still closed.
        return withTimeoutOrNull(budgetMs) {
            repeat(OpeningRestoration.EXACT_OPEN_VERIFY_ATTEMPTS) {
                settleLayout()
                if (ExactLocatorAnchor.verify(nav, locator)) return@withTimeoutOrNull true
            }
            false
        } == true
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
            // Vertical text is scrolled whatever the setting says, so
            // this is the derived answer and not the preference: a
            // sideways-scrolled book asked to turn a page would be asked
            // for a page it has not got.
            isScrolling = { effectiveScrollingNow },
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
                val requested = pendingPositionEvent
                pendingPositionEvent = null
                // Only paid for while the gate is closed, which is a
                // handful of emissions at the start of a session.
                val suppressed = gate.isGated && gate.onEmission(
                    here = native.restorePoint(),
                    anchorVerified = restoreTarget != null &&
                        ExactLocatorAnchor.isExact(restoreTarget) &&
                        ExactLocatorAnchor.verify(nav, restoreTarget),
                    elapsedMs = SystemClock.elapsedRealtime() - gateOpenedAt,
                ) == OpeningRestorationVerdict.SUPPRESS
                val event = when {
                    // The navigator reporting where it was before it
                    // finished being told where to go. Persisting that
                    // sends the reader's other devices to the top of the
                    // chapter.
                    suppressed -> NavigatorPositionEvent.OPENING_RESTORATION
                    requested != null -> requested
                    reflow.active -> NavigatorPositionEvent.PREFERENCE_REFLOW
                    else -> NavigatorPositionEvent.READER_MOVEMENT
                }
                // Anywhere the reader actually goes ends the run of
                // preference changes the held anchor was covering.
                if (event != NavigatorPositionEvent.PREFERENCE_REFLOW) reflowAnchor = null
                // This is fresher than anything a scroll watcher had in
                // flight, so those answers are refused from here on. The
                // place already held stands until this one is taken:
                // the capture below suspends, and a reader who leaves
                // while it is being answered would otherwise be left
                // with neither.
                val since = heldPlace.invalidate()
                val captured = capture(nav, native)
                // The capture reaches into the document and swallows its
                // own failures, cancellation included, so an answer can
                // arrive after this collector has been stood down — a
                // navigator replaced underneath it, or the reader gone.
                // Neither a place held nor a position saved from that is
                // anyone's.
                currentCoroutineContext().ensureActive()
                // A reflow locator is the layout moving, not the reader,
                // and it is not a jump anyone should be sent back to on
                // the way out. Its arrival still retires what was held,
                // which was measured against a page that no longer
                // exists in that shape.
                if (event == NavigatorPositionEvent.PREFERENCE_REFLOW) {
                    heldPlace.retire()
                } else {
                    heldPlace.hold(captured, since)
                }
                onLocatorChanged(captured, event)
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
        // The snapshot this navigator was built with, not
        // viewModel.lastLocator: onLocatorChanged assigns that before it
        // decides whether a position persists, so the navigator's own
        // opening emissions move it out from under this check.
        val requested = restoreTarget ?: return@LaunchedEffect
        if (!ExactLocatorAnchor.isExact(requested)) return@LaunchedEffect
        if (openingExactAnchorArrived(nav, requested)) return@LaunchedEffect
        val progression = requested.locations.totalProgression ?: return@LaunchedEffect
        val fallback = onProgressAction.locatorAtOrBeforeProgression(progression)
            ?: return@LaunchedEffect
        pendingPositionEvent = NavigatorPositionEvent.FRAGMENT_RECREATION
        // Retarget rather than release. This navigation is asynchronous
        // and pendingPositionEvent is single-use, so an emission landing
        // in between takes the marker and the real arrival is left
        // looking like a page turn. Telling the gate where the reader is
        // now being sent keeps it closed until they get there.
        gate.onNavigationIssued(fallback.restorePoint())
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
            // What the book is rendered with, not what the reader
            // happens to be holding. Reading preferences carry answers
            // the page cannot see — the auto-scroll speed is one — and a
            // slider dragged through its notches would otherwise reflow
            // the whole book at every notch, each time capturing an
            // anchor and restoring it, for a setting that changes not one
            // line of the text.
            .distinctUntilChanged()
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
                    if (reflowableText &&
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
    LaunchedEffect(navigator, reflowableText) {
        val nav = navigator ?: return@LaunchedEffect
        if (!reflowableText) return@LaunchedEffect
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

    // Tracked rather than read: the setting arrives with the book, and
    // asking a nullable navigator for it from the composition would make
    // the read itself conditional.
    LaunchedEffect(navigator) {
        val nav = navigator
        if (nav == null) {
            verticalText = false
            return@LaunchedEffect
        }
        nav.settings.collect { verticalText = it.verticalText }
    }

    /*
     * The page carrying itself.
     *
     * One predicate decides whether it moves, and it is written here in
     * one place rather than scattered through the screen, because the
     * pause ADR 6 asks for *is* the predicate: a tap raises the chrome,
     * the chrome is in the list, the page stops. Touch it again, the
     * chrome goes, the page carries on. A drag is a finger down, so the
     * text goes where the reader puts it and picks up from there.
     *
     * Nothing else in the reader gains a control for this. An offer the
     * reader has not answered — a pill, a dialog — holds the page still
     * too: a decision taken about a page that has moved on is a decision
     * about nothing.
     */
    val canAutoScroll = autoScrollArmed &&
        effectiveScrolling &&
        !chromeVisible &&
        !fingerDown &&
        !showingEnd &&
        sheet == ReaderSheet.NONE &&
        !showToc &&
        searchFor == null &&
        footnote == null &&
        selection == null &&
        noteFor == null &&
        defineWord == null &&
        jumpBack == null &&
        catchUp == null &&
        !blockedByDialog

    // Arming is a decision taken in a sheet, and the page it applies to
    // is behind the sheet. Get out of the way so the reader can see what
    // they asked for.
    LaunchedEffect(autoScrollArmed) {
        if (autoScrollArmed) {
            sheet = ReaderSheet.NONE
            chromeVisible = false
        }
    }

    // A book that stops being scrolled has nothing to scroll. Switching
    // to pages while the text was moving would otherwise leave the
    // switch on with nothing behind it.
    LaunchedEffect(effectiveScrolling) {
        if (!effectiveScrolling) autoScrollArmed = false
    }

    /*
     * Saving the reader's place while the page never stops.
     *
     * Readium notices a scroll through the web view's own
     * `onScrollChanged` and answers it with a *debounced* location
     * notification — a hundred milliseconds of stillness. A finger drag
     * always ends, so that debounce always lands. A page carrying itself
     * never stops, so it never lands at all, and the reader's place
     * would stay wherever they last lifted a finger.
     *
     * So the loop asks for the location itself, on its own clock, with
     * `scrolledPlace` — the same question the debounce would have asked,
     * just asked on time, and answered with a distance the navigator's
     * own answer does not carry. It is asked off the frame loop so the
     * page does not hitch waiting for a round trip into the document.
     */
    val autoScrollRunning by rememberUpdatedState(canAutoScroll)
    val density = LocalDensity.current.density

    LaunchedEffect(
        navigator,
        canAutoScroll,
        prefs.autoScrollSpeed,
        prefs.fontSize,
        density,
        lifecycle,
    ) {
        if (!canAutoScroll) return@LaunchedEffect
        val nav = navigator ?: return@LaunchedEffect
        val root = nav.publicationView
        val ticker = AutoScrollTicker()
        val webViews = visibleWebViewCache(root)
        // The pace cannot change under the loop: every term of it is a
        // key of this effect, so a reader who moves the slider gets a
        // new loop rather than a new number in the old one. Working it
        // out sixty times a second to arrive at the same answer is the
        // sort of arithmetic a frame should not be spending itself on.
        val pixelsPerSecond = AutoScrollSpeed.dpPerSecond(
            step = prefs.autoScrollSpeed,
            fontSize = prefs.fontSize,
        ) * density
        // RESUMED and not merely STARTED: a reader looking at something
        // else on a split screen is not reading this, and the page they
        // come back to should be the page they left.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ticker.reset()
            var savedAtNanos = 0L
            var saving = false
            // The place kept for the pause outlives this loop, and a
            // book moved to another chapter while the reader was away
            // moved without anyone here to see it: the flow below only
            // starts watching now, and its first value is the state of
            // things rather than news. A locator from a chapter that is
            // no longer open is not the reader's place in this one.
            if (heldPlace.current()?.href != nav.currentLocator.value.href) {
                heldPlace.retire()
            }
            // Two events, told apart because they are not the same news.
            // Invalidating costs nothing and can be said too often —
            // the next frame simply looks the view up again — so it
            // rides both a new position and a layout pass, the pair
            // this screen already trusts elsewhere: Readium reports
            // arriving at a resource before laying it out, and a view
            // that does not exist yet cannot be found.
            launch {
                merge(nav.currentLocator.map { }, layoutPasses(root)).collect {
                    webViews.invalidate()
                }
            }
            // A chapter change, on the other hand, is rare and means
            // something: the carried fraction belongs to the page that
            // has gone, and so does the place kept for the pause.
            // Hanging that off every position — they arrive as the
            // reader moves within a chapter — would reset the pace
            // constantly and throw the pause position away for nothing.
            launch {
                nav.currentLocator
                    .map { it.href }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        ticker.reset()
                        savedAtNanos = 0L
                        heldPlace.retire()
                    }
            }
            while (true) {
                val now = withFrameNanos { it }
                val web = webViews.current() ?: continue
                val vertical = nav.settings.value.verticalText
                val pixels = ticker.step(nowNanos = now, pixelsPerSecond = pixelsPerSecond)
                if (pixels <= 0) continue
                if (!saving && now - savedAtNanos >= AUTO_SCROLL_SAVE_NANOS) {
                    savedAtNanos = now
                    saving = true
                    launch {
                        try {
                            val since = heldPlace.mark()
                            val captured = scrolledPlace(nav)
                            // One question decides both: a measurement
                            // the reader has already moved past is not
                            // worth keeping, and publishing it would
                            // carry them back to it.
                            if (captured != null && heldPlace.hold(captured, since)) {
                                // Auto-scroll is reading, so it teaches
                                // the pace estimator and counts as time
                                // spent, which READER_MOVEMENT is what
                                // carries.
                                onLocatorChanged(
                                    captured,
                                    NavigatorPositionEvent.READER_MOVEMENT,
                                )
                            }
                        } catch (e: CancellationException) {
                            // The loop is going down anyway, and a
                            // cancelled measurement is not an answer.
                            throw e
                        } catch (_: Exception) {
                            // A failed round trip must not take the loop
                            // down with it: this coroutine is the loop's
                            // child, and an exception here would cancel
                            // its parent and stop the page for good.
                        } finally {
                            saving = false
                        }
                    }
                }
                // Later text lies below in a book set in lines across,
                // and to the left in one set in lines down — the same
                // convention ScrollEdgeTurner reads its drags by.
                val atEnd = if (vertical) {
                    !web.canScrollHorizontally(-1)
                } else {
                    !web.canScrollVertically(1)
                }
                if (atEnd) {
                    // The last lines have only just arrived. Leave them
                    // on screen for as long as they took to get there.
                    val viewport = if (vertical) web.width else web.height
                    val moved = carryIntoNextChapter(
                        nav = nav,
                        pageTurner = pageTurner,
                        dwellMillis = dwellMillis(viewport, pixelsPerSecond),
                    )
                    ticker.reset()
                    savedAtNanos = 0L
                    heldPlace.retire()
                    webViews.invalidate()
                    if (!moved) {
                        autoScrollArmed = false
                        return@repeatOnLifecycle
                    }
                    continue
                }
                if (vertical) web.scrollBy(-pixels, 0) else web.scrollBy(0, pixels)
            }
        }
    }

    /*
     * The place a manually scrolled book has got to, kept for the pause.
     *
     * A page carried by a finger does stop, so Readium's debounce does
     * land — but `ReaderActivity` marks the reader inactive before the
     * fragment pauses, and a debounce still in the air when the reader
     * leaves is then dropped as movement nobody made. The last drag
     * would go with it.
     *
     * So the offset is watched as the reader scrolls and a place is kept
     * against it, ready to be published on the way out without asking
     * the document anything. Watching is the view's own scroll offset,
     * which costs a field read; the document is only asked once that
     * offset has moved, and no more often than auto-scroll asks it.
     *
     * An offset that is the same as it was a moment ago is a page at
     * rest, and a page at rest has already been answered for. So the
     * round trip is spent only on a page that moved within the window,
     * which is the only page whose place can still be in the air.
     *
     * It watches for as long as the book is scrolled, whether or not
     * auto-scroll is armed, because arming is not running: a finger on
     * the page, the chrome up, a dialog open — all of them stop the
     * carrying loop and leave the reader free to scroll by hand. One
     * place is held between the two, so whichever of them last measured
     * it is the one the pause publishes.
     */
    // A place belongs to the navigator that was asked for it and to the
    // book being scrolled when it was. A fragment recreated underneath
    // the reader, or a book switched to pages, leaves one behind that
    // names a document nobody is looking at — and the holder outlives
    // both, so it has to be told. `onDispose` runs as the keys change,
    // before anything new can hold against the old generation.
    DisposableEffect(navigator, effectiveScrolling) {
        onDispose { heldPlace.retire() }
    }

    LaunchedEffect(navigator, effectiveScrolling, lifecycle) {
        val nav = navigator ?: return@LaunchedEffect
        if (!effectiveScrolling) return@LaunchedEffect
        val root = nav.publicationView
        val webViews = visibleWebViewCache(root)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                merge(nav.currentLocator.map { }, layoutPasses(root)).collect {
                    webViews.invalidate()
                }
            }
            var lastOffset: Int? = null
            while (true) {
                val web = webViews.current()
                val offset = web?.let {
                    if (nav.settings.value.verticalText) it.scrollX else it.scrollY
                }
                val moved = offset != null && lastOffset != null && offset != lastOffset
                // The baseline is taken before the first wait, not after
                // it, or the first window of a chapter is one nobody was
                // watching — and the window a reader opens a book in is
                // exactly the one they scroll in.
                if (offset != null) lastOffset = offset
                // Auto-scroll keeps the same place on its own clock, and
                // it is the one moving the page: asking twice would be
                // two round trips for one answer. The offset is still
                // read, so that whatever it has reached while the loop
                // ran is the baseline the moment it stops.
                if (moved && !autoScrollRunning) {
                    // A place that cannot be had leaves the last one
                    // standing: it is behind the reader by a tick, where
                    // the alternative is nothing at all.
                    val since = heldPlace.mark()
                    val captured = try {
                        scrolledPlace(nav)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (captured != null) heldPlace.hold(captured, since)
                }
                delay(SCROLL_PLACE_POLL_MS)
            }
        }
    }

    /*
     * The last place before the reader leaves.
     *
     * `ReaderViewModel` drops a READER_MOVEMENT locator once the reader
     * is inactive, and `ReaderActivity.onPause` clears that flag before
     * `super.onPause()` — so nothing published from an ON_PAUSE observer
     * can arrive as movement, and Readium's own debounce, landing a
     * hundred milliseconds later, is dropped as well. What goes in here
     * is the place a scrolled book was last measured at — carried by the
     * loop or by the reader's own finger — republished as the jump it
     * effectively was: LOCAL_JUMP persists, is not dropped by that
     * guard, and does not count the same reading twice.
     *
     * Nothing is asked of the page and nothing is waited for, so this is
     * an ordinary synchronous call inside `onPause`. `onStop` — and with
     * it the closing of the position queue — cannot begin until
     * `onPause` has returned, so the position is always in the queue
     * before there is any question of the queue being shut.
     */
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_PAUSE) return@LifecycleEventObserver
            heldPlace.current()?.let {
                onLocatorChanged(it, NavigatorPositionEvent.LOCAL_JUMP)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
                    isScrolling = { effectiveScrollingNow },
                    onTurnPage = pageTurner::turn,
                    onShowChrome = { chromeVisible = true },
                    onHideChrome = { chromeVisible = false },
                ),
                ScrollEdgeTurner(
                    navigator = it,
                    isScrolling = { effectiveScrollingNow },
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
    // Auto-scroll implies it: a page that carries itself past a screen
    // timeout is a page nobody is reading. The reader's own switch still
    // decides everything else, and this adds nothing once the page stops.
    KeepScreenOn(enabled = keepScreenOn || autoScrollArmed)

    Box(
        Modifier
            .fillMaxSize()
            .background(readingTheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // A finger on the page is a finger on the page,
                        // whatever it is doing there. Auto-scroll waits
                        // for it to be lifted, so a reader nudging the
                        // text is not fighting the page for it.
                        fingerDown = event.changes.any { it.pressed }
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
        // instead of the bars: the footer's own derived height
        // (FooterMetrics), which follows the system font scale the way
        // the fixed count of dp it replaces could not. The navigation
        // bar is not in that figure — the footer is only drawn while
        // the chrome is hidden, which is the very thing that hides the
        // bars, so reserving the bar's inset only left a blank band
        // under the footer, a finger deep on three-button navigation.
        // The reservation does not move when the chrome shows or hides,
        // so toggling the chrome still cannot reflow the page.
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
            // footer band goes back to the text, and the one reflow
            // that costs is the settings change itself.
            //
            // A vertical-text book draws no footer either, but it keeps
            // the band: whether the text is vertical is only known once
            // the navigator has read the publication, and taking the
            // band back then would reflow the book under the reader on
            // open. An unused 38dp is the cheaper of the two.
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
                    // A scrolled page moves under the reader's thumb
                    // without Readium saying so: its current locator is
                    // debounced, and between two of those the view
                    // model's idea of the place can be a screen or more
                    // behind what is on the page. A bookmark is taken at
                    // the moment the ribbon is tapped and has to mean
                    // that moment, so the document is asked where it is
                    // now — the same question the reader leaving the
                    // book already asks — and only then is the mark
                    // made. It also decides which mark is being toggled,
                    // so asking first is what stops a tap meant as a new
                    // bookmark deleting the one behind it.
                    //
                    // Only a scrolled page is asked. A book set in pages
                    // publishes its place on the turn, long before a
                    // finger can reach the ribbon, and it does not
                    // scroll: `scrolledPlace` would read an offset of
                    // nothing over a screenful and file the reader at
                    // the top of the chapter — the very thing this is
                    // here to prevent. Every other caller guards on the
                    // same flag.
                    effectScope.launch {
                        if (effectiveScrollingNow) {
                            navigatorNow?.let { nav ->
                                scrolledPlace(nav)?.let {
                                    onLocatorChanged(it, NavigatorPositionEvent.LOCAL_JUMP)
                                }
                            }
                        }
                        onAnnotationAction.toggleBookmark()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp),
            )
        }

        // The bars come and go with the chrome, so this corner takes the
        // live navigation-bar inset: it lifts the scrubber and the pills
        // clear of a bar that is actually there, and asks for nothing
        // when there is none. Reading it ignoring visibility parked
        // everything a bar's height up an empty screen.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding(),
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
                }
            }
        }

        // The footer sits outside that column, on the screen's edge and
        // no inset at all, because it is the one piece of chrome drawn
        // while the bars are hidden. Riding the inset would have it
        // slide down as the bars animate away — a slide the page cannot
        // follow, since the page's reservation is a constant, and one
        // e-paper repaints as a smear. It never shares the corner: the
        // pills displace it rather than stack on it.
        //
        // Two states put a bar back over it, and both are accepted. A
        // bar swiped out transiently is drawn over the page without
        // changing an inset, so nothing could dodge it anyway, and it
        // takes itself away again. A window that is not allowed to hide
        // its bars at all — split screen — covers the footer for good;
        // the figures are lost, which is the graceful half of that
        // trade, where floating the footer up a bar it cannot predict
        // would print it through the page's last line instead.
        //
        // The guard is effectiveScrolling, not the reader's scrolling
        // preference: a vertical-text book scrolls whatever the setting
        // says, and a scrolled page runs under this corner, so a footer
        // left drawn there prints itself over the text. The figures are
        // one tap away with the rest of the chrome.
        if (!showingEnd && !chromeVisible && !effectiveScrolling &&
            jumpBack == null && catchUp == null
        ) {
            ReadingFooter(
                progress = progress,
                mode = prefs.footerMode,
                theme = readingTheme,
                onCycleMode = onProgressAction.cycleFooterMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FooterMetrics.BOTTOM_MARGIN_DP.dp),
            )
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
                        onClick = { sheet = ReaderSheet.TYPOGRAPHY },
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

    if (sheet == ReaderSheet.TYPOGRAPHY) {
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
            // Nothing lives behind Advanced yet but auto-scroll, and
            // auto-scroll is only offered to a scrolled book. A paginated
            // one would open an empty sheet, so it is not offered the way
            // in either.
            advancedOffered = effectiveScrolling,
            onOpenAdvanced = { sheet = ReaderSheet.ADVANCED },
            onDismiss = { sheet = ReaderSheet.NONE },
        )
    }

    if (sheet == ReaderSheet.ADVANCED) {
        AdvancedSheet(
            autoScrollOffered = effectiveScrolling,
            autoScrolling = autoScrollArmed,
            autoScrollSpeed = prefs.autoScrollSpeed,
            onAutoScrollChanged = { autoScrollArmed = it },
            onAutoScrollSpeedChanged = onPrefsAction.setAutoScrollSpeed,
            // Back to typography, not back to the book: this sheet was
            // reached from there, and that is where the reader left off.
            onDismiss = { sheet = ReaderSheet.TYPOGRAPHY },
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

/**
 * Opens the next chapter and waits for it to arrive, or says it could
 * not.
 *
 * Reaching the bottom of a chapter is not the moment to leave it: the
 * last lines have only just come into view, and a reader who has been
 * carried down to them still has to read them. So the page waits there
 * for as long as those lines took to arrive — [dwellMillis], one
 * viewport at the pace the reader chose — before turning.
 *
 * `stepChapter` answers false when it did not move: the endpaper, the
 * end of the book, a reading order it could not place. The page has
 * nowhere to carry itself to, and says so, rather than sitting against
 * the edge waiting for a chapter that is not coming.
 *
 * The wait afterwards is for the resource to actually change, not for a
 * fixed delay: too short and the next chapter is stepped through before
 * it has laid out, taking the reader two chapters on; too long and a
 * chapter shorter than a screen is read as a stall. It is bounded all
 * the same, because a wait that can never end is a page that can never
 * be stopped.
 *
 * The dwell is the one window in which someone else can turn the page
 * first. A volume or page key goes straight to the turner without the
 * chrome coming up, so auto-scroll stays armed and this call stays
 * alive; stepping again afterwards would carry the reader a chapter
 * further than they asked for. If the chapter changed while we waited,
 * the page moved — which is what the wait was there to allow — and the
 * loop carries on wherever the reader now is.
 */
private suspend fun carryIntoNextChapter(
    nav: EpubNavigatorFragment,
    pageTurner: PageTurner,
    dwellMillis: Long,
): Boolean {
    val leaving = nav.currentLocator.value.href
    delay(dwellMillis)
    if (nav.currentLocator.value.href != leaving) return true
    if (!pageTurner.stepChapter(forward = true)) return false
    return withTimeoutOrNull(CHAPTER_ARRIVAL_MS) {
        nav.currentLocator.first { it.href != leaving }
        // Arriving is not the same as being laid out, and a chapter
        // scrolled before it has settled scrolls the wrong distance.
        withFrameNanos { }
        withFrameNanos { }
        true
    } == true
}

/**
 * How long to leave the last lines of a chapter on screen: the time it
 * took the reader to be carried one screen, at the pace they chose, so a
 * slow reader gets the long look and a skimming one is not held up.
 *
 * Bounded at both ends. A viewport the layout has not measured yet would
 * otherwise be no wait at all, and a very slow pace would hold a reader
 * against the edge long enough to wonder whether it had broken.
 */
private fun dwellMillis(viewportPixels: Int, pixelsPerSecond: Double): Long {
    if (viewportPixels <= 0 || pixelsPerSecond <= 0.0) return MIN_CHAPTER_DWELL_MS
    val millis = (viewportPixels / pixelsPerSecond * 1_000L).toLong()
    return millis.coerceIn(MIN_CHAPTER_DWELL_MS, MAX_CHAPTER_DWELL_MS)
}

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
    val setAutoScrollSpeed: (Float) -> Unit,
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

/**
 * A locator, in the terms the opening gate reasons about.
 *
 * Nothing here is Readium-shaped on purpose: the decision the gate makes
 * is worth testing on its own, without a navigator to ask.
 */
private fun Locator.restorePoint(exact: Boolean = false) = RestorePoint(
    href = href.toString(),
    progression = locations.totalProgression,
    exact = exact,
)
