package com.chmouel.liseur.ui.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderFontWeight
import com.chmouel.liseur.data.settings.ReaderPrefs
import com.chmouel.liseur.data.settings.ReaderTextAlign
import com.chmouel.liseur.data.settings.ReadingCss
import com.chmouel.liseur.data.settings.TypographyRange
import com.chmouel.liseur.data.settings.justificationHyphenates
import com.chmouel.liseur.data.settings.spacingCommit
import com.chmouel.liseur.data.settings.spacingThumb
import java.text.NumberFormat

/**
 * The six fine typography settings, gathered so the reader has one thing
 * to hand to a sheet or a settings screen rather than six.
 *
 * They are declared here beside the control that calls them, and not in
 * `ReaderScreen.kt` where the reader's other action bundles live, so
 * that the settings screen can use the same control without depending on
 * a type belonging to the reader.
 */
data class FineTypographyActions(
    val onTextAlignChanged: (ReaderTextAlign) -> Unit,
    val onHyphensChanged: (Boolean?) -> Unit,
    val onFontWeightChanged: (ReaderFontWeight) -> Unit,
    val onLetterSpacingChanged: (Double?) -> Unit,
    val onWordSpacingChanged: (Double?) -> Unit,
    val onParagraphSpacingChanged: (Double?) -> Unit,
)

/**
 * Alignment, hyphenation, weight, and letter, word and paragraph
 * spacing — the settings that shape a line of text rather than the page
 * around it.
 *
 * One composable for both the reading sheet and the settings screen, so
 * the two cannot drift apart.
 *
 * [css] is which of Readium's stylesheets this book will be rendered
 * with, and it decides what is offered. The variants do not carry the
 * same rules: hyphenation and letter and word spacing exist only in the
 * default stylesheet, alignment in the default and right-to-left ones. A
 * row whose rule this book's stylesheet does not contain is **disabled**
 * rather than annotated — it would take a tap, change nothing visible,
 * and, before the scope reached [com.chmouel.liseur.data.settings.requiresAdvancedStyles],
 * silently renormalize the book's whole type scale for the privilege.
 *
 * It keeps showing its stored value while disabled: the setting has not
 * been lost, it is waiting for a book that can use it.
 *
 * A fixed-layout book disables every row here, and says so nowhere: the
 * sheet above says it once at the top, for these rows and for the size,
 * the face, the margins and the columns alike. See
 * [com.chmouel.liseur.ui.reading.FixedLayoutNotice].
 *
 * [ReadingCss.Unknown] is the settings screen, where no book is open.
 * Nothing is disabled there — the reader is choosing a default for every
 * book they will open, not for this one — and the wording names the
 * writing systems instead of claiming anything about a particular book.
 */
@Composable
fun ReadingFineTypographyControls(
    prefs: ReaderPrefs,
    css: ReadingCss,
    actions: FineTypographyActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val enabled = css.honoursAnything

        AlignmentRow(
            value = prefs.textAlign,
            enabled = enabled && css.honoursAlignment,
            note = when {
                !enabled -> null
                !css.honoursAlignment -> stringResource(R.string.reader_typography_not_in_cjk)
                css == ReadingCss.Unknown ->
                    stringResource(R.string.reader_typography_align_scope)
                else -> null
            },
            onChanged = actions.onTextAlignChanged,
        )

        // Hyphenation and letter and word spacing are the three that
        // only the default stylesheet has a rule for, so they are kept
        // together and answered for once at the end of the run rather
        // than three times over.
        val latinOnly = enabled && css.honoursLatinSpacing

        HyphensRow(
            value = prefs.hyphens,
            enabled = latinOnly,
            // The justification note is the one thing a reader cannot
            // work out by looking: Readium hyphenates justified text
            // whether or not anyone asked it to.
            note = stringResource(R.string.reader_hyphens_justified_note)
                .takeIf { latinOnly && justificationHyphenates(prefs.textAlign, prefs.hyphens) },
            onChanged = actions.onHyphensChanged,
        )

        DefaultableSpacingSlider(
            label = stringResource(R.string.reader_letter_spacing),
            value = prefs.letterSpacing,
            range = TypographyRange.LETTER_SPACING,
            enabled = latinOnly,
            note = null,
            onChanged = actions.onLetterSpacingChanged,
        )

        DefaultableSpacingSlider(
            label = stringResource(R.string.reader_word_spacing),
            value = prefs.wordSpacing,
            range = TypographyRange.WORD_SPACING,
            enabled = latinOnly,
            note = latinNote(css, enabled),
            onChanged = actions.onWordSpacingChanged,
        )

        DefaultableSpacingSlider(
            label = stringResource(R.string.reader_paragraph_spacing),
            value = prefs.paragraphSpacing,
            range = TypographyRange.PARAGRAPH_SPACING,
            // Paragraph spacing is the one of the three that every
            // stylesheet carries a rule for.
            enabled = enabled,
            note = null,
            onChanged = actions.onParagraphSpacingChanged,
        )

        FontWeightRow(
            value = prefs.fontWeight,
            enabled = enabled,
            onChanged = actions.onFontWeightChanged,
        )
    }
}

/**
 * Why hyphenation and letter and word spacing do not apply here, said
 * once for the three of them rather than under each.
 */
@Composable
private fun latinNote(css: ReadingCss, enabled: Boolean): String? = when {
    !enabled -> null
    css == ReadingCss.Rtl -> stringResource(R.string.reader_typography_latin_not_in_rtl)
    css == ReadingCss.Cjk -> stringResource(R.string.reader_typography_latin_not_in_cjk)
    css == ReadingCss.Unknown -> stringResource(R.string.reader_typography_latin_only)
    else -> null
}

@Composable
private fun AlignmentRow(
    value: ReaderTextAlign,
    enabled: Boolean,
    note: String?,
    onChanged: (ReaderTextAlign) -> Unit,
) {
    val options = listOf(
        ReaderTextAlign.DEFAULT to R.string.reader_text_align_default,
        ReaderTextAlign.RAGGED to R.string.reader_text_align_ragged,
        ReaderTextAlign.JUSTIFIED to R.string.reader_text_align_justified,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_text_align))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (align, label) ->
                SegmentedButton(
                    selected = value == align,
                    enabled = enabled,
                    onClick = { onChanged(align) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        note?.let { ReadingSupportingText(it) }
    }
}

@Composable
private fun HyphensRow(
    value: Boolean?,
    enabled: Boolean,
    note: String?,
    onChanged: (Boolean?) -> Unit,
) {
    val options = listOf(
        null to R.string.reader_hyphens_default,
        false to R.string.reader_hyphens_off,
        true to R.string.reader_hyphens_on,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_hyphens))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (choice, label) ->
                SegmentedButton(
                    selected = value == choice,
                    enabled = enabled,
                    onClick = { onChanged(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        note?.let { ReadingSupportingText(it) }
    }
}

/**
 * Weight as a dropdown rather than a fourth segmented row.
 *
 * Four segments do not survive a narrow phone at a large system font
 * scale — the labels wrap to two lines and the row grows taller than the
 * three above it — and this is the row a reader visits least.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontWeightRow(
    value: ReaderFontWeight,
    enabled: Boolean,
    onChanged: (ReaderFontWeight) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReadingSectionLabel(stringResource(R.string.reader_font_weight))
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(value.labelRes()),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                ReaderFontWeight.entries.forEach { weight ->
                    DropdownMenuItem(
                        text = { Text(stringResource(weight.labelRes())) },
                        trailingIcon = {
                            if (weight == value) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            onChanged(weight)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun ReaderFontWeight.labelRes(): Int = when (this) {
    ReaderFontWeight.DEFAULT -> R.string.reader_font_weight_default
    ReaderFontWeight.LIGHT -> R.string.reader_font_weight_light
    ReaderFontWeight.NORMAL -> R.string.reader_font_weight_normal
    ReaderFontWeight.BOLD -> R.string.reader_font_weight_bold
}

/**
 * A spacing that may simply not be set, which is not the same as being
 * set to nothing.
 *
 * An unset spacing leaves the book's own alone; a spacing set to zero is
 * a decision that there should be none, and it switches Readium's
 * advanced styles on like any other. Both rest the thumb at the start of
 * the range, so no drag gets from one to the other and a press that
 * moves nothing is not guaranteed to report itself. Hence the trailing
 * button, which is a two-way toggle: from Default it commits the range
 * start as an explicit value, and from a value it goes back to Default.
 *
 * Committing on release rather than continuously, as the font size
 * slider does: each change reflows the page. Brightness is the exception
 * there, and deliberately so — it is judged by watching the screen while
 * dragging.
 */
@Composable
fun DefaultableSpacingSlider(
    label: String,
    value: Double?,
    range: TypographyRange.Slider,
    enabled: Boolean,
    note: String?,
    onChanged: (Double?) -> Unit,
) {
    var thumb by remember(value) { mutableFloatStateOf(spacingThumb(value, range)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ReadingSectionLabel(label)
            ReadingSectionLabel(spacingLabel(value))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = thumb,
                enabled = enabled,
                onValueChange = { thumb = it },
                onValueChangeFinished = { onChanged(spacingCommit(thumb, range)) },
                valueRange = range.min.toFloat()..range.max.toFloat(),
                steps = range.steps,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = enabled,
                onClick = { onChanged(if (value == null) range.min else null) },
            ) {
                Icon(
                    imageVector = if (value == null) Icons.Outlined.Tune else Icons.Outlined.Restore,
                    contentDescription = stringResource(
                        if (value == null) {
                            R.string.reader_spacing_customize
                        } else {
                            R.string.reader_spacing_reset
                        },
                    ),
                    tint = if (value == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        note?.let { ReadingSupportingText(it) }
    }
}

/**
 * What a spacing reads as.
 *
 * A `Double` cannot hold most of these increments exactly, so the
 * promise that the slider lands on round numbers is kept here, by
 * formatting, rather than by arithmetic — and through [NumberFormat], so
 * a reader whose locale writes `0,15` sees that.
 *
 * The formatter is remembered against the composition's locale rather
 * than held in a static, because a static one is fixed to whichever
 * locale was current when the class loaded and would go on writing
 * `0.15` after the reader switches languages. [NumberFormat] is also
 * not thread-safe, and one per composition is not shared.
 */
@Composable
private fun spacingLabel(value: Double?): String {
    val locale = Locale.current
    val format = remember(locale) {
        NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag(locale.toLanguageTag()))
            .apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 2
            }
    }
    return when {
        value == null -> stringResource(R.string.reader_spacing_value_default)
        value == 0.0 -> stringResource(R.string.reader_spacing_value_none)
        else -> stringResource(R.string.reader_spacing_value, format.format(value))
    }
}
