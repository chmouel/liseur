package com.chmouel.liseur.providers

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.RemoteResult
import com.chmouel.liseur.data.remote.SyncFailure
import com.chmouel.liseur.hardcover.EditionChip
import com.chmouel.liseur.hardcover.EditionIcon
import com.chmouel.liseur.hardcover.EditionRow
import com.chmouel.liseur.hardcover.EditionSelection
import com.chmouel.liseur.hardcover.HardcoverCandidates
import com.chmouel.liseur.hardcover.HardcoverEdition
import com.chmouel.liseur.hardcover.HardcoverImages
import com.chmouel.liseur.hardcover.HardcoverRef
import com.chmouel.liseur.hardcover.editionRow
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.theme.SystemBarIcons
import com.chmouel.liseur.ui.theme.isDarkSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the picker page opened for: the lookup's validation context and
 * whether choosing also pushes. Immutable and never saved, so a selection
 * can never outlive the account and book generation it was made under.
 */
internal data class PickerSession(val context: HardcoverCandidates, val push: Boolean)

private sealed interface PickerBody {
    data object Idle : PickerBody
    data object Loading : PickerBody
    data class Failed(val reason: SyncFailure, val input: String) : PickerBody
    data class Message(@StringRes val text: Int) : PickerBody
}

/** The page's mutable state, owned by one composition and dropped with it. */
private class PickerState(session: PickerSession) {
    var text by mutableStateOf(session.context.query)
    var results by mutableStateOf(session.context.editions)
    var body by mutableStateOf<PickerBody>(PickerBody.Idle)
    var selection by mutableStateOf(EditionSelection())
    var job: Job? = null
    var turn = 0

    val shownIds: List<Long> get() = results.map { it.id }
}

/**
 * The edition picker as a full-screen page: search field, rows with
 * covers, and one button to confirm the selected row.
 *
 * Nothing is looked up when it opens, and nothing while typing: a lookup
 * starts on the keyboard's Search action, the search button, a pasted
 * link or ID, or Retry, and a newer one cancels the one running.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditionPickerPage(
    session: PickerSession,
    bookLabel: String?,
    currentEditionId: Long?,
    lookup: suspend (String) -> RemoteResult<List<HardcoverEdition>>,
    failureMessage: (SyncFailure) -> Int,
    onChoose: (HardcoverEdition) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember(session) { PickerState(session) }
    val scope = rememberCoroutineScope()
    val loader = HardcoverImages.loader(LocalContext.current)
    val surface = MaterialTheme.colorScheme.surface

    fun search(input: String) {
        val ref = HardcoverRef.parse(input)
        if (ref is HardcoverRef.Blank) return
        state.job?.cancel()
        state.job = null
        val mine = ++state.turn
        state.results = emptyList()
        state.selection = state.selection.resultsChanged()
        when (ref) {
            is HardcoverRef.NotHardcover -> state.body = PickerBody.Message(R.string.hardcover_ref_not_hardcover)
            is HardcoverRef.BadId -> state.body = PickerBody.Message(R.string.hardcover_ref_bad_id)
            else -> {
                state.body = PickerBody.Loading
                state.job = scope.launch {
                    val answer = lookup(input)
                    if (mine != state.turn) return@launch
                    when (answer) {
                        is RemoteResult.Ok -> {
                            state.results = answer.value
                            state.body = PickerBody.Idle
                        }
                        is RemoteResult.Failed -> state.body = PickerBody.Failed(answer.reason, input)
                    }
                    state.selection = state.selection.resultsChanged()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = surface) {
            SystemBarIcons(dark = isDarkSurface(surface))
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.fillMaxSize().widthIn(max = PAGE_MAX_WIDTH)) {
                    PickerTopBar(bookLabel, onDismiss, enabled = !state.selection.submitting)
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = { next ->
                            val before = state.text
                            state.text = next
                            state.selection = state.selection.resultsChanged()
                            if (HardcoverRef.submitsOnPaste(before, next)) search(next)
                        },
                        enabled = !state.selection.submitting,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.hardcover_search_hint), maxLines = 1) },
                        trailingIcon = {
                            Row {
                                if (state.text.isNotEmpty()) {
                                    IconButton(onClick = {
                                        state.job?.cancel()
                                        state.job = null
                                        state.turn++
                                        state.text = ""
                                        state.results = emptyList()
                                        state.body = PickerBody.Idle
                                        state.selection = state.selection.resultsChanged()
                                    }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.hardcover_search_clear),
                                        )
                                    }
                                }
                                IconButton(onClick = { search(state.text) }) {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = stringResource(R.string.hardcover_search_action),
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(state.text) }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PickerResults(
                            state = state,
                            currentEditionId = currentEditionId,
                            failureMessage = failureMessage,
                            onRetry = { input -> search(input) },
                            loader = loader,
                        )
                    }
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            enabled = state.selection.canChoose(state.shownIds),
                            onClick = {
                                val (next, id) = state.selection.submit(state.shownIds)
                                val edition = state.results.firstOrNull { it.id == id }
                                if (edition != null) {
                                    state.selection = next
                                    onChoose(edition)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (session.push) R.string.hardcover_choose_and_push else R.string.hardcover_choose,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerTopBar(bookLabel: String?, onBack: () -> Unit, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onBack, enabled = enabled) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.hardcover_book_choose), style = MaterialTheme.typography.titleLarge)
            if (!bookLabel.isNullOrBlank()) {
                Text(
                    text = bookLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PickerResults(
    state: PickerState,
    currentEditionId: Long?,
    failureMessage: (SyncFailure) -> Int,
    onRetry: (String) -> Unit,
    loader: coil3.ImageLoader,
) {
    when (val body = state.body) {
        PickerBody.Loading -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            BusyIndicator()
        }
        is PickerBody.Failed -> CenteredMessage(stringResource(failureMessage(body.reason))) {
            OutlinedButton(onClick = { onRetry(body.input) }) {
                Text(stringResource(R.string.hardcover_search_retry))
            }
        }
        is PickerBody.Message -> CenteredMessage(stringResource(body.text))
        PickerBody.Idle -> if (state.results.isEmpty()) {
            CenteredMessage(stringResource(R.string.hardcover_search_empty))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                items(state.results, key = { it.id }) { edition ->
                    val row = editionRow(edition, currentEditionId, state.selection.selectedIn(state.shownIds))
                    EditionRowItem(
                        row = row,
                        loader = loader,
                        enabled = !state.selection.submitting,
                        onClick = { state.selection = state.selection.select(edition.id, state.shownIds) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditionRowItem(row: EditionRow, loader: coil3.ImageLoader, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = stringResource(R.string.hardcover_edition_current)
    val selected = stringResource(R.string.hardcover_edition_selected)
    val state = listOfNotNull(
        current.takeIf { row.current },
        selected.takeIf { row.selected },
    ).joinToString(", ")
    val shape = RoundedCornerShape(12.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(if (row.current) colors.secondaryContainer else colors.surface)
            .then(
                if (row.selected) {
                    Modifier.border(BorderStroke(2.dp, colors.primary), shape)
                } else {
                    Modifier
                },
            )
            .selectable(selected = row.selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { if (state.isNotEmpty()) stateDescription = state }
            .padding(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 56.dp, height = 84.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceContainerHighest),
        ) {
            Icon(
                imageVector = if (row.icon == EditionIcon.AUDIOBOOK) Icons.Outlined.Headphones else Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
            if (row.coverUrl != null) {
                AsyncImage(
                    model = row.coverUrl,
                    contentDescription = null,
                    imageLoader = loader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.headline,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.authors?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.chips.forEach { chip ->
                    ChipLabel(
                        when (chip) {
                            is EditionChip.Label -> chip.text
                            is EditionChip.Pages ->
                                pluralStringResource(R.plurals.hardcover_edition_pages, chip.count, chip.count)
                            is EditionChip.Readers ->
                                pluralStringResource(R.plurals.hardcover_edition_readers, chip.count, chip.count)
                        },
                    )
                }
            }
        }
        if (row.current) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary)
        }
    }
}

/** A label that looks like an assist chip but is no action target of its own. */
@Composable
private fun ChipLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private val PAGE_MAX_WIDTH = 720.dp
