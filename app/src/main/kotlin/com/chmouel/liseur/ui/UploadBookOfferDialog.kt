package com.chmouel.liseur.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R

/**
 * The offer to send one just-added book up, named rather than counted.
 *
 * The shelf's offer speaks of "3 books" because it covers whatever has
 * accumulated. This one is raised by an act — a book was opened, or
 * picked — so it can say which book, and it is asked where that act
 * happened instead of the next time the shelf is visited.
 *
 * Three answers, stacked rather than in a row. Laid side by side these
 * labels do not fit a phone dialog, and Material stacks actions that
 * cannot share a line rather than shrinking them.
 *
 * [onAlways] is here so that "stop asking me" has an answer within
 * reach of the question. Without it the only way to settle this is to
 * find the setting behind the server screen, which is a long walk from
 * a book someone is trying to read.
 */
@Composable
fun UploadBookOfferDialog(
    title: String,
    onSend: () -> Unit,
    onAlways: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upload_offer_one_title, title)) },
        text = { Text(stringResource(R.string.upload_offer_one_message)) },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier,
            ) {
                TextButton(onClick = onSend) {
                    Text(stringResource(R.string.upload_offer_confirm))
                }
                TextButton(onClick = onAlways) {
                    Text(stringResource(R.string.upload_offer_always))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.upload_offer_dismiss))
                }
            }
        },
    )
}
