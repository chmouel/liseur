package com.chmouel.liseur.ui.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    var editingAction by rememberSaveable { mutableStateOf<SelectionAction?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(stringResource(R.string.selection_arrange), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.selection_availability_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            layout.entries.filter { it.style != SelectionActionStyle.HIDDEN }.forEach { entry ->
                Row(Modifier.height(36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.style != SelectionActionStyle.LABEL) Icon(entry.action.icon, contentDescription = null)
                    if (entry.style != SelectionActionStyle.ICON) Text(
                        entry.customLabel ?: stringResource(entry.action.label),
                        modifier = Modifier.padding(start = if (entry.style == SelectionActionStyle.BOTH) 6.dp else 0.dp),
                        maxLines = 1, style = selectionLabelStyle(36.dp),
                    )
                }
            }
        }
        layout.entries.forEachIndexed { index, entry ->
            key(entry.action) {
                var expanded by remember(entry.action) { mutableStateOf(false) }
                var drag by remember(entry.action) { mutableFloatStateOf(0f) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).pointerInput(entry.action) {
                            detectDragGestures(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                                change.consume()
                                drag += amount.y
                                if (kotlin.math.abs(drag) >= 48.dp.toPx()) {
                                    val offset = if (drag > 0) 1 else -1
                                    currentOnChanged { it.moveBy(entry.action, offset) }
                                    drag = 0f
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.DragHandle, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(entry.action.label), style = MaterialTheme.typography.bodyMedium)
                        entry.customLabel?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            val styleDescription = "${stringResource(entry.action.label)}, ${stringResource(entry.style.label)}"
                            TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = styleDescription }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(entry.style.label))
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                SelectionActionStyle.entries.filter { entry.action != SelectionAction.DELETE || it != SelectionActionStyle.HIDDEN }.forEach { style ->
                                    DropdownMenuItem(text = { Text(stringResource(style.label)) }, onClick = {
                                        expanded = false
                                        onChanged { it.withStyle(entry.action, style) }
                                    })
                                }
                            }
                        }
                    }
                    IconButton(onClick = { editingAction = entry.action }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.selection_edit_label, stringResource(entry.action.label)))
                    }
                    IconButton(onClick = { onChanged { it.moveBy(entry.action, -1) } }, enabled = index > 0) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.selection_move_up, stringResource(entry.action.label)))
                    }
                    IconButton(onClick = { onChanged { it.moveBy(entry.action, 1) } }, enabled = index < layout.entries.lastIndex) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.selection_move_down, stringResource(entry.action.label)))
                    }
                }
            }
        }
        TextButton(onClick = { onChanged { SelectionActionLayout.default } }) { Text(stringResource(R.string.selection_reset)) }
    }
    editingAction?.let { action ->
        val entry = layout.entries.first { it.action == action }
        val defaultLabel = stringResource(action.label)
        var label by rememberSaveable(action) { mutableStateOf(entry.customLabel.orEmpty()) }
        val focusRequester = remember { FocusRequester() }
        val save = {
            val savedLabel = label
            onChanged { it.withLabel(action, savedLabel) }
            editingAction = null
        }
        AlertDialog(
            onDismissRequest = { editingAction = null },
            title = { Text(stringResource(R.string.selection_edit_label, defaultLabel)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.selection_style_label)) },
                        placeholder = { Text(defaultLabel) },
                        supportingText = { Text(stringResource(R.string.selection_label_help)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                    TextButton(onClick = { label = "" }) { Text(stringResource(R.string.selection_label_default)) }
                }
            },
            confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editingAction = null }) { Text(stringResource(R.string.cancel)) } },
        )
        LaunchedEffect(action) { focusRequester.requestFocus() }
    }
}

private val SelectionActionStyle.label: Int get() = when (this) {
    SelectionActionStyle.ICON -> R.string.selection_style_icon
    SelectionActionStyle.LABEL -> R.string.selection_style_label
    SelectionActionStyle.BOTH -> R.string.selection_style_both
    SelectionActionStyle.HIDDEN -> R.string.selection_style_hidden
}
