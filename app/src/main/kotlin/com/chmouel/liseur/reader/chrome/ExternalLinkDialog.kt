package com.chmouel.liseur.reader.chrome

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R

/**
 * Asks before a link in a book takes the reader out of it.
 *
 * Liseur promises to talk to the reader's own book server and to nothing
 * else. A link in a book is somebody else's server, and following one on a
 * tap would break that promise quietly — which is the only way promises like
 * this ever get broken. So the host is named and the reader decides.
 */
@Composable
fun ExternalLinkDialog(
    url: String,
    host: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_external_link_title)) },
        text = { Text(stringResource(R.string.reader_external_link_body, host)) },
        confirmButton = {
            TextButton(onClick = { onOpen(url) }) {
                Text(stringResource(R.string.reader_external_link_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
