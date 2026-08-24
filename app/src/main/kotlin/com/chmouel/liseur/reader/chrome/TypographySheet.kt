package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.ReadingBrightnessSlider
import com.chmouel.liseur.ui.reading.ReadingFontDropdown
import com.chmouel.liseur.ui.reading.ReadingFontSizeSlider
import com.chmouel.liseur.ui.reading.ReadingFooterModeDropdown
import com.chmouel.liseur.ui.reading.ReadingLayoutControls
import com.chmouel.liseur.ui.reading.ReadingPageTurnAnimationToggle
import com.chmouel.liseur.ui.reading.ReadingThemeRow
import com.chmouel.liseur.ui.windowWidth

/**
 * Kindle-style "Aa" sheet: reading theme, font, size, brightness
 * and layout, applied live as they change.
 *
 * The controls themselves live in `ui/reading`, because Settings shows
 * the same ones on a screen of its own. This sheet is the quick path to
 * them with a book open, and adds the three answers that only make
 * sense with one: scrolling, the screen, and setting this book apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    prefs: ReaderPrefs,
    readingTheme: ReaderTheme,
    typographyIsOwn: Boolean,
    onTypographyIsOwnChanged: (Boolean) -> Unit,
    onFontSelected: (ReaderFont) -> Unit,
    onFontSizeChanged: (Double) -> Unit,
    onThemeSelected: (ReaderThemeChoice) -> Unit,
    onLineHeightChanged: (Double?) -> Unit,
    onPageMarginsChanged: (Double?) -> Unit,
    onBrightnessChanged: (Float?) -> Unit,
    onPageTurnAnimationChanged: (Boolean) -> Unit,
    onFooterModeChanged: (FooterMode) -> Unit,
    onColumnModeChanged: (ColumnMode) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    scrollMode: Boolean,
    onScrollModeChanged: (Boolean) -> Unit,
    advancedOffered: Boolean,
    onOpenAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ReadingThemeRow(
                selected = prefs.themeChoice,
                resolved = readingTheme,
                onSelected = onThemeSelected,
            )
            ReadingFontSizeSlider(value = prefs.fontSize, onChanged = onFontSizeChanged)
            ReadingBrightnessSlider(value = prefs.brightness, onChanged = onBrightnessChanged)
            ReadingFontDropdown(selected = prefs.font, onSelected = onFontSelected)
            ReadingLayoutControls(
                lineHeight = prefs.lineHeight,
                pageMargins = prefs.pageMargins,
                columnMode = prefs.columnMode,
                // A scrolled chapter is one running column, so the count
                // has nothing to divide and the control would take a tap
                // and change nothing.
                showColumns = !scrollMode,
                onLineHeightChanged = onLineHeightChanged,
                onPageMarginsChanged = onPageMarginsChanged,
                onColumnModeChanged = onColumnModeChanged,
            )
            ReadingFooterModeDropdown(
                selected = prefs.footerMode,
                onSelected = onFooterModeChanged,
            )
            ScrollModeToggle(
                enabled = scrollMode,
                onChanged = onScrollModeChanged,
            )
            // Nothing lifts off the screen when the text is scrolled, so
            // the animation has no page to describe.
            if (!scrollMode) {
                ReadingPageTurnAnimationToggle(
                    enabled = prefs.pageTurnAnimation,
                    onChanged = onPageTurnAnimationChanged,
                )
            }
            KeepScreenOnToggle(
                enabled = keepScreenOn,
                onChanged = onKeepScreenOnChanged,
            )
            JustThisBookToggle(
                enabled = typographyIsOwn,
                onChanged = onTypographyIsOwnChanged,
            )
            // Last, and only when there is something behind it. An
            // Advanced row that opens an empty sheet is worse than no
            // row at all.
            if (advancedOffered) {
                AdvancedRow(onClick = onOpenAdvanced)
            }
        }
    }
}

/**
 * The way through to the settings a reader changes once, if ever.
 *
 * One row, at the bottom, and the only row this sheet gains however many
 * reading features arrive: that is the whole bargain of
 * `docs/adr/0001-advanced-reading-menu.md`. What it opens closes back to
 * here, and here closes back to the book.
 */
@Composable
private fun AdvancedRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = stringResource(R.string.reader_advanced),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * Reads this book by scrolling rather than by turning pages.
 *
 * Settings holds the answer for the library, and it is what a book
 * starts from; flipping it here sets this book apart for good, the same
 * way the screen switch below does, so a later change in Settings leaves
 * this book where it was put.
 */
@Composable
private fun ScrollModeToggle(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onChanged,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_scroll_mode),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}


/**
 * Holds the screen awake for this book alone.
 *
 * Settings holds the answer for the library, and it is what a book
 * starts from; flipping it here sets this book apart for good, the way a
 * book can be set apart for its typography, and a later change in
 * Settings leaves it where it was put.
 */
@Composable
private fun KeepScreenOnToggle(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onChanged,
            ),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_keep_screen_on),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * Sets the book apart from the shared reading settings.
 *
 * Last in the sheet, and only about the four settings above it that are
 * genuinely the book's: what it is set in, how big, how open, how wide.
 * The theme and the brightness are about the room, so they carry on
 * being shared however this is left.
 */
@Composable
private fun JustThisBookToggle(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(!enabled) },
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.reader_typography_own),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (enabled) {
                    stringResource(R.string.reader_typography_own_on)
                } else {
                    stringResource(R.string.reader_typography_own_off)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChanged)
    }
}
