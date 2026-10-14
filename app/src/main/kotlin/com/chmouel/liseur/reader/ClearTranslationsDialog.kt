package com.chmouel.liseur.reader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chmouel.liseur.R

/** Asks before forgetting the book's saved translations, here and on the account. */
@Composable
internal fun ClearTranslationsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_clear_translations_title)) },
        text = { Text(stringResource(R.string.reader_clear_translations_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.reader_clear_translations_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
