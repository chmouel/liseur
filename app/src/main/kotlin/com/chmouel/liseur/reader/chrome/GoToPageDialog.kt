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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.progress.GoToPageDestination
import com.chmouel.liseur.reader.progress.GoToPagePrompt
import com.chmouel.liseur.reader.progress.PageNumbering

/** Asks for an exact printed page or synthetic reader position. */
@Composable
fun GoToPageDialog(
    prompt: GoToPagePrompt,
    resolve: (String) -> GoToPageDestination?,
    onConfirm: (GoToPageDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var answer by rememberSaveable(prompt.numbering, prompt.firstLabel, prompt.lastLabel) {
        mutableStateOf("")
    }
    val destination = resolve(answer)
    val invalid = answer.isNotBlank() && destination == null
    val printed = prompt.numbering == PageNumbering.PRINTED
    val submit: () -> Unit = { destination?.let(onConfirm) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (printed) R.string.go_to_printed_page_title else R.string.go_to_page_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            stringResource(
                                if (printed) {
                                    R.string.go_to_printed_page_label
                                } else {
                                    R.string.go_to_page_position_label
                                },
                            ),
                        )
                    },
                    supportingText = if (invalid) {
                        {
                            Text(
                                stringResource(
                                    if (printed) {
                                        R.string.go_to_printed_page_invalid
                                    } else {
                                        R.string.go_to_page_position_invalid
                                    },
                                ),
                            )
                        }
                    } else {
                        null
                    },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (printed) KeyboardType.Text else KeyboardType.Number,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                )
                Text(
                    text = stringResource(
                        if (printed) {
                            R.string.go_to_printed_page_range
                        } else {
                            R.string.go_to_page_position_range
                        },
                        prompt.firstLabel,
                        prompt.lastLabel,
                    ),
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
