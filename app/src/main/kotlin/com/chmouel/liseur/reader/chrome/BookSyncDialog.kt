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
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.ReaderViewModel
import kotlin.math.roundToInt

/**
 * Shows both reading positions and lets the reader pick one.
 *
 * Nothing has been applied by the time this appears: the server's answer
 * has been written down, but neither side has been changed. Dismissing is
 * therefore a real answer — "not now" — and leaves the question to be
 * asked again rather than quietly picking a winner.
 *
 * It appears whenever the two differ, including when the server is
 * *behind* this device. That case is the reason this exists: ordinary
 * syncing has nothing to say about it, and it is exactly what happens
 * when you have read on elsewhere and want to push it out.
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
                            if (state.theirsIsBehind) {
                                R.string.reader_sync_book_behind
                            } else {
                                R.string.reader_sync_book_ahead
                            },
                        ),
                    )
                    Side(R.string.reader_sync_here, state.here)
                    Side(R.string.reader_sync_there, state.there)
                }
            },
            confirmButton = {
                TextButton(onClick = { onResolve(true) }) {
                    Text(stringResource(R.string.reader_sync_take_theirs))
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolve(false) }) {
                    Text(stringResource(R.string.reader_sync_keep_mine))
                }
            },
        )
    }
}

/**
 * One side, said in pages where the book has been laid out and as a
 * percentage where it has not — a page number is what a reader can place
 * themselves by, but it is not known until the book has been measured.
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
        Text(
            text = if (page != null && total != null && total > 0) {
                stringResource(R.string.reader_sync_page, page, total)
            } else {
                stringResource(
                    R.string.reader_sync_percent,
                    (point.progression * 100).roundToInt(),
                )
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
