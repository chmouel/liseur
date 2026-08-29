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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.ReadingBrightnessSlider
import com.chmouel.liseur.ui.reading.ReadingFontDropdown
import com.chmouel.liseur.ui.reading.ReadingFontSizeSlider
import com.chmouel.liseur.ui.reading.ReadingThemeRow
import com.chmouel.liseur.ui.windowWidth

/**
 * Kindle-style "Aa" sheet: reading theme, font, size and brightness,
 * applied live as they change.
 *
 * The controls themselves live in `ui/reading`, because Settings shows
 * the same ones on a screen of its own. This sheet is the quick path to
 * them with a book open, and adds the two answers that only make sense
 * with one: how the book is read, and whether the screen stays awake for
 * it.
 *
 * It is deliberately short. Everything a reader sets once, if ever, is a
 * tap further in, behind the Advanced row at the bottom — see
 * `docs/adr/0001-advanced-reading-menu.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    prefs: ReaderPrefs,
    readingTheme: ReaderTheme,
    onFontSelected: (ReaderFont) -> Unit,
    onFontSizeChanged: (Double) -> Unit,
    onThemeSelected: (ReaderThemeChoice) -> Unit,
    onBrightnessChanged: (Float?) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    scrollMode: Boolean,
    onScrollModeChanged: (Boolean) -> Unit,
    onOpenAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    LiseurModalBottomSheet(onDismissRequest = onDismiss) {
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
            ScrollModeToggle(
                enabled = scrollMode,
                onChanged = onScrollModeChanged,
            )
            KeepScreenOnToggle(
                enabled = keepScreenOn,
                onChanged = onKeepScreenOnChanged,
            )
            // Last, and always. The sheet behind it has rows for any
            // book, read either way, so it can no longer open empty.
            AdvancedRow(onClick = onOpenAdvanced)
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
