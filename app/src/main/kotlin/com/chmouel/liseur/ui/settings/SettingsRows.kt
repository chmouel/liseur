package com.chmouel.liseur.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The rows the settings screens are built out of.
 *
 * Shared rather than private to one screen since Settings grew a second
 * page of its own: a switch that looks and behaves differently
 * depending on which page it landed on would be the first thing a
 * reader noticed about the split.
 */

/**
 * A section of the settings: a title over one rounded card, with the
 * rows inside it divided by hairlines.
 *
 * The screen used to put every switch in a card of its own, which read
 * as a stack of boxed islands whose padding changed from section to
 * section. One card per section puts the rows on a single axis and
 * makes the sections themselves the unit of the screen.
 */
@Composable
internal fun SettingsGroup(
    title: String,
    onHelp: (() -> Unit)? = null,
    // Named by whoever offers the help, since only they know what it is
    // about. Optional because most sections have none to offer.
    helpDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        onHelp?.let {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = helpDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(32.dp)
                    .clickable(onClick = it)
                    .padding(6.dp),
            )
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            // One tap target for the whole row, so the switch is not the
            // only thing you are allowed to hit. Toggleable rather than
            // merely clickable, so the row carries its own on-or-off state
            // and a screen reader can say which it is.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
}

/**
 * A row whose answer is one of a few chips — the theme, the e-ink
 * behaviour. Shaped like the other rows so it sits on the same axis:
 * the label where a headline would be, the chips where supporting
 * text would go.
 */
@Composable
internal fun <T> ChipRow(
    title: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelected(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}
