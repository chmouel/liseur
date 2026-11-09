package com.chmouel.liseur.reader.annotations

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.annotations.NoteFormatting.Format
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.theme.SystemBarIcons
import com.chmouel.liseur.ui.theme.isDarkSurface

/**
 * Writing, or reworking, a note about a passage or the book itself.
 *
 * It takes the whole screen, painted in the reading theme, because a note
 * can run to paragraphs and the keyboard already takes half of what is
 * left. The passage stays above the words, folded to a few lines when it
 * is long, with the mark's colour down its side; the colour can be
 * changed here and goes in with the words. A toolbar over the keyboard
 * writes the little Markdown [NoteMarkdown] understands.
 *
 * What was typed is never thrown away by accident: leaving with changes
 * asks first. Saving no words over a note is how a note is taken off, so
 * the action says what it will do: [clearRes].
 *
 * [initialTint] is null for a note about the book, which has no colour.
 */
@Composable
fun NoteEditor(
    passage: String?,
    initialNote: String,
    initialTint: HighlightTint?,
    theme: ReaderTheme,
    palette: HighlightPalette,
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    @StringRes clearRes: Int,
    onSave: (note: String, tint: HighlightTint?) -> Unit,
    onDismiss: () -> Unit,
) {
    val eInk = LocalEInk.current
    val ink = theme.foreground
    val paper = theme.background
    // A wash under half ink dithers to grey on electronic paper.
    val quiet = if (eInk) ink else ink.copy(alpha = 0.6f)

    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialNote, TextRange(initialNote.length)))
    }
    var tintName by rememberSaveable { mutableStateOf(initialTint?.name) }
    val tint = tintName?.let(HighlightTint::fromName)
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }

    val dirty = value.text != initialNote || tint != initialTint
    val clearing = value.text.isBlank() && initialNote.isNotBlank()
    val canSave = value.text.isNotBlank() || clearing
    val close = { if (dirty) confirmingDiscard = true else onDismiss() }
    val save = { onSave(value.text.trimEnd(), tint) }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = paper, contentColor = ink) {
            SystemBarIcons(dark = isDarkSurface(paper))
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close), tint = ink)
                    }
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    TextButton(onClick = save, enabled = canSave) {
                        Text(
                            text = stringResource(if (clearing) clearRes else R.string.save),
                            color = if (canSave) ink else quiet,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    passage?.takeIf { it.isNotBlank() }?.let {
                        Passage(it.trim(), tint?.color ?: quiet, quiet)
                    }
                    if (tint != null) {
                        val chips = palette.chipsFor(tint)
                        if (chips.size > 1) {
                            Row(
                                Modifier
                                    .padding(top = 4.dp)
                                    .horizontalScroll(rememberScrollState())
                                    .selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                chips.forEach { chip ->
                                    TintChip(
                                        tint = chip,
                                        selected = chip == tint,
                                        onClick = { tintName = chip.name },
                                        ringColor = ink,
                                    )
                                }
                            }
                        }
                    }
                    NoteField(
                        value = value,
                        onValueChange = { changed ->
                            val before = NoteFormatting.Edit(value.text, value.selection.min, value.selection.max)
                            val after = NoteFormatting.Edit(changed.text, changed.selection.min, changed.selection.max)
                            value = NoteFormatting.continueList(before, after)?.field() ?: changed
                        },
                        hint = stringResource(hintRes),
                        ink = ink,
                        quiet = quiet,
                    )
                }

                FormatBar(
                    value = value,
                    onChange = { value = it },
                    ink = ink,
                    paper = paper,
                    quiet = quiet,
                    rule = if (eInk) ink else ink.copy(alpha = 0.15f),
                )
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            containerColor = paper,
            titleContentColor = ink,
            textContentColor = quiet,
            title = { Text(stringResource(R.string.annotation_note_discard_title)) },
            text = { Text(stringResource(R.string.annotation_note_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    onDismiss()
                }) { Text(stringResource(R.string.annotation_note_discard), color = ink) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) {
                    Text(stringResource(R.string.annotation_note_keep_editing), color = ink)
                }
            },
        )
    }
}

/** The passage being written about, folded to a few lines until asked for. */
@Composable
private fun Passage(passage: String, stripe: Color, quiet: Color) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(top = 8.dp)
            .height(IntrinsicSize.Min),
    ) {
        Spacer(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(stripe),
        )
        SelectionContainer {
            Text(
                text = passage,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = quiet,
                maxLines = if (expanded) Int.MAX_VALUE else PASSAGE_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
    if (overflows || expanded) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(if (expanded) R.string.annotation_show_less else R.string.annotation_show_more),
                color = quiet,
            )
        }
    }
}

@Composable
private fun NoteField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    hint: String,
    ink: Color,
    quiet: Color,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val style = MaterialTheme.typography.bodyLarge.copy(color = ink)
    val transformation = remember(quiet) { NoteMarkdownTransformation(quiet) }
    CompositionLocalProvider(
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = ink,
            backgroundColor = ink.copy(alpha = 0.25f),
        ),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            cursorBrush = SolidColor(ink),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .padding(top = 16.dp, bottom = 24.dp)
                .focusRequester(focus),
            decorationBox = { field ->
                Box {
                    if (value.text.isEmpty()) Text(hint, style = style, color = quiet)
                    field()
                }
            },
        )
    }
}

/** Bold, italic, the two lists and a quote, over the keyboard. */
@Composable
private fun FormatBar(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    ink: Color,
    paper: Color,
    quiet: Color,
    rule: Color,
) {
    val edit = NoteFormatting.Edit(value.text, value.selection.min, value.selection.max)
    val active = remember(value.text, value.selection) { NoteFormatting.active(edit) }
    HorizontalDivider(color = rule)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FORMATS.forEach { (format, icon, label) ->
            IconToggleButton(
                checked = format in active,
                onCheckedChange = { onChange(NoteFormatting.toggle(edit, format).field()) },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    contentColor = quiet,
                    checkedContainerColor = ink,
                    checkedContentColor = paper,
                ),
            ) {
                Icon(icon, contentDescription = stringResource(label))
            }
        }
    }
}

private fun NoteFormatting.Edit.field(): TextFieldValue = TextFieldValue(text, TextRange(start, end))

private const val PASSAGE_LINES = 4

private val FORMATS: List<Triple<Format, ImageVector, Int>> = listOf(
    Triple(Format.BOLD, Icons.Outlined.FormatBold, R.string.note_format_bold),
    Triple(Format.ITALIC, Icons.Outlined.FormatItalic, R.string.note_format_italic),
    Triple(Format.BULLET, Icons.AutoMirrored.Outlined.FormatListBulleted, R.string.note_format_bullets),
    Triple(Format.NUMBERED, Icons.Outlined.FormatListNumbered, R.string.note_format_numbers),
    Triple(Format.QUOTE, Icons.Outlined.FormatQuote, R.string.note_format_quote),
)
