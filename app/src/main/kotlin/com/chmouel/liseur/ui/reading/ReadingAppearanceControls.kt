package com.chmouel.liseur.ui.reading

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ColumnMode
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.data.settings.ReaderThemeChoice
import com.chmouel.liseur.ui.widthClass

/*
 * The controls that say how a page looks, in one place.
 *
 * Two screens draw them: the "Aa" sheet over an open book, and the
 * reading appearance screen in Settings. They were only in the sheet
 * once, which meant the only way to find the dark reading theme was to
 * open a book and press a button labelled "Aa". Having them in both
 * places is deliberate; having them written twice would not be, so they
 * live here and both callers ask for the same composable.
 */

@Composable
fun ReadingSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The reading themes, as swatches of themselves.
 *
 * [resolved] is what the page is actually set in, which for
 * [ReaderThemeChoice.FOLLOW_APP] is not something the choice can say on
 * its own — so the Auto swatch borrows those colours and marks itself
 * with an icon rather than the "Aa" the fixed themes show. Selection is
 * compared on the choice, so sitting on Auto in the dark and having
 * asked for Dark outright stay visibly different states.
 */
@Composable
fun ReadingThemeRow(
    selected: ReaderThemeChoice,
    resolved: ReaderTheme,
    onSelected: (ReaderThemeChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSectionLabel(stringResource(R.string.settings_theme))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            ReaderThemeChoice.entries.forEach { choice ->
                val palette = choice.palette ?: resolved
                val isSelected = choice == selected
                val label = stringResource(choice.label)
                Surface(
                    shape = CircleShape,
                    color = palette.background,
                    border = BorderStroke(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .clickable { onSelected(choice) }
                        .semantics { contentDescription = label },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (choice.palette == null) {
                            Icon(
                                Icons.Outlined.BrightnessAuto,
                                contentDescription = null,
                                tint = palette.foreground,
                            )
                        } else {
                            Text(
                                text = "Aa",
                                color = palette.foreground,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
        if (selected == ReaderThemeChoice.FOLLOW_APP) {
            // The one theme whose swatch cannot show what it is: it
            // borrows another's colours, so it has to say so in words.
            Text(
                text = stringResource(R.string.reader_theme_auto_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingFontDropdown(selected: ReaderFont, onSelected: (ReaderFont) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_font))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(selected.label),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = remember(selected) { selected.composeFamily(context.assets) },
                    fontSize = 18.sp,
                ),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                ReaderFont.entries.forEach { font ->
                    val family = remember(font) { font.composeFamily(context.assets) }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(font.label),
                                fontFamily = family,
                                fontSize = 18.sp,
                            )
                        },
                        trailingIcon = {
                            if (font == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            onSelected(font)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingFooterModeDropdown(selected: FooterMode, onSelected: (FooterMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_progress))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(selected.label),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                FooterMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(mode.label)) },
                        trailingIcon = {
                            if (mode == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            onSelected(mode)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ReadingFontSizeSlider(value: Double, onChanged: (Double) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_size))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", fontSize = 14.sp)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onChanged(sliderValue.toDouble()) },
                valueRange = ReaderPrefs.MIN_FONT_SIZE.toFloat()..ReaderPrefs.MAX_FONT_SIZE.toFloat(),
                steps = 17,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text("A", fontSize = 26.sp)
        }
    }
}

@Composable
fun ReadingBrightnessSlider(value: Float?, onChanged: (Float?) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value ?: 0.5f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_brightness))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.BrightnessLow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onChanged(it)
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Icon(
                Icons.Outlined.BrightnessHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onChanged(null) }) {
                Icon(
                    Icons.Outlined.BrightnessAuto,
                    contentDescription = stringResource(R.string.reader_brightness_system),
                    tint = if (value == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
fun ReadingLayoutControls(
    lineHeight: Double?,
    pageMargins: Double?,
    columnMode: ColumnMode,
    showColumns: Boolean,
    onLineHeightChanged: (Double?) -> Unit,
    onPageMarginsChanged: (Double?) -> Unit,
    onColumnModeChanged: (ColumnMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_line_spacing))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                R.string.reader_spacing_compact to 1.2,
                R.string.reader_spacing_default to null,
                R.string.reader_spacing_relaxed to 1.8,
            )
            options.forEachIndexed { index, (label, v) ->
                SegmentedButton(
                    selected = lineHeight == v,
                    onClick = { onLineHeightChanged(v) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        ReadingSectionLabel(stringResource(R.string.reader_margins))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                R.string.reader_margins_narrow to 0.5,
                R.string.reader_margins_default to null,
                R.string.reader_margins_wide to 2.0,
            )
            options.forEachIndexed { index, (label, v) ->
                SegmentedButton(
                    selected = pageMargins == v,
                    onClick = { onPageMarginsChanged(v) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        // Only offered where it can be honoured. Two columns need room
        // for two columns, and on a phone there is none: the control
        // would sit there taking a tap and changing nothing.
        if (showColumns && widthClass().isAtLeastMedium) {
            ReadingSectionLabel(stringResource(R.string.reader_columns))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = ColumnMode.entries
                options.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = columnMode == mode,
                        onClick = { onColumnModeChanged(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(mode.label)) }
                }
            }
        }
    }
}

@Composable
fun ReadingPageTurnAnimationToggle(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(!enabled) },
    ) {
        Text(
            text = stringResource(R.string.reader_page_turn_animation),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onChanged)
    }
}

internal fun ReaderFont.composeFamily(assets: android.content.res.AssetManager): FontFamily? =
    when (this) {
        ReaderFont.LITERATA -> FontFamily(Font("fonts/Literata.ttf", assets))
        ReaderFont.VOLLKORN -> FontFamily(Font("fonts/Vollkorn.ttf", assets))
        ReaderFont.ATKINSON -> FontFamily(Font("fonts/AtkinsonHyperlegible-Regular.ttf", assets))
        ReaderFont.INTER -> FontFamily(Font("fonts/Inter.ttf", assets))
        ReaderFont.PUBLISHER -> null
    }

/*
 * The names these settings go by, mapped where the strings live rather
 * than carried on the enums, which stay pure Kotlin and testable off a
 * device. Same shape as ThemeMode.label and EInkMode.label in Settings.
 */

val ReaderThemeChoice.label: Int
    get() = when (this) {
        ReaderThemeChoice.FOLLOW_APP -> R.string.reader_theme_auto
        ReaderThemeChoice.LIGHT -> R.string.reader_theme_light
        ReaderThemeChoice.SEPIA -> R.string.reader_theme_sepia
        ReaderThemeChoice.DARK -> R.string.reader_theme_dark
        ReaderThemeChoice.BLACK -> R.string.reader_theme_black
    }

val ReaderFont.label: Int
    get() = when (this) {
        ReaderFont.LITERATA -> R.string.reader_font_literata
        ReaderFont.VOLLKORN -> R.string.reader_font_vollkorn
        ReaderFont.ATKINSON -> R.string.reader_font_atkinson
        ReaderFont.INTER -> R.string.reader_font_inter
        ReaderFont.PUBLISHER -> R.string.reader_font_publisher
    }

val ColumnMode.label: Int
    get() = when (this) {
        ColumnMode.AUTO -> R.string.reader_columns_auto
        ColumnMode.ONE -> R.string.reader_columns_one
        ColumnMode.TWO -> R.string.reader_columns_two
    }

val FooterMode.label: Int
    get() = when (this) {
        FooterMode.SMART -> R.string.footer_mode_smart
        FooterMode.TIME_LEFT_BOOK -> R.string.footer_mode_time_book
        FooterMode.CHAPTER_TITLE -> R.string.footer_mode_chapter
        FooterMode.EMPTY -> R.string.footer_mode_empty
        FooterMode.NONE -> R.string.footer_mode_none
    }
