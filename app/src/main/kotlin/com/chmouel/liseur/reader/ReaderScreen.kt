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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.compose.AndroidFragment
import com.chmouel.liseur.R
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/** Duration of the gentle chrome show/hide animation. */
private const val CHROME_ANIM_MS = 300

@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
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
    onBack: () -> Unit,
) {
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var chromeVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showTypography by remember { mutableStateOf(false) }
    val chromeVisibleNow by rememberUpdatedState(chromeVisible)
    val prefs by prefsFlow.collectAsStateWithLifecycle()
    val progress by progressFlow.collectAsStateWithLifecycle()
    val jumpBack by jumpBackFlow.collectAsStateWithLifecycle()
    val view = LocalView.current
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
        AndroidFragment<EpubNavigatorFragment>(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 26.dp),
        ) { fragment ->
            navigator = fragment
        }

        PageTurnOverlay(pageTurnEffect)

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
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

    if (showToc) {
        BackHandler { showToc = false }
        val here = navigator?.currentLocator?.collectAsStateWithLifecycle()
        ContentsScreen(
            publication = publication,
            theme = prefs.theme,
            currentHref = here?.value?.href?.toString(),
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

/** Bundle of preference setters passed down to the reader chrome. */class ReaderPrefsActions(
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
