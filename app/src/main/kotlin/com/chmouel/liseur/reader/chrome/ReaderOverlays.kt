package com.chmouel.liseur.reader.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.FooterField
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.FooterSlot
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.ReaderNarrateEntry
import com.chmouel.liseur.reader.ReaderTranslateEntry
import com.chmouel.liseur.reader.ReaderTranslateMenuItem
import com.chmouel.liseur.reader.ReaderWholeBook
import com.chmouel.liseur.reader.progress.BookScreenEstimate
import com.chmouel.liseur.reader.progress.ReaderProgress
import com.chmouel.liseur.reader.progress.SectionScreens
import com.chmouel.liseur.readaloud.ReadAloudFeature
import com.chmouel.liseur.translate.PageTranslationModes
import com.chmouel.liseur.translate.TranslateFeature
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.readium.r2.navigator.epub.EpubNavigatorFragment

private const val OVERLAY_CHROME_ANIM_MS = 300

sealed interface GestureHudState {
    data object None : GestureHudState
    data object FixedLayoutPinch : GestureHudState
    data class FontSize(val size: Double) : GestureHudState
    data class Brightness(val level: Float) : GestureHudState
}

fun resolveGestureHudState(
    pinchRefused: Boolean,
    pinchTarget: Double?,
    brightnessSwiping: Boolean,
    brightnessPreview: Float?,
): GestureHudState = when {
    pinchRefused -> GestureHudState.FixedLayoutPinch
    pinchTarget != null -> GestureHudState.FontSize(pinchTarget)
    brightnessSwiping && brightnessPreview != null -> GestureHudState.Brightness(brightnessPreview)
    else -> GestureHudState.None
}

@Composable
fun ReaderGestureHudOverlay(
    pinchRefused: () -> Boolean,
    pinchTarget: () -> Double?,
    brightnessSwiping: () -> Boolean,
    brightnessPreview: () -> Float?,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    val refused = pinchRefused()
    val target = pinchTarget()
    val swiping = brightnessSwiping()
    val preview = brightnessPreview()
    Box(modifier.fillMaxSize()) {
        when (val hudState = resolveGestureHudState(refused, target, swiping, preview)) {
            GestureHudState.FixedLayoutPinch -> FixedLayoutPinchHud(theme = theme)
            is GestureHudState.FontSize -> FontSizeHud(size = hudState.size, theme = theme)
            else -> Unit
        }
        if (swiping && preview != null) {
            BrightnessHud(brightness = preview, theme = theme)
        }
    }
}

@Composable
fun ReaderLiveFooterOverlay(
    navigator: () -> EpubNavigatorFragment?,
    footerDrawn: () -> Boolean,
    progress: () -> ReaderProgress?,
    reflowable: Boolean,
    screens: () -> SectionScreens?,
    bookScreens: () -> BookScreenEstimate,
    mode: FooterMode,
    left: FooterField,
    right: FooterField,
    footerLocked: Boolean,
    footerReserve: Dp,
    theme: ReaderTheme,
    footerPickOpen: () -> Boolean,
    onCycleMode: ((FooterMode) -> Unit) -> Unit,
    onCycleField: (FooterSlot, (FooterField) -> Unit) -> Unit,
    onPickSlot: (FooterPickTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawn = footerDrawn()
    val nav = navigator()
    val readsTheDevice = left.readsTheDevice || right.readsTheDevice
    var footerTurn by remember { mutableIntStateOf(0) }
    var footerNote by remember { mutableStateOf<FooterSlotNote?>(null) }
    var footerNoteCount by remember { mutableLongStateOf(0L) }

    // Clock and battery figures do not self-invalidate on e-paper, and books
    // without built positions do not bump [progress], so turns are counted off
    // the navigator only while a slot displays device state.
    LaunchedEffect(nav, drawn, readsTheDevice) {
        if (!drawn || !readsTheDevice) return@LaunchedEffect
        val pages = nav ?: return@LaunchedEffect
        pages.currentLocator
            .map { at ->
                Triple(
                    at.href.toString(),
                    at.locations.position,
                    at.locations.progression,
                )
            }
            .distinctUntilChanged()
            .drop(1)
            .collect { footerTurn += 1 }
    }

    // Clear any in-flight or late-arriving note when the footer leaves the screen
    // so an unfinished countdown does not resume later.
    LaunchedEffect(drawn, footerNote) {
        if (!drawn) footerNote = null
    }

    if (drawn) {
        Box(modifier.fillMaxSize()) {
            ReadingFooter(
                progress = progress(),
                reflowable = reflowable,
                screens = screens(),
                bookScreens = bookScreens(),
                mode = mode,
                left = left,
                right = right,
                footerLocked = footerLocked,
                turn = footerTurn,
                theme = theme,
                onCycleMode = onCycleMode,
                onCycleField = onCycleField,
                onPickSlot = {
                    footerNote = null
                    onPickSlot(it)
                },
                onNote = { label, unknown ->
                    if (!footerPickOpen()) {
                        footerNoteCount += 1
                        footerNote = FooterSlotNote(label, unknown, footerNoteCount)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FooterMetrics.BOTTOM_MARGIN_DP.dp),
            )
            FooterNote(
                note = footerNote,
                theme = theme,
                onDone = { footerNote = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = footerNoteBottom(footerReserve)),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBarOverlay(
    visibleState: MutableTransitionState<Boolean>,
    eInk: Boolean,
    title: String,
    bookLanguages: List<String>,
    reflowableText: Boolean,
    bookmarked: Boolean,
    theme: ReaderTheme,
    codexOrStoryAvailable: Boolean,
    chromeVisible: Boolean,
    translate: TranslateFeature,
    translateReady: Boolean,
    pageTranslated: Boolean,
    translationSettingsOpen: Boolean,
    wholeBook: ReaderWholeBook?,
    readAloudEnabled: Boolean,
    readAloudFeature: ReadAloudFeature,
    readAloudConfigured: Boolean,
    listening: Boolean,
    hasReadAloudFromHere: Boolean,
    syncable: Boolean,
    scrollMode: Boolean,
    scrollModeEnabled: Boolean,
    effectiveScrolling: Boolean,
    autoScrollArmed: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onTypography: () -> Unit,
    onShowToc: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenCodex: () -> Unit,
    onTranslateFromHere: (PageTranslationModes.Mode) -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onStopTranslatingBook: () -> Unit,
    onOpenWholeBook: () -> Unit,
    onReadAloudFromHere: () -> Unit,
    onStopReadAloud: () -> Unit,
    onOpenReadAloudSetup: () -> Unit,
    onSyncBook: () -> Unit,
    onScrollModeChanged: (Boolean) -> Unit,
    onAutoScrollArmedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chromeEnter = if (eInk) {
        EnterTransition.None
    } else {
        slideInVertically(tween(OVERLAY_CHROME_ANIM_MS)) { -it } +
            fadeIn(tween(OVERLAY_CHROME_ANIM_MS))
    }
    val chromeExit = if (eInk) {
        ExitTransition.None
    } else {
        slideOutVertically(tween(OVERLAY_CHROME_ANIM_MS)) { -it } +
            fadeOut(tween(OVERLAY_CHROME_ANIM_MS))
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = chromeEnter,
        exit = chromeExit,
        modifier = modifier,
    ) {
        var moreOpen by remember { mutableStateOf(false) }
        val uiLocale = LocalConfiguration.current.locales[0]
        val wholeBookSavedFlow = remember(wholeBook) { wholeBook?.saved ?: flowOf(false) }
        val wholeBookSaved by wholeBookSavedFlow.collectAsStateWithLifecycle(false)
        val translateEntry by produceState<ReaderTranslateEntry?>(
            null,
            chromeVisible,
            translateReady,
            pageTranslated,
            uiLocale,
            translationSettingsOpen,
            wholeBookSaved,
        ) {
            if (translationSettingsOpen) return@produceState
            val languages = if (translate.isAvailable && translateReady && !pageTranslated && reflowableText) {
                translate.pageLanguages(bookLanguages, uiLocale)
            } else {
                null
            }
            value = ReaderTranslateEntry.of(
                available = translate.isAvailable,
                reflowable = reflowableText,
                translated = pageTranslated,
                languages = languages,
                wholeBookEligible = wholeBook != null && translate.translatesWholeBooks(),
                wholeBookSaved = wholeBookSaved,
            )
        }
        val narrateEntry = if (readAloudEnabled) {
            ReaderNarrateEntry.of(
                available = readAloudFeature.isAvailable,
                reflowable = reflowableText,
                configured = readAloudConfigured,
            )
        } else {
            null
        }
        Column {
            TopAppBar(
                title = {
                    Text(
                        text = title,
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
                    IconButton(onClick = onSearch) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.reader_search),
                        )
                    }
                    val typographyLabel = stringResource(R.string.reader_typography)
                    IconButton(
                        onClick = onTypography,
                        modifier = Modifier.semantics {
                            contentDescription = typographyLabel
                        },
                    ) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    IconButton(onClick = onShowToc) {
                        Icon(
                            Icons.AutoMirrored.Outlined.List,
                            contentDescription = stringResource(R.string.reader_contents),
                        )
                    }
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            if (bookmarked) {
                                Icons.Filled.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            contentDescription = stringResource(
                                if (bookmarked) {
                                    R.string.reader_remove_bookmark
                                } else {
                                    R.string.reader_add_bookmark
                                },
                            ),
                        )
                    }
                    Box {
                        IconButton(onClick = { moreOpen = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            if (codexOrStoryAvailable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.codex_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                                    onClick = {
                                        moreOpen = false
                                        onOpenCodex()
                                    },
                                )
                            }
                            translateEntry?.let { entry ->
                                ReaderTranslateMenuItem(
                                    entry = entry,
                                    uiLocale = uiLocale,
                                    onClick = {
                                        moreOpen = false
                                        when (entry) {
                                            is ReaderTranslateEntry.Into -> onTranslateFromHere(entry.languages)
                                            is ReaderTranslateEntry.SetUp -> onOpenTranslationSettings()
                                            ReaderTranslateEntry.Stop -> onStopTranslatingBook()
                                        }
                                    },
                                    onLongClick = {
                                        moreOpen = false
                                        onOpenWholeBook()
                                    },
                                )
                            }
                            if (listening) {
                                if (hasReadAloudFromHere) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.read_aloud_from_this_page)) },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                                        },
                                        onClick = {
                                            moreOpen = false
                                            onReadAloudFromHere()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_aloud_stop)) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = null)
                                    },
                                    onClick = {
                                        moreOpen = false
                                        onStopReadAloud()
                                    },
                                )
                            } else narrateEntry?.let { entry ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reader_narrate_from_here)) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                                    },
                                    onClick = {
                                        moreOpen = false
                                        when (entry) {
                                            ReaderNarrateEntry.Start -> onReadAloudFromHere()
                                            ReaderNarrateEntry.SetUp -> onOpenReadAloudSetup()
                                        }
                                    },
                                )
                            }
                            if (syncable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reader_sync_book)) },
                                    leadingIcon = { Icon(Icons.Outlined.CloudSync, contentDescription = null) },
                                    onClick = {
                                        moreOpen = false
                                        onSyncBook()
                                    },
                                )
                            }
                            if (
                                codexOrStoryAvailable || translateEntry != null ||
                                listening || narrateEntry != null || syncable
                            ) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    role = Role.Checkbox
                                    toggleableState = if (scrollMode) ToggleableState.On else ToggleableState.Off
                                },
                                text = { Text(stringResource(R.string.settings_scroll_mode)) },
                                leadingIcon = { Icon(Icons.Outlined.SwapVert, contentDescription = null) },
                                trailingIcon = {
                                    Checkbox(
                                        checked = scrollMode,
                                        onCheckedChange = null,
                                        enabled = scrollModeEnabled,
                                    )
                                },
                                enabled = scrollModeEnabled,
                                onClick = { onScrollModeChanged(!scrollMode) },
                            )
                            if (effectiveScrolling) {
                                DropdownMenuItem(
                                    modifier = Modifier.semantics(mergeDescendants = true) {
                                        role = Role.Checkbox
                                        toggleableState =
                                            if (autoScrollArmed) ToggleableState.On else ToggleableState.Off
                                    },
                                    text = { Text(stringResource(R.string.reader_auto_scroll)) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                                    trailingIcon = {
                                        Checkbox(checked = autoScrollArmed, onCheckedChange = null)
                                    },
                                    onClick = {
                                        val enable = !autoScrollArmed
                                        onAutoScrollArmedChanged(enable)
                                        if (enable) moreOpen = false
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.foreground,
                    navigationIconContentColor = theme.foreground,
                    actionIconContentColor = theme.foreground,
                ),
            )
            ChromeEdgeFade(theme = theme, solidAtTop = true)
        }
    }
}
