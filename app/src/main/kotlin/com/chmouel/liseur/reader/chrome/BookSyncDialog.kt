package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.reader.ReaderViewModel
import kotlin.math.roundToInt

/**
 * Shows the reading positions and lets the reader pick one.
 *
 * Nothing has been applied by the time this appears: the server's answer
 * has been written down, but neither side has been changed.
 *
 * Cancelling is a real answer — "not this way" — and changes nothing
 * here. It is deliberately not called "not now": the server's answer is
 * on disk either way, and the next time the book is opened an ordinary
 * sync settles it by taking the further side, exactly as it would have
 * done had the button never been pressed. Cancelling buys no delay.
 *
 * [onForgetFurthest] is null where the furthest cannot be forgotten, and
 * then the card offers nothing.
 */
@Composable
fun BookSyncDialog(
    state: ReaderViewModel.BookSync,
    onResolve: (takeRemote: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onFurthest: () -> Unit = {},
    onForgetFurthest: (() -> Unit)? = null,
) {
    when (state) {
        ReaderViewModel.BookSync.Idle, ReaderViewModel.BookSync.Asking -> Unit

        is ReaderViewModel.BookSync.Note -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(stringResource(state.messageRes)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            },
        )

        is ReaderViewModel.BookSync.Choice -> {
            val offersFurthest = state.furthest != null
            val showsRemote = BookSyncChoice.showsRemote(state.preview, offersFurthest)
            PositionsDialog(
                helpRes = if (showsRemote) {
                    when (state.relation) {
                        SyncRelation.AHEAD -> R.string.reader_sync_book_ahead
                        SyncRelation.BEHIND -> R.string.reader_sync_book_behind
                        SyncRelation.SAME_PAGE -> R.string.reader_sync_book_same_page
                    }
                } else null,
                places = buildList {
                    add(Place(Mark.HERE, R.string.reader_sync_here, state.here))
                    if (showsRemote) add(Place(Mark.THERE, R.string.reader_sync_there, state.there))
                    state.furthest?.let {
                        add(Place(Mark.FURTHEST, R.string.reader_sync_furthest_known, it, onForgetFurthest))
                    }
                },
                answers = BookSyncChoice.actions(state.preview, state.relation, offersFurthest).map {
                    when (it) {
                        BookSyncAction.FURTHEST -> Answer(R.string.reader_sync_take_furthest, onFurthest)
                        BookSyncAction.THEIRS -> Answer(R.string.reader_sync_take_theirs) { onResolve(true) }
                        BookSyncAction.MINE -> Answer(R.string.reader_sync_keep_mine) { onResolve(false) }
                    }
                },
                onDismiss = onDismiss,
            )
        }

        is ReaderViewModel.BookSync.BookOrbitChoice -> PositionsDialog(
            helpRes = when {
                state.preview.retryRequired && state.canTakeRemote -> R.string.reader_bookorbit_retry_help
                state.preview.retryRequired -> R.string.reader_bookorbit_retry_only_help
                state.preview.canKeepLocal -> R.string.reader_bookorbit_choice_help
                else -> R.string.reader_bookorbit_take_only_help
            },
            places = listOf(
                Place(Mark.HERE, R.string.reader_sync_here, state.here),
                Place(Mark.THERE, R.string.reader_sync_there, state.there),
            ),
            answers = buildList {
                if (state.canTakeRemote) add(Answer(R.string.reader_bookorbit_take) { onResolve(true) })
                if (state.preview.canKeepLocal) {
                    add(Answer(
                        if (state.preview.retryRequired) R.string.reader_bookorbit_retry
                        else R.string.reader_sync_keep_mine,
                    ) { onResolve(false) })
                }
            },
            onDismiss = onDismiss,
        )
    }
}

/** Which position a card and its marker on the track stand for. */
private enum class Mark { HERE, THERE, FURTHEST }

private class Place(
    val mark: Mark,
    val labelRes: Int,
    val point: ReaderViewModel.SyncPoint,
    val onForget: (() -> Unit)? = null,
)

private class Answer(val labelRes: Int, val onClick: () -> Unit)

/**
 * The frame both choices share: a track showing where each position sits
 * in the book, a card per position, and the answers under them, the first
 * one filled.
 *
 * The cards scroll and the answers do not, so at a large font size the
 * buttons stay where a thumb can reach them. Cancel is a button of its own
 * rather than only a tap outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionsDialog(
    helpRes: Int?,
    places: List<Place>,
    answers: List<Answer>,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(
                    text = stringResource(R.string.reader_sync_book_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                ) {
                    helpRes?.let {
                        Text(
                            text = stringResource(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AlertDialogDefaults.textContentColor,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    PositionTrack(places, Modifier.padding(top = 20.dp, bottom = 4.dp))
                    places.forEach { PositionCard(it, Modifier.padding(top = 12.dp)) }
                }
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    answers.forEachIndexed { index, answer ->
                        val label: @Composable () -> Unit = {
                            Text(stringResource(answer.labelRes), textAlign = TextAlign.Center)
                        }
                        if (index == 0) {
                            Button(onClick = answer.onClick, modifier = Modifier.fillMaxWidth()) { label() }
                        } else {
                            OutlinedButton(onClick = answer.onClick, modifier = Modifier.fillMaxWidth()) { label() }
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(stringResource(R.string.reader_sync_cancel))
                    }
                }
            }
        }
    }
}

/**
 * The book as a line, filled up to this device's place, with a marker for
 * every position shown. The cards say the same thing in words, so the
 * track is left out of what a screen reader reads.
 */
@Composable
private fun PositionTrack(places: List<Place>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.outlineVariant
    val here = places.firstOrNull { it.mark == Mark.HERE }?.point?.progression ?: 0.0
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(TRACK_MARK)
            .clearAndSetSemantics {},
    ) {
        val usable = maxWidth - TRACK_MARK
        fun along(progression: Double) = usable * progression.toFloat().coerceIn(0f, 1f)
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TRACK_MARK / 2)
                .fillMaxWidth()
                .height(TRACK_RAIL)
                .clip(CircleShape)
                .background(rail),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = TRACK_MARK / 2)
                .width(along(here))
                .height(TRACK_RAIL)
                .clip(CircleShape)
                .background(accent),
        )
        places.forEach { place ->
            Marker(
                mark = place.mark,
                size = TRACK_MARK,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = along(place.point.progression)),
            )
        }
    }
}

/**
 * A marker told apart by its shape rather than its colour, so it reads the
 * same on every page theme and on e-ink: a dot for this device, a diamond
 * for the server, a ring for the furthest.
 */
@Composable
private fun Marker(mark: Mark, size: Dp, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    when (mark) {
        Mark.HERE -> Box(modifier.size(size).clip(CircleShape).background(accent))
        Mark.THERE -> Box(
            modifier
                .size(size)
                .padding(size * 0.15f)
                .rotate(45f)
                .background(accent),
        )
        Mark.FURTHEST -> Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(AlertDialogDefaults.containerColor)
                .border(size * 0.2f, accent, CircleShape),
        )
    }
}

/**
 * One position, said in pages and as a percentage where the book has been
 * laid out, and as a percentage alone where it has not — a page number is
 * what a reader can place themselves by, but it is not known until the
 * book has been measured. The chapter follows when it is known.
 *
 * The excerpt is the server's text, so it is drawn as text and nothing
 * else: no markup is interpreted, its length is capped, and it is held
 * to two lines. A partner sending a chapter where a phrase was expected
 * cannot push the buttons off the screen. An excerpt that is only the
 * chapter's heading again is left out.
 *
 * Forgetting asks once more in place, rather than with a second dialog
 * stacked on this one.
 */
@Composable
private fun PositionCard(place: Place, modifier: Modifier = Modifier) {
    val point = place.point
    var confirming by remember(point) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Marker(place.mark, size = 10.dp)
                Spacer(Modifier.width(8.dp))
                val label = stringResource(place.labelRes)
                Text(
                    text = relativeAge(point.at)?.let { "$label · $it" } ?: label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (place.onForget != null && !confirming) {
                    TextButton(
                        onClick = { confirming = true },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text(stringResource(R.string.reader_sync_forget))
                    }
                }
            }
            Column(Modifier.padding(end = 8.dp)) {
                Text(
                    text = placeLine(point),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val chapter = point.chapter?.trim()?.takeIf { it.isNotEmpty() }
                if (chapter != null) {
                    Text(
                        text = chapter,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val excerpt = point.excerpt?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(chapter, ignoreCase = true) }
                if (excerpt != null) {
                    Text(
                        text = excerpt.take(EXCERPT_CHARS),
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            val onForget = place.onForget
            if (onForget != null && confirming) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.reader_sync_forget_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirming = false }) {
                        Text(stringResource(R.string.reader_sync_forget_keep))
                    }
                    TextButton(onClick = onForget) {
                        Text(stringResource(R.string.reader_sync_forget))
                    }
                }
            }
        }
    }
}

@Composable
private fun placeLine(point: ReaderViewModel.SyncPoint): String {
    val page = point.page
    val total = point.totalPages
    val percent = stringResource(
        R.string.reader_sync_percent,
        (point.progression * 100).roundToInt(),
    )
    if (page == null || total == null || total <= 0) return percent
    val pages = if (point.confidence == ResumeConfidence.EXACT) {
        stringResource(R.string.reader_sync_page, page, total)
    } else {
        stringResource(R.string.reader_sync_near_page, page, total)
    }
    return "$pages · $percent"
}

private val TRACK_MARK = 14.dp
private val TRACK_RAIL = 4.dp

/** As much of a server's excerpt as is worth reading to place oneself. */
private const val EXCERPT_CHARS = 200
