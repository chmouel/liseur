package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.data.settings.ReaderFont
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTheme

/**
 * Kindle-style "Aa" sheet: reading theme, font, size, brightness
 * and layout, applied live as they change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    prefs: ReaderPrefs,
    onFontSelected: (ReaderFont) -> Unit,
    onFontSizeChanged: (Double) -> Unit,
    onThemeSelected: (ReaderTheme) -> Unit,
    onLineHeightChanged: (Double?) -> Unit,
    onPageMarginsChanged: (Double?) -> Unit,
    onBrightnessChanged: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ThemeRow(selected = prefs.theme, onSelected = onThemeSelected)
            FontSizeSlider(value = prefs.fontSize, onChanged = onFontSizeChanged)
            BrightnessSlider(value = prefs.brightness, onChanged = onBrightnessChanged)
            FontDropdown(selected = prefs.font, onSelected = onFontSelected)
            LayoutControls(
                lineHeight = prefs.lineHeight,
                pageMargins = prefs.pageMargins,
                onLineHeightChanged = onLineHeightChanged,
                onPageMarginsChanged = onPageMarginsChanged,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThemeRow(selected: ReaderTheme, onSelected: (ReaderTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReaderTheme.entries.forEach { theme ->
                val isSelected = theme == selected
                Surface(
                    shape = CircleShape,
                    color = theme.background,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .clickable { onSelected(theme) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Aa",
                            color = theme.foreground,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontDropdown(selected: ReaderFont, onSelected: (ReaderFont) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Font")
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selected.displayName,
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
                            Text(font.displayName, fontFamily = family, fontSize = 18.sp)
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

private fun ReaderFont.composeFamily(assets: android.content.res.AssetManager): FontFamily? =
    when (this) {
        ReaderFont.LITERATA -> FontFamily(Font("fonts/Literata.ttf", assets))
        ReaderFont.VOLLKORN -> FontFamily(Font("fonts/Vollkorn.ttf", assets))
        ReaderFont.ATKINSON -> FontFamily(Font("fonts/AtkinsonHyperlegible-Regular.ttf", assets))
        ReaderFont.INTER -> FontFamily(Font("fonts/Inter.ttf", assets))
        ReaderFont.PUBLISHER -> null
    }

@Composable
private fun FontSizeSlider(value: Double, onChanged: (Double) -> Unit) {
    var sliderValue by remember(Unit) { mutableFloatStateOf(value.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Size")
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
private fun BrightnessSlider(value: Float?, onChanged: (Float?) -> Unit) {
    var sliderValue by remember(Unit) { mutableFloatStateOf(value ?: 0.5f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Brightness")
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
                    contentDescription = "Follow system brightness",
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
private fun LayoutControls(
    lineHeight: Double?,
    pageMargins: Double?,
    onLineHeightChanged: (Double?) -> Unit,
    onPageMarginsChanged: (Double?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Line spacing")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf("Compact" to 1.2, "Default" to null, "Relaxed" to 1.8)
            options.forEachIndexed { index, (label, v) ->
                SegmentedButton(
                    selected = lineHeight == v,
                    onClick = { onLineHeightChanged(v) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
        SectionLabel("Margins")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf("Narrow" to 0.5, "Default" to null, "Wide" to 2.0)
            options.forEachIndexed { index, (label, v) ->
                SegmentedButton(
                    selected = pageMargins == v,
                    onClick = { onPageMarginsChanged(v) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}
