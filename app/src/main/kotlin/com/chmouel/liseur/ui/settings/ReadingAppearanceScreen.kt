package com.chmouel.liseur.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.ReadingBrightnessSlider
import com.chmouel.liseur.ui.reading.ReadingFontDropdown
import com.chmouel.liseur.ui.reading.ReadingFontSizeSlider
import com.chmouel.liseur.ui.reading.ReadingFooterModeDropdown
import com.chmouel.liseur.ui.reading.ReadingLayoutControls
import com.chmouel.liseur.ui.reading.ReadingPageTurnAnimationToggle
import com.chmouel.liseur.ui.reading.ReadingSectionLabel
import com.chmouel.liseur.ui.reading.ReadingThemeRow
import com.chmouel.liseur.ui.reading.composeFamily
import com.chmouel.liseur.ui.windowWidth

/**
 * How the page looks, away from any particular page.
 *
 * The same controls the "Aa" sheet shows, on a screen you can reach
 * without opening a book — which is what a reader who had turned the app
 * dark and found their books still white was looking for, and could not
 * find (issue #13).
 *
 * The preview at the top is the point of having this here rather than a
 * row of sentences: with no page behind them, these settings need
 * something to be true of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingAppearanceScreen(
    prefs: ReaderPrefs,
    appIsDark: Boolean,
    onTheme: (ReaderThemeChoice) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onFontSize: (Double) -> Unit,
    onLineHeight: (Double?) -> Unit,
    onPageMargins: (Double?) -> Unit,
    onBrightness: (Float?) -> Unit,
    onColumnMode: (ColumnMode) -> Unit,
    onFooterMode: (FooterMode) -> Unit,
    onPageTurnAnimation: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val resolved = prefs.themeChoice.resolve(appIsDark)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_reading_appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ReadingPreview(prefs = prefs, theme = resolved)
                ReadingThemeRow(
                    selected = prefs.themeChoice,
                    resolved = resolved,
                    onSelected = onTheme,
                )
                ReadingFontSizeSlider(value = prefs.fontSize, onChanged = onFontSize)
                ReadingBrightnessSlider(value = prefs.brightness, onChanged = onBrightness)
                ReadingFontDropdown(selected = prefs.font, onSelected = onFont)
                AdvancedSection(
                    prefs = prefs,
                    onLineHeight = onLineHeight,
                    onPageMargins = onPageMargins,
                    onColumnMode = onColumnMode,
                    onFooterMode = onFooterMode,
                    onPageTurnAnimation = onPageTurnAnimation,
                )
                Text(
                    text = stringResource(R.string.settings_reading_appearance_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The same split the reader sees, on the screen that has no book.
 *
 * The shared settings behind the "Aa" sheet's Advanced row are behind
 * this too, in the same order: a reader who learned where the margins
 * live in one place should not have to learn it again in the other.
 *
 * Only the shared ones. The rest of that sheet has no meaning here —
 * auto-scroll needs a page that is running, and setting a book apart
 * needs a book — so this holds the layout controls, the footer mode and
 * the page-turn animation, and nothing else. See
 * `docs/adr/0001-advanced-reading-menu.md`.
 */
@Composable
private fun AdvancedSection(
    prefs: ReaderPrefs,
    onLineHeight: (Double?) -> Unit,
    onPageMargins: (Double?) -> Unit,
    onColumnMode: (ColumnMode) -> Unit,
    onFooterMode: (FooterMode) -> Unit,
    onPageTurnAnimation: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.reader_hide_advanced else R.string.reader_advanced,
            ),
        )
    }
    // Sliding this open on electronic paper is a full repaint of the
    // lower half of the screen for every frame; it is the same list
    // either way, so it simply appears.
    val eInk = LocalEInk.current
    AnimatedVisibility(
        visible = expanded,
        enter = if (eInk) EnterTransition.None else fadeIn() + expandVertically(),
        exit = if (eInk) ExitTransition.None else fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ReadingLayoutControls(
                lineHeight = prefs.lineHeight,
                pageMargins = prefs.pageMargins,
                columnMode = prefs.columnMode,
                // Unlike the sheet, there is no book here to be
                // scrolled, so the count always has something to
                // divide and the control is always worth offering.
                showColumns = true,
                onLineHeightChanged = onLineHeight,
                onPageMarginsChanged = onPageMargins,
                onColumnModeChanged = onColumnMode,
            )
            ReadingFooterModeDropdown(
                selected = prefs.footerMode,
                onSelected = onFooterMode,
            )
            ReadingPageTurnAnimationToggle(
                enabled = prefs.pageTurnAnimation,
                onChanged = onPageTurnAnimation,
            )
        }
    }
}

/**
 * A few lines of a book that is not there.
 *
 * Drawn in the reading theme's own colours rather than the app's, so
 * the difference between the two — the thing the reporter of #13 could
 * not see — is on the screen where the setting is. The size, spacing
 * and margins are the real ones; the brightness is not, since it is the
 * screen's and not the page's.
 */
@Composable
private fun ReadingPreview(prefs: ReaderPrefs, theme: ReaderTheme) {
    val context = LocalContext.current
    val family = remember(prefs.font) { prefs.font.composeFamily(context.assets) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_preview_title))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.background)
                .padding(
                    horizontal = (24 * (prefs.pageMargins ?: 1.0)).dp,
                    vertical = 20.dp,
                ),
        ) {
            Text(
                text = stringResource(R.string.reader_preview_body),
                color = theme.foreground,
                fontFamily = family,
                fontSize = (17 * prefs.fontSize).sp,
                lineHeight = (17 * prefs.fontSize * (prefs.lineHeight ?: 1.4)).sp,
                textAlign = TextAlign.Start,
            )
        }
    }
}
