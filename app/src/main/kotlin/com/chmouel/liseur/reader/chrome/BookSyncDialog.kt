package com.chmouel.liseur.reader.chrome

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.ReaderViewModel

/**
 * Says how syncing this one book went, when there is anything to say.
 *
 * There is no choice to make any more: syncing takes whichever side has
 * read further, and jumping ahead announces itself with the way-back
 * pill instead of a dialog. What is left here are the outcomes that end
 * in words — already in step, nothing on the server, sent, or failed.
 */
@Composable
fun BookSyncDialog(
    state: ReaderViewModel.BookSync,
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
    }
}
