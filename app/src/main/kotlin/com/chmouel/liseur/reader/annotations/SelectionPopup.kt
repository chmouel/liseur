package com.chmouel.liseur.reader.annotations

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk

/** What the reader can do with a passage they have just selected. */
class SelectionActions(
    val onHighlight: (HighlightTint) -> Unit,
    val onNote: () -> Unit,
    val onSearch: () -> Unit,
    val onLookUp: () -> Unit,
    val onShare: () -> Unit,
    val layout: SelectionActionLayout = SelectionActionLayout.default,
    val onCopy: (() -> Unit)? = null,
    val onCitation: (() -> Unit)? = null,
    val onOpenWith: (() -> Unit)? = null,
    val onWebSearch: (() -> Unit)? = null,
    val onWebsite: ((SelectionActionEntry) -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    /** Translates the passage, when a service can. */
    val onTranslate: (() -> Unit)? = null,
    /** The translate button's label, as the chosen service's terms word it. */
    @param:StringRes val translateLabel: Int = R.string.translation_action,
    /** Reads aloud from here, where the build can. */
    val onReadAloud: (() -> Unit)? = null,
    /** Describes the selected character from the chapters before this one. */
    val onCompanion: (() -> Unit)? = null,
    /** False while the page shows a translation, whose words a highlight or note could not keep. */
    val annotate: Boolean = true,
)

/**
 * The bar of things to do with a selected passage.
 *
 * It is placed just above the selection where there is room and just below
 * it otherwise, so it never covers the words being acted on — the one thing
 * that makes an in-page menu feel wrong.
 *
 * [palette] is how much of the bar is colours. It is the reader's to
 * set, because six chips and five actions over the passage they are
 * trying to look at is more bar than passage on a phone. When it offers
 * none, a plain Highlight takes their place: a bar with no way to mark
 * a passage would be a regression wearing a setting's clothes.
 *
 * [dismissOnOutsideTouch] must be off while the web view holds a live
 * selection. Its handles are windows of their own, so grabbing one is a
 * touch outside this bar, and dismissing then clears the selection out
 * from under the finger dragging it (#257). The web view already lets
 * the selection go on a tap elsewhere, and the bar follows it.
 */
@Composable
fun SelectionPopup(
    offset: IntOffset,
    actions: SelectionActions,
    activeTint: HighlightTint?,
    palette: HighlightPalette,
    onDismiss: () -> Unit,
    dismissOnOutsideTouch: Boolean = true,
) {
    Popup(
        alignment = Alignment.TopCenter,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = dismissOnOutsideTouch,
        ),
    ) {
        // Electronic paper has no way to draw a soft shadow: it arrives as
        // a band of dithered grey that costs a repaint and reads as dirt.
        // An outline says the same thing — this floats over the page — in
        // ink the panel can actually print.
        val eInk = LocalEInk.current
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = if (eInk) 0.dp else 3.dp,
            shadowElevation = if (eInk) 0.dp else 8.dp,
            border = if (eInk) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            } else {
                null
            },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            SelectionActionRow(
                actions = actions,
                activeTint = activeTint,
                palette = palette,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The colours and actions of [SelectionPopup], laid out as a full-width tonal
 * bar at the foot of the definition sheet a long press opens. That sheet is
 * the look-up already, so Define is omitted and the remaining actions spread
 * across the width of the card.
 */
@Composable
fun SheetSelectionActionBar(
    actions: SelectionActions,
    activeTint: HighlightTint?,
    palette: HighlightPalette,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInk.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (eInk) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (eInk) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val paletteMaxWidth = maxWidth / 2
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chips = if (actions.annotate) palette.chipsFor(activeTint) else emptyList()
                if (chips.isNotEmpty()) {
                    Row(
                        Modifier
                            .widthIn(max = paletteMaxWidth)
                            .padding(horizontal = 4.dp)
                            .horizontalScroll(rememberScrollState())
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        chips.forEach { tint ->
                            TintChip(
                                tint = tint,
                                selected = tint == activeTint,
                                onClick = { actions.onHighlight(tint) },
                            )
                        }
                    }
                    Box(
                        Modifier
                            .padding(start = 8.dp, end = 4.dp)
                            .size(width = 1.dp, height = 22.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .widthIn(min = maxWidth)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ActionButtons(
                            actions = actions,
                            palette = palette,
                            lookUp = false,
                            buttonSize = 44.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectionActionRow(
    actions: SelectionActions,
    activeTint: HighlightTint?,
    palette: HighlightPalette,
    modifier: Modifier = Modifier,
    lookUp: Boolean = true,
) {
    BoxWithConstraints(modifier) {
        val paletteMaxWidth = maxWidth / 2
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val chips = if (actions.annotate) palette.chipsFor(activeTint) else emptyList()
            // The colours are one choice among themselves, not among the
            // actions beside them, so they are grouped on their own.
            if (chips.isNotEmpty()) {
                Row(
                    Modifier
                        .widthIn(max = paletteMaxWidth)
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chips.forEach { tint ->
                        TintChip(
                            tint = tint,
                            selected = tint == activeTint,
                            onClick = { actions.onHighlight(tint) },
                        )
                    }
                }
            }
            // On a narrow phone with large text the actions outgrow the
            // bar; they scroll behind the chips rather than fall off it.
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionButtons(
                    actions = actions,
                    palette = palette,
                    lookUp = lookUp,
                    buttonSize = 36.dp,
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    actions: SelectionActions,
    palette: HighlightPalette,
    lookUp: Boolean,
    buttonSize: androidx.compose.ui.unit.Dp,
) {
    if (actions.annotate && palette.isEmpty) {
        PopupAction(label = stringResource(R.string.annotation_highlight), buttonSize = buttonSize, onClick = { actions.onHighlight(palette.default) })
    }
    actions.layout.entries.forEach { entry ->
        val action = entry.action
        val callback = when (action) {
            SelectionAction.NOTE -> actions.onNote.takeIf { actions.annotate }
            SelectionAction.LOOK_UP -> actions.onLookUp.takeIf { lookUp }
            SelectionAction.COMPANION -> actions.onCompanion
            SelectionAction.SEARCH -> actions.onSearch
            SelectionAction.DELETE -> actions.onDelete
            SelectionAction.TRANSLATE -> actions.onTranslate
            SelectionAction.READ_ALOUD -> actions.onReadAloud
            SelectionAction.SHARE -> actions.onShare
            SelectionAction.COPY -> actions.onCopy
            SelectionAction.CITATION -> actions.onCitation
            SelectionAction.OPEN_WITH -> actions.onOpenWith
            SelectionAction.WEB_SEARCH -> actions.onWebSearch
            SelectionAction.WIKIPEDIA, SelectionAction.OPEN_LIBRARY, null -> actions.onWebsite?.let { open -> { open(entry) } }
        }
        if (callback != null && entry.style != SelectionActionStyle.HIDDEN) {
            val label = entry.customLabel ?: stringResource(if (action == SelectionAction.TRANSLATE) actions.translateLabel else requireNotNull(action).label)
            Row(
                Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = callback)
                    .semantics { if (entry.style == SelectionActionStyle.ICON) contentDescription = label }
                    .padding(horizontal = if (entry.style == SelectionActionStyle.ICON) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.style != SelectionActionStyle.LABEL) Icon(entry.icon, contentDescription = null)
                if (entry.style != SelectionActionStyle.ICON) {
                    Text(label, modifier = Modifier.padding(start = if (entry.style == SelectionActionStyle.BOTH) 6.dp else 0.dp),
                        style = selectionLabelStyle(buttonSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


@Composable
internal fun TintChip(
    tint: HighlightTint,
    selected: Boolean,
    onClick: () -> Unit,
    ringColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val label = stringResource(tint.label)
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(tint.color)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) ringColor else Color.Transparent,
                shape = CircleShape,
            ),
        )
    }
}

@Composable
private fun PopupAction(
    label: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = selectionLabelStyle(buttonSize), maxLines = 1)
    }
}

@Composable
internal fun selectionLabelStyle(height: Dp): TextStyle {
    val style = MaterialTheme.typography.labelLarge
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    val scale = (height / lineHeight).coerceAtMost(1f)
    return style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
}

internal val SelectionAction.label: Int get() = when (this) {
    SelectionAction.NOTE -> R.string.annotation_note
    SelectionAction.LOOK_UP -> R.string.annotation_look_up
    SelectionAction.COMPANION -> R.string.companion_action
    SelectionAction.SEARCH -> R.string.reader_search
    SelectionAction.DELETE -> R.string.annotation_delete
    SelectionAction.TRANSLATE -> R.string.translation_action
    SelectionAction.READ_ALOUD -> R.string.read_aloud_from_here
    SelectionAction.SHARE -> R.string.annotation_share
    SelectionAction.COPY -> R.string.selection_copy
    SelectionAction.CITATION -> R.string.selection_citation
    SelectionAction.OPEN_WITH -> R.string.selection_open_with
    SelectionAction.WEB_SEARCH -> R.string.selection_web_search
    SelectionAction.WIKIPEDIA -> R.string.selection_wikipedia
    SelectionAction.OPEN_LIBRARY -> R.string.selection_open_library
}

internal val SelectionActionEntry.icon: ImageVector get() = (customIcon ?: defaultIcon).vector

internal val SelectionActionIcon.vector: ImageVector get() = when (this) {
    SelectionActionIcon.NOTE -> Icons.Outlined.EditNote
    SelectionActionIcon.BOOK -> Icons.AutoMirrored.Outlined.MenuBook
    SelectionActionIcon.COMPANION -> Icons.Outlined.PersonSearch
    SelectionActionIcon.SEARCH -> Icons.Outlined.Search
    SelectionActionIcon.DELETE -> Icons.Outlined.Delete
    SelectionActionIcon.TRANSLATE -> Icons.Outlined.Translate
    SelectionActionIcon.READ_ALOUD -> Icons.AutoMirrored.Outlined.VolumeUp
    SelectionActionIcon.SHARE -> Icons.Outlined.Share
    SelectionActionIcon.COPY -> Icons.Outlined.ContentCopy
    SelectionActionIcon.QUOTE -> Icons.Outlined.FormatQuote
    SelectionActionIcon.OPEN_WITH -> Icons.AutoMirrored.Outlined.OpenInNew
    SelectionActionIcon.GLOBE -> Icons.Outlined.Public
    SelectionActionIcon.STAR -> Icons.Outlined.StarBorder
    SelectionActionIcon.BOOKMARK -> Icons.Outlined.BookmarkBorder
    SelectionActionIcon.HEART -> Icons.Outlined.FavoriteBorder
    SelectionActionIcon.CHECK -> Icons.Outlined.Check
}

internal val SelectionActionIcon.label: Int get() = when (this) {
    SelectionActionIcon.NOTE -> R.string.annotation_note
    SelectionActionIcon.BOOK -> R.string.annotation_look_up
    SelectionActionIcon.COMPANION -> R.string.companion_action
    SelectionActionIcon.SEARCH -> R.string.reader_search
    SelectionActionIcon.DELETE -> R.string.annotation_delete
    SelectionActionIcon.TRANSLATE -> R.string.translation_action
    SelectionActionIcon.READ_ALOUD -> R.string.read_aloud_from_here
    SelectionActionIcon.SHARE -> R.string.annotation_share
    SelectionActionIcon.COPY -> R.string.selection_copy
    SelectionActionIcon.QUOTE -> R.string.selection_citation
    SelectionActionIcon.OPEN_WITH -> R.string.selection_open_with
    SelectionActionIcon.GLOBE -> R.string.selection_web_search
    SelectionActionIcon.STAR -> R.string.selection_icon_star
    SelectionActionIcon.BOOKMARK -> R.string.reader_bookmarks
    SelectionActionIcon.HEART -> R.string.selection_icon_heart
    SelectionActionIcon.CHECK -> R.string.selection_icon_check
}
