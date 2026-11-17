package com.chmouel.liseur.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.PersonalEvaluation
import java.text.NumberFormat

@Composable
internal fun PersonalEvaluationCard(details: BookDetails, onSet: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val book = details.book
    key(book.url, book.id, book.workId, book.fileModifiedAt, book.linkGeneration) {
        val rating = details.evaluations.find { it.field == PersonalEvaluation.RATING }?.value?.toIntOrNull()
        val review = details.evaluations.find { it.field == PersonalEvaluation.REVIEW }?.value
        var editing by rememberSaveable(details.book.url) { mutableStateOf(false) }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.evaluation_title), style = MaterialTheme.typography.titleMedium)
            Text(ratingLabel(rating), style = MaterialTheme.typography.bodyLarge)
            review?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.evaluation_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.evaluation_edit)) }
        }
        if (editing) {
            EvaluationDialog(details.book.url, rating, review, onDismiss = { editing = false }) { changes ->
                changes.forEach { (field, value) -> onSet(field, value) }
                editing = false
            }
        }
    }
}

@Composable
private fun ratingLabel(rating: Int?): String = if (rating == null) {
    stringResource(R.string.evaluation_no_rating)
} else {
    stringResource(R.string.evaluation_rating_value, NumberFormat.getNumberInstance().format(rating / 2.0))
}

@Composable
private fun EvaluationDialog(bookUrl: String, initialRating: Int?, initialReview: String?, onDismiss: () -> Unit, onSave: (Map<String, String?>) -> Unit) {
    val originalRating by rememberSaveable(bookUrl) { mutableStateOf(initialRating) }
    val originalReview by rememberSaveable(bookUrl) { mutableStateOf(initialReview) }
    var rating by rememberSaveable(bookUrl) { mutableStateOf(initialRating) }
    var review by rememberSaveable(bookUrl) { mutableStateOf(initialReview.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    val normalized = review.takeIf { it.isNotBlank() }
    val valid = PersonalEvaluation.valid(PersonalEvaluation.REVIEW, normalized)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.evaluation_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    OutlinedButton(onClick = { menu = true }) { Text(ratingLabel(rating)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.evaluation_no_rating)) }, onClick = { rating = null; menu = false })
                        for (value in 2..10) {
                            DropdownMenuItem(text = { Text(ratingLabel(value)) }, onClick = { rating = value; menu = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = review, onValueChange = { review = it },
                    label = { Text(stringResource(R.string.evaluation_review)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
                    isError = !valid,
                    supportingText = { if (!valid) Text(stringResource(R.string.evaluation_review_limit)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = {
            onSave(buildMap {
                if (rating != originalRating) put(PersonalEvaluation.RATING, rating?.toString())
                if (normalized != originalReview) put(PersonalEvaluation.REVIEW, normalized)
            })
        }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
