package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.reader.ReaderViewModel
import kotlin.math.roundToInt

/**
 * Shows both reading positions and lets the reader pick one.
 *
 * Nothing has been applied by the time this appears: the server's answer
 * has been written down, but neither side has been changed.
 *
 * Cancelling is a real answer — "not this way" — and changes nothing
 * here. It is deliberately not called "not now": the server's answer is
 * on disk either way, and the next time the book is opened an ordinary
 * sync settles it by taking the further side, exactly as it would have
 * done had the button never been pressed. Nothing about cancelling books
 * this question for later.
 */
@Composable
fun BookSyncDialog(
    state: ReaderViewModel.BookSync,
    onResolve: (takeRemote: Boolean) -> Unit,
    onDismiss: () -> Unit,
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

        is ReaderViewModel.BookSync.Choice -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.reader_sync_book_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            when (state.relation) {
                                SyncRelation.AHEAD -> R.string.reader_sync_book_ahead
                                SyncRelation.BEHIND -> R.string.reader_sync_book_behind
                                SyncRelation.SAME_PAGE -> R.string.reader_sync_book_same_page
                            },
                        ),
                    )
                    Side(R.string.reader_sync_here, state.here)
                    Side(R.string.reader_sync_there, state.there)
                }
            },
            // Three answers and room for two, so they go in a column of
            // their own. Cancel has to be reachable by aiming at it, not
            // only by tapping away from the others.
            confirmButton = {
                Column {
                    TextButton(onClick = { onResolve(true) }) {
                        Text(stringResource(R.string.reader_sync_take_theirs))
                    }
                    TextButton(onClick = { onResolve(false) }) {
                        Text(stringResource(R.string.reader_sync_keep_mine))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.reader_sync_cancel))
                    }
                }
            },
        )
    }
}

/**
 * One side, said in pages where the book has been laid out and as a
 * percentage where it has not — a page number is what a reader can place
 * themselves by, but it is not known until the book has been measured.
 *
 * The excerpt is the server's text, so it is drawn as text and nothing
 * else: no markup is interpreted, its length is capped, and it is held
 * to two lines. A partner sending a chapter where a phrase was expected
 * cannot push the buttons off the screen.
 */
@Composable
private fun Side(labelRes: Int, point: ReaderViewModel.SyncPoint) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val page = point.page
        val total = point.totalPages
        val place = if (page != null && total != null && total > 0) {
            if (point.confidence == ResumeConfidence.EXACT) {
                stringResource(R.string.reader_sync_page, page, total)
            } else {
                stringResource(R.string.reader_sync_near_page, page, total)
            }
        } else {
            stringResource(
                R.string.reader_sync_percent,
                (point.progression * 100).roundToInt(),
            )
        }
        Text(
            text = relativeAge(point.at)?.let { "$place · $it" } ?: place,
            style = MaterialTheme.typography.bodyLarge,
        )
        val excerpt = point.excerpt?.takeIf { it.isNotBlank() }
        if (excerpt != null) {
            Text(
                text = excerpt.take(EXCERPT_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** As much of a server's excerpt as is worth reading to place oneself. */
private const val EXCERPT_CHARS = 200
