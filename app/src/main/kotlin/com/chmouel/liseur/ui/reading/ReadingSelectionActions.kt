package com.chmouel.liseur.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.annotations.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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

private const val ZONE_DIVIDER = "selection_zone_divider"

/**
 * The toolbar editor: actions in the toolbar on top, hidden ones below, and
 * a drag handle on each row to reorder or move it across the divider. The
 * order lives in a draft while a finger is down so the list follows the drag,
 * and is committed to the layout once the finger lifts.
 */
@Composable
fun ReadingSelectionActions(
    layout: SelectionActionLayout,
    onChanged: ((SelectionActionLayout) -> SelectionActionLayout) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    showTitle: Boolean = true,
    showReset: Boolean = true,
) {
    val currentOnChanged by rememberUpdatedState(onChanged)
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingWebsite by rememberSaveable { mutableStateOf(false) }
    val byId = layout.entries.associateBy { it.id }
    val committed = layout.shown.map { it.id } + ZONE_DIVIDER + layout.hidden.map { it.id }
    var draft by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(committed) { draft = null }
    val order = draft ?: committed
    val divider = order.indexOf(ZONE_DIVIDER)
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val current = draft ?: committed
        val fromIndex = current.indexOf(from.key)
        val toIndex = current.indexOf(to.key)
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        val next = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        if (next.indexOf(SelectionAction.DELETE.id) > next.indexOf(ZONE_DIVIDER)) return@rememberReorderableLazyListState
        draft = next
    }
    val commit = { id: String ->
        draft?.let { current ->
            val zoneStart = current.indexOf(ZONE_DIVIDER)
            val index = current.indexOf(id)
            if (index >= 0) currentOnChanged {
                if (index > zoneStart) it.moveTo(id, index - zoneStart - 1, toHidden = true)
                else it.moveTo(id, index, toHidden = false)
            }
        }
        Unit
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = contentPadding) {
        if (showTitle) item(key = "title") {
            Text(stringResource(R.string.selection_arrange), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        item(key = "help") {
            Text(stringResource(R.string.selection_arrange_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "preview") {
            SelectionPreview(order.take(divider).mapNotNull(byId::get), Modifier.padding(top = 16.dp, bottom = 24.dp))
        }
        item(key = "toolbar_header") {
            ZoneHeader(stringResource(R.string.selection_zone_toolbar), divider)
        }
        itemsIndexed(order, key = { _, id -> id }) { index, id ->
            ReorderableItem(reorder, key = id) { dragging ->
                if (id == ZONE_DIVIDER) {
                    ZoneDivider(hiddenCount = order.size - divider - 1, onAddWebsite = { addingWebsite = true })
                } else {
                    val entry = byId[id] ?: return@ReorderableItem
                    val hidden = index > divider
                    ActionRow(
                        entry = entry,
                        hidden = hidden,
                        first = index == 0 || index == divider + 1,
                        last = hidden && index == order.lastIndex,
                        dragging = dragging,
                        dragHandle = Modifier.draggableHandle(
                            onDragStarted = {
                                draft = order
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = { commit(id) },
                        ),
                        onClick = { editingId = id },
                    )
                }
            }
        }
        item(key = "availability") {
            Text(stringResource(R.string.selection_availability_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
        if (showReset) item(key = "reset") {
            TextButton(onClick = { onChanged { it.reset() } }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.selection_reset))
            }
        }
    }
    val editing = layout.entries.firstOrNull { it.id == editingId }
    if (addingWebsite || editing != null) {
        SelectionActionEditor(
            entry = editing,
            onDismiss = { editingId = null; addingWebsite = false },
            onSave = { label, template, icon, style ->
                val id = editing?.id ?: "custom_${UUID.randomUUID()}"
                onChanged {
                    val updated = if (editing == null || editing.target is SelectionActionTarget.Website) it.withWebsite(id, label, template, icon)
                        else it.withLabel(id, label).withIcon(id, icon)
                    if (style != null) updated.withStyle(id, style) else updated
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

/** A stand-in for the popup that opens over selected text, built from the actions in the toolbar. */
@Composable
private fun SelectionPreview(entries: List<SelectionActionEntry>, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val sample = stringResource(R.string.reader_preview_body)
            Text(
                buildAnnotatedString {
                    val start = sample.indexOfFirst { it == ',' || it == '，' } + 1
                    val rest = sample.drop(start)
                    val gap = rest.takeWhile(Char::isWhitespace)
                    append(sample.take(start) + gap)
                    withStyle(SpanStyle(background = HighlightTint.YELLOW.color.copy(alpha = 0.45f))) { append(rest.drop(gap.length)) }
                },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = MaterialTheme.typography.headlineSmall.fontFamily, fontStyle = FontStyle.Italic,
                ),
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(HighlightTint.YELLOW, HighlightTint.GREEN, HighlightTint.PINK).forEach {
                        Box(Modifier.padding(horizontal = 3.dp).size(16.dp).clip(CircleShape).background(it.color))
                    }
                    Box(Modifier.padding(horizontal = 8.dp).size(width = 1.dp, height = 22.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant))
                    entries.forEach { entry ->
                        Row(Modifier.height(40.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (entry.style != SelectionActionStyle.LABEL) Icon(entry.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            if (entry.style != SelectionActionStyle.ICON) Text(
                                entry.customLabel ?: stringResource(requireNotNull(entry.action).label),
                                modifier = Modifier.padding(start = if (entry.style == SelectionActionStyle.BOTH) 6.dp else 0.dp),
                                maxLines = 1, style = selectionLabelStyle(36.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp))
    }
}

/** Closes the toolbar card with "Add website action" and opens the hidden zone; dragging over it crosses zones. */
@Composable
private fun ZoneDivider(hiddenCount: Int, onAddWebsite: () -> Unit) {
    Column {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onAddWebsite)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.selection_add_website), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        ZoneHeader(stringResource(R.string.selection_style_hidden), hiddenCount, Modifier.padding(bottom = 0.dp))
        Text(stringResource(R.string.selection_zone_hidden_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        if (hiddenCount == 0) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            )
        }
    }
}

@Composable
private fun ActionRow(
    entry: SelectionActionEntry,
    hidden: Boolean,
    first: Boolean,
    last: Boolean,
    dragging: Boolean,
    dragHandle: Modifier,
    onClick: () -> Unit,
) {
    val name = entry.action?.let { stringResource(it.label) } ?: entry.customLabel.orEmpty()
    val caption = listOfNotNull(
        entry.customLabel.takeIf { entry.action != null },
        stringResource(R.string.selection_website).takeIf { entry.target is SelectionActionTarget.Website },
        stringResource(R.string.selection_cannot_hide).takeIf { entry.action == SelectionAction.DELETE },
    ).joinToString(" · ")
    val top = if (first || dragging) 16.dp else 0.dp
    val bottom = if (last || dragging) 16.dp else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val content = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fillMaxWidth()
            .shadow(if (dragging) 8.dp else 0.dp, shape)
            .clip(shape)
            .background(if (hidden) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (!first && !dragging) HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(entry.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption.isNotEmpty()) Text(caption, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!hidden) Surface(
                shape = CircleShape,
                color = if (entry.style == SelectionActionStyle.ICON) MaterialTheme.colorScheme.surfaceContainerHighest
                    else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 128.dp),
            ) {
                Text(stringResource(entry.style.label), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Box(dragHandle.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DragHandle, contentDescription = stringResource(R.string.selection_drag_action, name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectionActionEditor(
    entry: SelectionActionEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, SelectionActionIcon?, SelectionActionStyle?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val website = entry == null || entry.target is SelectionActionTarget.Website
    val defaultLabel = entry?.action?.let { stringResource(it.label) } ?: entry?.customLabel.orEmpty()
    var label by rememberSaveable(entry?.id) { mutableStateOf(entry?.customLabel.orEmpty()) }
    var templateField by rememberSaveable(entry?.id, stateSaver = TextFieldValue.Saver) {
        val initial = (entry?.target as? SelectionActionTarget.Website)?.urlTemplate.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val template = templateField.text
    var customIcon by rememberSaveable(entry?.id) { mutableStateOf(entry?.customIcon) }
    var style by rememberSaveable(entry?.id) {
        mutableStateOf(if (entry == null) SelectionActionStyle.BOTH else entry.style.takeIf { it != SelectionActionStyle.HIDDEN })
    }
    var submitted by rememberSaveable(entry?.id) { mutableStateOf(false) }
    val nameValid = !website || label.isNotBlank()
    val templateValid = !website || selectionWebsiteTemplate(template) != null
    val save = {
        submitted = true
        if (nameValid && templateValid) onSave(label, template, customIcon, style)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) stringResource(R.string.selection_add_website)
            else stringResource(R.string.selection_edit_action, defaultLabel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.selection_show_as), style = MaterialTheme.typography.titleSmall)
                val styles = listOf(SelectionActionStyle.ICON, SelectionActionStyle.LABEL, SelectionActionStyle.BOTH)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Min)) {
                    styles.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = style == option,
                            onClick = { style = option },
                            shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                            icon = {},
                            modifier = Modifier.fillMaxHeight(),
                        ) {
                            Text(stringResource(option.label), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    stringResource(if (style == null) R.string.selection_hidden_pick_style else R.string.selection_show_as_help),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
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
                        value = templateField,
                        onValueChange = { templateField = it },
                        label = { Text(stringResource(R.string.selection_url_template)) },
                        supportingText = { Text(stringResource(if (submitted && !templateValid)
                            R.string.selection_url_invalid else R.string.selection_url_help)) },
                        isError = submitted && !templateValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SELECTION_WEB_PLACEHOLDERS.forEach { name ->
                            val token = "{$name}"
                            val insert = stringResource(R.string.selection_insert_placeholder, token)
                            SuggestionChip(
                                onClick = {
                                    val at = templateField.selection
                                    templateField = TextFieldValue(
                                        templateField.text.replaceRange(at.min, at.max, token),
                                        TextRange(at.min + token.length),
                                    )
                                },
                                label = { Text(token, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.semantics { contentDescription = insert },
                            )
                        }
                    }
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
