package com.chmouel.liseur.reader.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.chmouel.liseur.R

/** What the reader can do with a passage they have just selected. */
class SelectionActions(
    val onHighlight: (HighlightTint) -> Unit,
    val onNote: () -> Unit,
    val onSearch: () -> Unit,
    val onLookUp: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: (() -> Unit)? = null,
)

/**
 * The bar of things to do with a selected passage.
 *
 * It is placed just above the selection where there is room and just below
 * it otherwise, so it never covers the words being acted on — the one thing
 * that makes an in-page menu feel wrong.
 */
@Composable
fun SelectionPopup(
    offset: IntOffset,
    actions: SelectionActions,
    activeTint: HighlightTint?,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopCenter,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HighlightTint.entries.forEach { tint ->
                    TintChip(
                        tint = tint,
                        selected = tint == activeTint,
                        onClick = { actions.onHighlight(tint) },
                    )
                }
                PopupAction(
                    label = stringResource(R.string.annotation_note),
                    onClick = actions.onNote,
                )
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
        }
    }
}

@Composable
private fun TintChip(tint: HighlightTint, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(tint.label)
    Row(
        Modifier
            .size(30.dp)
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .background(tint.color)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.Transparent
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {}
}

@Composable
private fun PopupAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
