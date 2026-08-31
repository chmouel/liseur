package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.progress.GoToDestination
import com.chmouel.liseur.reader.progress.GoToPagePrompt
import com.chmouel.liseur.reader.progress.PageNumbering

/** Asks for an exact printed page or synthetic reader position. */
@Composable
fun GoToPageDialog(
    prompt: GoToPagePrompt,
    resolve: (String) -> GoToDestination?,
    onConfirm: (GoToDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    val printed = prompt.numbering == PageNumbering.PRINTED
    GoToDialog(
        title = stringResource(
            if (printed) R.string.go_to_printed_page_title else R.string.go_to_page_title,
        ),
        label = stringResource(
            if (printed) R.string.go_to_printed_page_label else R.string.go_to_page_position_label,
        ),
        range = stringResource(
            if (printed) R.string.go_to_printed_page_range else R.string.go_to_page_position_range,
            prompt.firstLabel,
            prompt.lastLabel,
        ),
        invalidMessage = stringResource(
            if (printed) {
                R.string.go_to_printed_page_invalid
            } else {
                R.string.go_to_page_position_invalid
            },
        ),
        // A printed page list is a list of labels, and "iv" is one of
        // them, so only the synthetic numbering can promise digits.
        keyboardType = if (printed) KeyboardType.Text else KeyboardType.Number,
        start = prompt.currentLabel,
        resolve = resolve,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Asks for a whole percentage of the book, 0 to 100. */
@Composable
fun GoToPercentDialog(
    currentPercent: Int,
    resolve: (String) -> GoToDestination?,
    onConfirm: (GoToDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    GoToDialog(
        title = stringResource(R.string.go_to_percent_title),
        label = stringResource(R.string.go_to_percent_label),
        range = stringResource(R.string.go_to_percent_range),
        invalidMessage = stringResource(R.string.go_to_percent_invalid),
        keyboardType = KeyboardType.Number,
        start = currentPercent.toString(),
        resolve = resolve,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The shape both questions share: one number, the range it must fall in,
 * and the chapter it lands in named before the reader commits.
 *
 * The field opens on [start] — where the reader is now — with the whole
 * of it selected. The number they are moving relative to is the one
 * thing a "go to" dialog cannot ask for and cannot do without, and
 * offering it as the answer costs nothing: the first digit typed
 * replaces it, and confirming it untouched goes where the book already
 * is.
 */
@Composable
private fun GoToDialog(
    title: String,
    label: String,
    range: String,
    invalidMessage: String,
    keyboardType: KeyboardType,
    start: String,
    resolve: (String) -> GoToDestination?,
    onConfirm: (GoToDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seeded once and deliberately not keyed on [start]: the reader's
    // position can settle under the dialog, and a key would take the
    // half-typed answer with it. The dialog is created when it opens, so
    // the next one starts from wherever the reader is by then.
    var answer by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(start, TextRange(0, start.length)))
    }
    val destination = resolve(answer.text)
    val invalid = answer.text.isNotBlank() && destination == null
    val submit: () -> Unit = { destination?.let(onConfirm) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    label = { Text(label) },
                    supportingText = if (invalid) {
                        { Text(invalidMessage) }
                    } else {
                        null
                    },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
                Text(
                    text = range,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                destination?.chapterTitle?.let { chapter ->
                    Text(
                        text = stringResource(R.string.go_to_page_chapter, chapter),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = destination != null) {
                Text(stringResource(R.string.go_to_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
