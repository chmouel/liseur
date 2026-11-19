package com.chmouel.liseur.vocabulary

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VocabularyReviewScreen(
    repository: VocabularyRepository,
    queue: VocabularyQueue,
    bookUrl: String?,
    newLimit: Int,
    reviewLimit: Int,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val word = queue.card
    var undo by remember { mutableStateOf<VocabularyReviewUndo?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val duration = remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vocabulary_review_progress, queue.completedToday, queue.dueCount),
                Modifier.padding(vertical = 14.dp))
            undo?.let { previous ->
                TextButton(onClick = {
                    undo = null
                    scope.launch { repository.undoReview(previous) }
                }) { Text(stringResource(R.string.undo)) }
            }
        }
        if (word == null) {
            Text(stringResource(R.string.vocabulary_review_done), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDone) { Text(stringResource(R.string.back)) }
        } else {
            key(word.id, word.repetitions) {
                var revealed by rememberSaveable { mutableStateOf(false) }
                var submitting by remember { mutableStateOf(false) }
                Text(word.word, style = MaterialTheme.typography.headlineLarge)
                if (revealed) {
                    val occurrences by remember(word.id) { repository.dao.occurrences(word.id) }
                        .collectAsStateWithLifecycle(emptyList())
                    val passage = occurrences.firstOrNull { (bookUrl == null || it.bookUrl == bookUrl) && !it.sentence.isNullOrBlank() }
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    var overflowing by remember { mutableStateOf(false) }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                word.definition ?: stringResource(R.string.vocabulary_no_definition),
                                maxLines = if (expanded) Int.MAX_VALUE else 6,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                            )
                            if (word.notes.isNotBlank()) Text(word.notes)
                            passage?.sentence?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    if (overflowing || expanded) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.annotation_show_less else R.string.annotation_show_more))
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((rating, label) in recallButtons) {
                            val interval = scheduleVocabulary(word.streak, rating, 0).intervalMinutes
                            val measure = when {
                                interval % 1440 == 0 -> Measure(interval / 1440, MeasureUnit.DAY)
                                interval % 60 == 0 -> Measure(interval / 60, MeasureUnit.HOUR)
                                else -> Measure(interval, MeasureUnit.MINUTE)
                            }
                            OutlinedButton(
                                enabled = !submitting,
                                onClick = {
                                    submitting = true
                                    scope.launch {
                                        try {
                                            undo = repository.review(word, rating, newLimit, reviewLimit) ?: undo
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (_: Exception) {
                                            Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
                                        } finally {
                                            submitting = false
                                        }
                                    }
                                },
                            ) {
                                Column {
                                    Text(stringResource(label))
                                    Text(duration.format(measure), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    Button(onClick = { revealed = true }) { Text(stringResource(R.string.vocabulary_reveal)) }
                }
            }
        }
    }
}

private val recallButtons = listOf(
    Recall.AGAIN to R.string.vocabulary_again,
    Recall.HARD to R.string.vocabulary_hard,
    Recall.GOOD to R.string.vocabulary_good,
    Recall.EASY to R.string.vocabulary_easy,
)
