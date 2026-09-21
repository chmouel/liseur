package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.FooterField
import com.chmouel.liseur.data.settings.FooterMode
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.reading.label
import com.chmouel.liseur.ui.windowWidth

/** Which footer slot a long press opened the picker for. */
enum class FooterPickTarget { LEFT, MIDDLE, RIGHT }

/**
 * The list a footer slot offers when it is held down.
 *
 * The tap cycle walks the catalog one entry at a time, which is the
 * right gesture for a reader who wants the next thing and the wrong one
 * for a reader who wants the twelfth. This is the way across: the same
 * options, named, with the current one checked.
 *
 * The middle is a [FooterMode] rather than a [FooterField] and has its
 * own shorter catalog — the chapter's name
 * and the smart fallback need the room only the middle has, and hiding
 * the whole footer is a decision that belongs where the footer is
 * described rather than on an edge that would then have nothing left to
 * tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FooterSlotSheet(
    target: FooterPickTarget,
    field: FooterField,
    mode: FooterMode,
    onFieldSelected: (FooterField) -> Unit,
    onModeSelected: (FooterMode) -> Unit,
    onDismiss: () -> Unit,
) {
    LiseurModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(
                    when (target) {
                        FooterPickTarget.LEFT -> R.string.footer_pick_left
                        FooterPickTarget.MIDDLE -> R.string.footer_pick_middle
                        FooterPickTarget.RIGHT -> R.string.footer_pick_right
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.footer_pick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (target == FooterPickTarget.MIDDLE) {
                FooterMode.entries.forEach { entry ->
                    FooterOptionRow(
                        label = stringResource(entry.label),
                        selected = entry == mode,
                        onClick = {
                            onModeSelected(entry)
                            onDismiss()
                        },
                    )
                }
            } else {
                FooterField.entries.forEach { entry ->
                    FooterOptionRow(
                        label = stringResource(entry.label),
                        selected = entry == field,
                        onClick = {
                            onFieldSelected(entry)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FooterOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Selectable rather than clickable: these are one choice
            // out of a list, and the tick is drawn with no description
            // of its own, so a row announced as a button would leave a
            // screen reader no way of saying which one is in force.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
