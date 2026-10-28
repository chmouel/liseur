package com.chmouel.liseur.ui.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.annotations.*
import java.util.UUID

@Composable
fun SelectionToolbarRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.selection_arrange), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ReadingSelectionActions(
    layout: SelectionActionLayout,
    onChanged: ((SelectionActionLayout) -> SelectionActionLayout) -> Unit,
    showTitle: Boolean = true,
) {
    val currentOnChanged by rememberUpdatedState(onChanged)
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingWebsite by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(stringResource(R.string.selection_arrange), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.selection_availability_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            layout.entries.filter { it.style != SelectionActionStyle.HIDDEN }.forEach { entry ->
                Row(Modifier.height(36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.style != SelectionActionStyle.LABEL) Icon(entry.icon, contentDescription = null)
                    if (entry.style != SelectionActionStyle.ICON) Text(
                        entry.customLabel ?: stringResource(requireNotNull(entry.action).label),
                        modifier = Modifier.padding(start = if (entry.style == SelectionActionStyle.BOTH) 6.dp else 0.dp),
                        maxLines = 1, style = selectionLabelStyle(36.dp),
                    )
                }
            }
        }
        layout.entries.forEachIndexed { index, entry ->
            key(entry.id) {
                val name = entry.action?.let { stringResource(it.label) } ?: entry.customLabel.orEmpty()
                var expanded by remember(entry.id) { mutableStateOf(false) }
                var drag by remember(entry.id) { mutableFloatStateOf(0f) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).pointerInput(entry.id) {
                            detectDragGestures(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                                change.consume()
                                drag += amount.y
                                if (kotlin.math.abs(drag) >= 48.dp.toPx()) {
                                    val offset = if (drag > 0) 1 else -1
                                    currentOnChanged { it.moveBy(entry.id, offset) }
                                    drag = 0f
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.DragHandle, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                        entry.customLabel?.takeIf { entry.action != null }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            val styleDescription = "$name, ${stringResource(entry.style.label)}"
                            TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = styleDescription }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(entry.style.label))
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                SelectionActionStyle.entries.filter { entry.action != SelectionAction.DELETE || it != SelectionActionStyle.HIDDEN }.forEach { style ->
                                    DropdownMenuItem(text = { Text(stringResource(style.label)) }, onClick = {
                                        expanded = false
                                        onChanged { it.withStyle(entry.id, style) }
                                    })
                                }
                            }
                        }
                    }
                    IconButton(onClick = { editingId = entry.id }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.selection_edit_action, name))
                    }
                    IconButton(onClick = { onChanged { it.moveBy(entry.id, -1) } }, enabled = index > 0) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.selection_move_up, name))
                    }
                    IconButton(onClick = { onChanged { it.moveBy(entry.id, 1) } }, enabled = index < layout.entries.lastIndex) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.selection_move_down, name))
                    }
                }
            }
        }
        TextButton(onClick = { addingWebsite = true }) { Text(stringResource(R.string.selection_add_website)) }
        TextButton(onClick = { onChanged { it.reset() } }) { Text(stringResource(R.string.selection_reset)) }
    }
    val editing = layout.entries.firstOrNull { it.id == editingId }
    if (addingWebsite || editing != null) {
        SelectionActionEditor(
            entry = editing,
            onDismiss = { editingId = null; addingWebsite = false },
            onSave = { label, template, icon ->
                val id = editing?.id ?: "custom_${UUID.randomUUID()}"
                onChanged {
                    if (editing == null || editing.target is SelectionActionTarget.Website) it.withWebsite(id, label, template, icon)
                    else it.withLabel(id, label).withIcon(id, icon)
                }
                editingId = null
                addingWebsite = false
            },
            onDelete = editing?.takeIf { it.target is SelectionActionTarget.Website }?.let { entry ->
                {
                    onChanged { it.removeWebsite(entry.id) }
                    editingId = null
                }
            },
        )
    }
}

@Composable
private fun SelectionActionEditor(
    entry: SelectionActionEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, SelectionActionIcon?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val website = entry == null || entry.target is SelectionActionTarget.Website
    val defaultLabel = entry?.action?.let { stringResource(it.label) } ?: entry?.customLabel.orEmpty()
    var label by rememberSaveable(entry?.id) { mutableStateOf(entry?.customLabel.orEmpty()) }
    var template by rememberSaveable(entry?.id) {
        mutableStateOf((entry?.target as? SelectionActionTarget.Website)?.urlTemplate.orEmpty())
    }
    var customIcon by rememberSaveable(entry?.id) { mutableStateOf(entry?.customIcon) }
    var submitted by rememberSaveable(entry?.id) { mutableStateOf(false) }
    val nameValid = !website || label.isNotBlank()
    val templateValid = !website || selectionWebsiteTemplate(template) != null
    val save = {
        submitted = true
        if (nameValid && templateValid) onSave(label, template, customIcon)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) stringResource(R.string.selection_add_website)
            else stringResource(R.string.selection_edit_action, defaultLabel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(if (website) R.string.services_server_name else R.string.selection_style_label)) },
                    placeholder = { if (!website) Text(defaultLabel) },
                    supportingText = {
                        if (submitted && !nameValid) Text(stringResource(R.string.selection_name_required))
                        else if (!website) Text(stringResource(R.string.selection_label_help))
                    },
                    isError = submitted && !nameValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = if (website) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                if (website) {
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it },
                        label = { Text(stringResource(R.string.selection_url_template)) },
                        supportingText = { Text(stringResource(if (submitted && !templateValid)
                            R.string.selection_url_invalid else R.string.selection_url_help)) },
                        isError = submitted && !templateValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                } else {
                    TextButton(onClick = { label = "" }) { Text(stringResource(R.string.selection_label_default)) }
                }
                Text(stringResource(R.string.selection_style_icon), style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    maxItemsInEachRow = 4,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SelectionActionIcon.entries.forEach { icon ->
                        val selected = icon == (customIcon ?: entry?.defaultIcon ?: SelectionActionIcon.GLOBE)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { customIcon = icon }),
                        ) {
                            Icon(icon.vector, contentDescription = stringResource(icon.label),
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { customIcon = null }) { Text(stringResource(R.string.selection_icon_default)) }
                onDelete?.let { delete ->
                    TextButton(onClick = delete) { Text(stringResource(R.string.selection_delete_action), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val SelectionActionStyle.label: Int get() = when (this) {
    SelectionActionStyle.ICON -> R.string.selection_style_icon
    SelectionActionStyle.LABEL -> R.string.selection_style_label
    SelectionActionStyle.BOTH -> R.string.selection_style_both
    SelectionActionStyle.HIDDEN -> R.string.selection_style_hidden
}
