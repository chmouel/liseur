package com.chmouel.liseur.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.SeriesExtras
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.SeriesVolume
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * One series: what is in it, in order, and where the reader is in it.
 *
 * The screen is built to answer one question at the top — what do I read
 * next — and to answer it with a button rather than with a list to be
 * searched. Everything below is for the times that is not the question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    shelf: SeriesShelf,
    downloads: Map<String, DownloadProgress>,
    extras: SeriesExtras?,
    deleteFailures: Flow<DeleteFailure>,
    /** Whether the account behind the catalog may fetch files at all. */
    canDownload: Boolean,
    onBack: () -> Unit,
    onVolumeSelected: (Book) -> Unit,
    onVolumeLongPress: (Book) -> Unit,
    onDownloadMissing: () -> Unit,
    onMarkSeriesRead: () -> Unit,
    onArchiveSeries: () -> Unit,
    /** The draft order, or null when the shelf is only being read. */
    reorder: SeriesReorder?,
    onStartReorder: () -> Unit,
    onMoveVolume: (from: Int, to: Int) -> Unit,
    onCommitReorder: () -> Unit,
    onCancelReorder: () -> Unit,
    /** Whether anything on this shelf carries a number set by hand. */
    hasCustomNumbers: Boolean,
    onClearCustomNumbers: () -> Unit,
    notice: Notice?,
    onNoticeShown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val downloadsNotAllowed = stringResource(R.string.downloads_not_allowed)
    val serverDeleteFailed = stringResource(R.string.delete_from_server_failed)
    val localDeleteFailed = stringResource(R.string.delete_local_failed)
    val reordering = reorder != null

    // Back leaves the mode before it leaves the screen: a draft is
    // unsaved work, and the gesture that discards it should be the one
    // that says so.
    BackHandler(enabled = reordering) { onCancelReorder() }

    // The same message the library shows, shown here too: a refused
    // commit leaves the reader on this screen, and a snackbar that
    // waits for them to navigate away explains nothing.
    val seriesChanged = stringResource(R.string.series_reorder_changed)
    LaunchedEffect(notice) {
        val pending = notice ?: return@LaunchedEffect
        when (pending.kind) {
            NoticeKind.SeriesChangedWhileReordering -> snackbarHost.showSnackbar(seriesChanged)
        }
        onNoticeShown(pending.id)
    }

    LaunchedEffect(deleteFailures) {
        deleteFailures.collect { failure ->
            val message = if (failure.onServer) serverDeleteFailed else localDeleteFailed
            snackbarHost.showSnackbar(message.format(failure.book.title))
        }
    }

    // A volume that is only on the server needs an account allowed to
    // fetch it. Queueing work that is known to fail and saying nothing
    // is how a tap turns into a book that never arrives.
    val open: (Book) -> Unit = { book ->
        if (book.openableUrl == null && !canDownload) {
            scope.launch { snackbarHost.showSnackbar(downloadsNotAllowed) }
        } else {
            onVolumeSelected(book)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = shelf.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (reordering) onCancelReorder else onBack) {
                        Icon(
                            if (reordering) {
                                Icons.Outlined.Close
                            } else {
                                Icons.AutoMirrored.Outlined.ArrowBack
                            },
                            contentDescription = stringResource(
                                if (reordering) R.string.cancel else R.string.back,
                            ),
                        )
                    }
                },
                actions = {
                    if (reordering) {
                        TextButton(
                            onClick = onCommitReorder,
                            enabled = !reorder.saving,
                        ) {
                            Text(stringResource(R.string.done))
                        }
                    } else {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.series_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                if (shelf.missing.isNotEmpty() && canDownload) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.series_download_missing))
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onDownloadMissing()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.series_mark_read)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onMarkSeriesRead()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.series_reorder)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.SwapVert, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onStartReorder()
                                    },
                                )
                                if (hasCustomNumbers) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.series_clear_numbers))
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Restore, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            confirmClear = true
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.series_archive)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Inventory2, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onArchiveSeries()
                                    },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (reorder != null) {
            ReorderableVolumes(
                shelf = shelf,
                order = reorder.order,
                onMove = onMoveVolume,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SeriesHero(shelf, extras, open) }
            item {
                SeriesRail(
                    shelf = shelf,
                    extras = extras,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
            items(shelf.volumes, key = { it.book.id }) { volume ->
                // The numbers a series skips are drawn above the volume
                // that follows them, so a hole in a shelf is seen where
                // the hole is rather than in a list of complaints at the
                // bottom of the screen.
                gapsBefore(shelf, volume).forEach { missing ->
                    MissingVolumeRow(missing, published = true)
                }
                SeriesVolumeRow(
                    volume = volume,
                    progress = downloads[volume.book.url],
                    onClick = { open(volume.book) },
                    onLongClick = { onVolumeLongPress(volume.book) },
                )
            }
            // Volumes a server says exist and the library has never seen.
            // Only ever shown when a server has actually counted them:
            // guessing that a series is incomplete because it stops at
            // four is how a shelf starts nagging.
            items(unpublishedTail(shelf, extras)) { number ->
                MissingVolumeRow(number, published = false)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.series_clear_numbers)) },
            text = { Text(stringResource(R.string.series_clear_numbers_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearCustomNumbers()
                    },
                ) {
                    Text(stringResource(R.string.series_clear_numbers_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * The shelf, and nothing else, while it is being put in order.
 *
 * The hero, the Continue button, the missing-volume rail, the inferred
 * gaps and the unpublished tail are all gone. Every one of them is
 * computed from the numbers that are about to be replaced, so leaving
 * them up would mean answering "what do I read next" from an order the
 * reader is in the middle of disagreeing with.
 */
@Composable
private fun ReorderableVolumes(
    shelf: SeriesShelf,
    order: List<String>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byUrl = shelf.volumes.associateBy { it.book.url }
    val volumes = order.mapNotNull { byUrl[it] }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // These are indices into the LazyColumn, not into the draft. The
        // two only agree because every item in this list is a volume,
        // which is why the explainer sits outside it: a single header
        // would silently shift every drag by one and make the last row
        // undraggable.
        onMove(from.index, to.index)
    }
    val eInk = LocalEInk.current

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.series_reorder_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(volumes, key = { _, volume -> volume.book.id }) { index, volume ->
                ReorderableItem(reorderState, key = volume.book.id) { dragging ->
                    ReorderableVolumeRow(
                        volume = volume,
                        dragging = dragging,
                        // A drag is unreachable from a switch or a screen
                        // reader, and on e-ink it repaints the whole screen
                        // on every frame. The arrows are not a fallback.
                        canMoveUp = index > 0,
                        canMoveDown = index < volumes.lastIndex,
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        dragHandle = Modifier.draggableHandle(),
                        animate = !eInk,
                    )
                }
            }
        }
    }
}

/** One volume, with the two ways of moving it. */
@Composable
private fun ReorderableVolumeRow(
    volume: SeriesVolume,
    dragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    dragHandle: Modifier,
    animate: Boolean,
) {
    Surface(
        tonalElevation = if (dragging && animate) 4.dp else 0.dp,
        shadowElevation = if (dragging && animate) 4.dp else 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Outlined.DragHandle,
                contentDescription = stringResource(R.string.series_reorder_drag),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandle.padding(horizontal = 8.dp),
            )
            BookCover(
                book = volume.book,
                modifier = Modifier.width(36.dp).aspectRatio(2f / 3f),
            )
            Text(
                text = volume.book.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.series_reorder_up),
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.series_reorder_down),
                )
            }
        }
    }
}

/**
 * The top of the screen: the next cover, blurred behind its own title,
 * and the one button worth pressing.
 */
@Composable
private fun SeriesHero(
    shelf: SeriesShelf,
    extras: SeriesExtras?,
    onVolumeSelected: (Book) -> Unit,
) {
    val eInk = LocalEInk.current
    val next = shelf.nextUp
    Box(modifier = Modifier.fillMaxWidth()) {
        // A cover blown up and thrown out of focus, which gives the
        // screen the series' own colours without asking a server for
        // artwork it may not have. E-paper gets a flat ground instead:
        // a blur there is a grey rectangle that costs a full refresh.
        if (!eInk) {
            BookCover(
                book = shelf.cover,
                modifier = Modifier
                    .matchParentSize()
                    .blur(28.dp)
                    .alpha(0.5f),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = if (eInk) 1f else 0.75f),
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row {
                BookCover(
                    book = shelf.cover,
                    modifier = Modifier.width(104.dp).aspectRatio(2f / 3f),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = shelf.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shelf.author?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    SeriesSummaryLine(shelf, Modifier.padding(top = 8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        extras?.status?.let { StatusPill(it) }
                        extras?.totalBookCount?.let { total ->
                            Text(
                                text = stringResource(
                                    R.string.series_published_count,
                                    shelf.volumes.size,
                                    total,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (next != null) {
                Button(
                    onClick = { onVolumeSelected(next.book) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            // Carrying on and starting out are different
                            // errands, and a button that says the wrong
                            // one of them is a button nobody trusts.
                            if (next.inProgress) R.string.series_continue else R.string.series_start,
                            next.book.displayTitle,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.series_all_read),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            extras?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** Ongoing, ended, abandoned — what the server says of the series. */
@Composable
private fun StatusPill(status: String) {
    val label = when (status.uppercase()) {
        "ONGOING" -> R.string.series_status_ongoing
        "ENDED" -> R.string.series_status_ended
        "ABANDONED" -> R.string.series_status_abandoned
        "HIATUS" -> R.string.series_status_hiatus
        else -> return
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * Every volume as a numbered chip, coloured by what has become of it.
 *
 * This is the part that answers "where am I in this" without reading
 * anything: a run of filled chips, then the one you are on, then the
 * ones ahead. It scrolls sideways so a series of forty still shows the
 * shape of itself on a phone.
 */
@Composable
private fun SeriesRail(
    shelf: SeriesShelf,
    extras: SeriesExtras?,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        railChips(shelf, extras).forEach { chip ->
            val (background, foreground) = when (chip) {
                is RailChip.Absent ->
                    Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                is RailChip.Present -> when {
                    chip.volume.finished ->
                        MaterialTheme.colorScheme.primary to
                            MaterialTheme.colorScheme.onPrimary
                    chip.volume.inProgress ->
                        MaterialTheme.colorScheme.primaryContainer to
                            MaterialTheme.colorScheme.onPrimaryContainer
                    chip.volume.onDevice ->
                        MaterialTheme.colorScheme.surfaceVariant to
                            MaterialTheme.colorScheme.onSurfaceVariant
                    else ->
                        Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                }
            }
            val label = when (chip) {
                is RailChip.Absent -> seriesIndexLabel(chip.number).orEmpty()
                is RailChip.Present -> seriesIndexLabel(chip.volume.index) ?: "\u00b7"
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(background)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        CircleShape,
                    )
                    // A number that is not here is drawn faintly, so the
                    // run reads as a run with a hole in it.
                    .alpha(if (chip is RailChip.Absent) 0.5f else 1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = foreground,
                )
            }
        }
    }
}

/** One chip on the rail: a volume you have, or a number you do not. */
private sealed interface RailChip {
    data class Present(val volume: SeriesVolume) : RailChip
    data class Absent(val number: Double) : RailChip
}

/**
 * The rail in reading order, holes included.
 *
 * A missing volume is worth showing only where it is missing: appended
 * to the end it reads as a further book rather than as the gap between
 * two you own. Volumes the source never numbered cannot be placed, so
 * they follow the numbered run, and what a server says is still to come
 * follows them.
 */
private fun railChips(shelf: SeriesShelf, extras: SeriesExtras?): List<RailChip> {
    val placed = shelf.volumes.mapNotNull { volume ->
        volume.index?.let { it to (RailChip.Present(volume) as RailChip) }
    } + shelf.gaps.map { it to (RailChip.Absent(it) as RailChip) }

    return placed.sortedBy { it.first }.map { it.second } +
        shelf.volumes.filter { it.index == null }.map { RailChip.Present(it) } +
        unpublishedTail(shelf, extras).map { RailChip.Absent(it) }
}

/** One volume in the list: its number, its cover, and where you got to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesVolumeRow(
    volume: SeriesVolume,
    progress: DownloadProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = stringResource(R.string.read_book),
                onLongClickLabel = stringResource(R.string.book_actions),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(32.dp),
        ) {
            Text(
                text = seriesIndexLabel(volume.index) ?: "·",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (volume.finished) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Box {
            BookCover(
                book = volume.book,
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(2f / 3f)
                    .alpha(if (volume.finished) 0.55f else 1f),
            )
            if (!volume.onDevice && progress == null) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = stringResource(R.string.book_on_server),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(14.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = volume.book.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val progression = volume.progression
            if (progression != null && progression > 0.0 && !volume.finished) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { progression.toFloat() },
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.weight(1f).height(4.dp),
                    )
                    Text(
                        text = "${(progression * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (volume.finished) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = stringResource(R.string.book_finished),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A number the series has and the library does not. */
@Composable
private fun MissingVolumeRow(number: Double, published: Boolean) {
    Text(
        text = stringResource(
            if (published) R.string.series_missing_volume else R.string.series_not_published_yet,
            seriesIndexLabel(number).orEmpty(),
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .alpha(0.75f),
    )
}

/** The numbers skipped immediately before [volume], if any. */
private fun gapsBefore(shelf: SeriesShelf, volume: SeriesVolume): List<Double> {
    val index = volume.index ?: return emptyList()
    val previous = shelf.volumes
        .mapNotNull { it.index }
        .filter { it < index }
        .maxOrNull() ?: return emptyList()
    return shelf.gaps.filter { it > previous && it < index }
}

/**
 * Volumes a server has counted that the library has not seen, after the
 * last one it has.
 *
 * Only when a server actually counted them. Without that number there is
 * no way to tell a series you are four books into from a series that is
 * four books long, and guessing turns a shelf into a shopping list.
 */
private fun unpublishedTail(shelf: SeriesShelf, extras: SeriesExtras?): List<Double> {
    val total = extras?.totalBookCount ?: return emptyList()
    val last = shelf.volumes.mapNotNull { it.index }
        .filter { it == Math.floor(it) }
        .maxOrNull()?.toLong() ?: return emptyList()
    if (total <= last) return emptyList()
    // A wildly wrong count from a server must not draw a thousand rows.
    val end = minOf(total.toLong(), last + 50)
    return ((last + 1)..end).map { it.toDouble() }
}
