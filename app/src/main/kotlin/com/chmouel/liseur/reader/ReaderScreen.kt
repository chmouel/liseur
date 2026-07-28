package com.chmouel.liseur.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.compose.AndroidFragment
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.reader.annotations.BookmarkRibbon
import com.chmouel.liseur.reader.annotations.DECORATION_GROUP
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.reader.annotations.NoteDialog
import com.chmouel.liseur.reader.annotations.SelectionActions
import com.chmouel.liseur.reader.annotations.SelectionPopup
import com.chmouel.liseur.reader.annotations.locator
import com.chmouel.liseur.reader.annotations.lookUpExternally
import com.chmouel.liseur.reader.annotations.openWiktionary
import com.chmouel.liseur.reader.annotations.shareText
import com.chmouel.liseur.reader.annotations.toDecorations
import com.chmouel.liseur.reader.dictionary.DefinitionSheet
import com.chmouel.liseur.reader.dictionary.WiktionaryClient
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.chrome.JumpBackPill
import com.chmouel.liseur.reader.chrome.PageTurnEffectState
import com.chmouel.liseur.reader.chrome.PageTurnOverlay
import com.chmouel.liseur.reader.chrome.PageTurner
import com.chmouel.liseur.reader.chrome.ReaderTapZones
import com.chmouel.liseur.reader.chrome.ReadingFooter
import com.chmouel.liseur.reader.chrome.ReadingScrubber
import com.chmouel.liseur.reader.chrome.ContentsScreen
import com.chmouel.liseur.reader.chrome.TypographySheet
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.search.SearchScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Duration of the gentle chrome show/hide animation. */
private const val CHROME_ANIM_MS = 300

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalReadiumApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ReaderScreen(
    publication: Publication,
    prefsFlow: StateFlow<ReaderPrefs>,
    progressFlow: StateFlow<ReaderProgress?>,
    jumpBackFlow: StateFlow<ReaderViewModel.JumpBack?>,
    onLocatorChanged: (Locator) -> Unit,
    onNavigatorChanged: (EpubNavigatorFragment?) -> Unit,
    onPageTurnerChanged: (PageTurner?) -> Unit,
    onPrefsAction: ReaderPrefsActions,
    onProgressAction: ReaderProgressActions,
    annotationsFlow: StateFlow<List<BookAnnotation>>,
    searchFlow: StateFlow<ReaderViewModel.SearchState>,
    bookmarkedFlow: StateFlow<Boolean>,
    selectionRequests: SharedFlow<Unit>,
    onAnnotationAction: ReaderAnnotationActions,
    onSearchAction: ReaderSearchActions,
    onBack: () -> Unit,
) {
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var chromeVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var searchFor by remember { mutableStateOf<String?>(null) }
    var searchHit by remember { mutableStateOf<Locator?>(null) }
    var showTypography by remember { mutableStateOf(false) }
    val chromeVisibleNow by rememberUpdatedState(chromeVisible)
    val prefs by prefsFlow.collectAsStateWithLifecycle()
    val progress by progressFlow.collectAsStateWithLifecycle()
    val jumpBack by jumpBackFlow.collectAsStateWithLifecycle()
    val annotations by annotationsFlow.collectAsStateWithLifecycle()
    val searchState by searchFlow.collectAsStateWithLifecycle()
    val bookmarked by bookmarkedFlow.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<ActiveSelection?>(null) }
    var noteFor by remember { mutableStateOf<ActiveSelection?>(null) }
    var defineWord by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    val context = LocalContext.current
    val effectScope = rememberCoroutineScope()
    val pageTurnEffect = remember { PageTurnEffectState(effectScope) }
    val pageTurner = remember {
        PageTurner(
            effect = pageTurnEffect,
            isAnimated = { prefsFlow.value.pageTurnAnimation },
            isEffectSuppressed = { chromeVisibleNow },
        )
    }

    LaunchedEffect(navigator) {
        onNavigatorChanged(navigator)
        // The navigator replays its current locator on subscription; only
        // subsequent changes need persisting.
        navigator?.currentLocator?.drop(1)?.collect(onLocatorChanged)
    }

    // Apply preference changes to the rendered book as they happen.
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        prefsFlow.collect { nav.submitPreferences(it.toEpubPreferences()) }
    }

    // Picking the selection up from the navigator when it tells us the
    // reader made one, and turning it into a place to put the action bar.
    LaunchedEffect(navigator) {
        val nav = navigator ?: return@LaunchedEffect
        selectionRequests.collect {
            val current = nav.currentSelection() ?: return@collect
            selection = ActiveSelection(
                locator = current.locator,
                rect = current.rect,
                existing = onAnnotationAction.annotationAt(current.locator),
            )
        }
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
        onPageTurnerChanged(if (nav != null) pageTurner else null)
        val listener = nav?.let {
            ReaderTapZones(
                navigator = it,
                isChromeVisible = { chromeVisibleNow },
                onTurnPage = pageTurner::turn,
                onShowChrome = { chromeVisible = true },
                onHideChrome = { chromeVisible = false },
            ).also(it::addInputListener)
        }
        onDispose {
            if (nav != null && listener != null) nav.removeInputListener(listener)
            pageTurner.navigator = null
            pageTurner.window = null
            onPageTurnerChanged(null)
            onNavigatorChanged(null)
        }
    }

    ImmersiveMode(hideSystemBars = !chromeVisible)
    ScreenBrightness(brightness = prefs.brightness)

    Box(
        Modifier
            .fillMaxSize()
            .background(prefs.theme.background),
    ) {
        // The page keeps its own breathing room: Readium's own padding
        // is switched off (see dimens.xml) because it is applied after
        // the page is laid out, which cuts the last line in half.
        //
        // The bars and the cutout are measured *ignoring visibility*, so
        // hiding the chrome does not change the height Readium paginates
        // against. Otherwise every page would be re-laid-out on each tap,
        // and a line would end up hidden under the gesture bar or the
        // notch — close enough to fit that the page becomes scrollable by
        // a few pixels, which is exactly the itch we are scratching.
        AndroidFragment<EpubNavigatorFragment>(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(readerInsets())
                .padding(top = 12.dp, bottom = 26.dp),
        ) { fragment ->
            navigator = fragment
        }

        PageTurnOverlay(pageTurnEffect)

        BookmarkRibbon(
            bookmarked = bookmarked,
            theme = prefs.theme,
            onToggle = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onAnnotationAction.toggleBookmark()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                .padding(end = 10.dp),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
        ) {
            jumpBack?.let { target ->
                JumpBackPill(
                    position = target.position,
                    theme = prefs.theme,
                    onJumpBack = {
                        onProgressAction.dismissJumpBack()
                        navigator?.go(target.locator, animated = false)
                    },
                    onDismiss = onProgressAction.dismissJumpBack,
                )
            }
            if (chromeVisible) {
                ReadingScrubber(
                    progress = progress,
                    theme = prefs.theme,
                    chapterTicks = remember(progress?.totalPositions) {
                        onProgressAction.chapterTicks()
                    },
                    titleAtPosition = onProgressAction.chapterTitleAtPosition,
                    positionAtProgression = onProgressAction.positionAtProgression,
                    onSeek = { position ->
                        onProgressAction.locatorAtPosition(position)?.let {
                            onProgressAction.onJump()
                            navigator?.go(it, animated = false)
                        }
                    },
                )
            } else if (jumpBack == null) {
                ReadingFooter(
                    progress = progress,
                    mode = prefs.footerMode,
                    theme = prefs.theme,
                    onCycleMode = onProgressAction.cycleFooterMode,
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically(tween(CHROME_ANIM_MS)) { -it } + fadeIn(tween(CHROME_ANIM_MS)),
            exit = slideOutVertically(tween(CHROME_ANIM_MS)) { -it } + fadeOut(tween(CHROME_ANIM_MS)),
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
                    IconButton(onClick = { showTypography = true }) {
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
                    containerColor = prefs.theme.background,
                    titleContentColor = prefs.theme.foreground,
                    navigationIconContentColor = prefs.theme.foreground,
                    actionIconContentColor = prefs.theme.foreground,
                ),
            )
        }
    }

    selection?.let { active ->
        SelectionPopup(
            offset = active.popupOffset(),
            activeTint = active.existing?.tint?.let(HighlightTint::fromName),
            actions = remember(active) {
                SelectionActions(
                    onHighlight = { tint ->
                        onAnnotationAction.highlight(active.locator, tint, active.existing?.id)
                        navigator?.clearSelection()
                        selection = null
                    },
                    onNote = {
                        noteFor = active
                        navigator?.clearSelection()
                        selection = null
                    },
                    onSearch = {
                        searchFor = active.text
                        navigator?.clearSelection()
                        selection = null
                    },
                    onLookUp = {
                        defineWord = active.text
                        navigator?.clearSelection()
                        selection = null
                    },
                    onShare = {
                        context.shareText(active.text, publication.metadata.title)
                        selection = null
                    },
                    onDelete = active.existing?.let { existing ->
                        {
                            onAnnotationAction.remove(existing)
                            navigator?.clearSelection()
                            selection = null
                        }
                    },
                )
            },
            onDismiss = { selection = null },
        )
    }

    defineWord?.let { word ->
        DefinitionSheet(
            word = word,
            languages = remember(publication) {
                WiktionaryClient.languagesFor(publication.metadata.languages.firstOrNull())
            },
            onOpenInDictionaryApp = {
                context.lookUpExternally(it)
                defineWord = null
            },
            onOpenInBrowser = {
                context.openWiktionary(it)
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
            onFontSelected = onPrefsAction.setFont,
            onFontSizeChanged = onPrefsAction.setFontSize,
            onThemeSelected = onPrefsAction.setTheme,
            onLineHeightChanged = onPrefsAction.setLineHeight,
            onPageMarginsChanged = onPrefsAction.setPageMargins,
            onBrightnessChanged = onPrefsAction.setBrightness,
            onPageTurnAnimationChanged = onPrefsAction.setPageTurnAnimation,
            onFooterModeChanged = onProgressAction.setFooterMode,
            onDismiss = { showTypography = false },
        )
    }

    searchFor?.let { initial ->
        BackHandler {
            searchFor = null
            searchHit = null
            onSearchAction.clear()
        }
        SearchScreen(
            state = searchState,
            theme = prefs.theme,
            initialQuery = initial,
            onSearch = onSearchAction.search,
            onHitSelected = { hit ->
                searchFor = null
                searchHit = hit
                chromeVisible = false
                onProgressAction.onJump()
                navigator?.go(hit, animated = false)
            },
            onClose = {
                searchFor = null
                searchHit = null
                onSearchAction.clear()
            },
        )
    }

    if (showToc) {
        BackHandler { showToc = false }
        val here = navigator?.currentLocator?.collectAsStateWithLifecycle()
        ContentsScreen(
            publication = publication,
            theme = prefs.theme,
            currentHref = here?.value?.href?.toString(),
            annotations = annotations,
            onAnnotationSelected = { annotation ->
                annotation.locator()?.let {
                    showToc = false
                    chromeVisible = false
                    onProgressAction.onJump()
                    navigator?.go(it, animated = false)
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
            onEntrySelected = { link ->
                showToc = false
                chromeVisible = false
                publication.locatorFromLink(link)?.let {
                    onProgressAction.onJump()
                    navigator?.go(it, animated = false)
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
    val setTheme: (ReaderTheme) -> Unit,
    val setLineHeight: (Double?) -> Unit,
    val setPageMargins: (Double?) -> Unit,
    val setBrightness: (Float?) -> Unit,
    val setPageTurnAnimation: (Boolean) -> Unit,
)

/** Bundle of progress and navigation actions for the reader chrome. */
class ReaderProgressActions(
    val cycleFooterMode: () -> Unit,
    val setFooterMode: (FooterMode) -> Unit,
    val onJump: () -> Unit,
    val dismissJumpBack: () -> Unit,
    val chapterTicks: () -> List<Float>,
    val chapterTitleAtPosition: (Int) -> String?,
    val positionAtProgression: (Float) -> Int,
    val locatorAtPosition: (Int) -> Locator?,
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

@Composable
fun ReaderLoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

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
 * The space a page of text may actually use: everything but the system bars
 * and the display cutout, whether or not the bars happen to be on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun readerInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
