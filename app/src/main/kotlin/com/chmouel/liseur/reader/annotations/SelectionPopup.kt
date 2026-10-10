package com.chmouel.liseur.reader.annotations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
    val onDelete: (() -> Unit)? = null,
    /** Translates the passage, when a service can. */
    val onTranslate: (() -> Unit)? = null,
    /** Reads aloud from here, where the build can. */
    val onReadAloud: (() -> Unit)? = null,
    /** False while the page shows a translation, whose words a highlight or note could not keep. */
    val annotate: Boolean = true,
) {
    /** Whether there is anything behind More; with nothing there, it is not drawn. */
    val hasMore: Boolean get() = onTranslate != null || onReadAloud != null
}

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
 * Read aloud and Translate wait behind More, which swaps the row for
 * theirs at the same height. A menu would be a second window, whose taps
 * the bar could take for a touch outside it; a taller bar would cover
 * the selection its placement keeps clear. [selectionKey] puts the main
 * row back whenever the passage changes.
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
    selectionKey: Any,
    onDismiss: () -> Unit,
    dismissOnOutsideTouch: Boolean = true,
) {
    var more by remember(selectionKey) { mutableStateOf(false) }
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
            if (more) {
                MoreRow(actions, onBack = { more = false })
                return@Surface
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val chips = if (actions.annotate) palette.chipsFor(activeTint) else emptyList()
                chips.forEach { tint ->
                    TintChip(
                        tint = tint,
                        selected = tint == activeTint,
                        onClick = { actions.onHighlight(tint) },
                    )
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
                    if (actions.annotate) {
                        if (palette.isEmpty) {
                            PopupAction(
                                label = stringResource(R.string.annotation_highlight),
                                onClick = { actions.onHighlight(palette.default) },
                            )
                        }
                        PopupAction(
                            label = stringResource(R.string.annotation_note),
                            onClick = actions.onNote,
                        )
                    }
                    PopupAction(
                        label = stringResource(R.string.annotation_look_up),
                        onClick = actions.onLookUp,
                    )
                    IconButton(onClick = actions.onSearch, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.annotation_search),
                        )
                    }
                    IconButton(onClick = actions.onShare, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.annotation_share),
                        )
                    }
                    actions.onDelete?.let { delete ->
                        IconButton(onClick = delete, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.annotation_delete),
                            )
                        }
                    }
                }
                if (actions.hasMore) {
                    IconButton(onClick = { more = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.selection_more_actions),
                        )
                    }
                }
            }
        }
    }
}

/** What More opens: the actions that are not about marking the passage. */
@Composable
private fun MoreRow(actions: SelectionActions, onBack: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Row(
            Modifier
                .weight(1f, fill = false)
                .horizontalScroll(rememberScrollState())
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.onReadAloud?.let {
                PopupAction(
                    label = stringResource(R.string.selection_read_aloud),
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    description = stringResource(R.string.read_aloud_from_here),
                    onClick = it,
                )
            }
            actions.onTranslate?.let {
                PopupAction(
                    label = stringResource(R.string.translation_action),
                    icon = Icons.Outlined.Translate,
                    onClick = it,
                )
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
    Row(
        Modifier
            .size(30.dp)
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .background(tint.color)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) ringColor else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {}
}

@Composable
private fun PopupAction(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick, role = Role.Button)
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
