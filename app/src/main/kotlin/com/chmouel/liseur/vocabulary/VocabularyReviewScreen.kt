package com.chmouel.liseur.vocabulary

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    val duration = rememberIntervalFormat()
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
                var flying by remember { mutableStateOf(false) }
                suspend fun rate(rating: Recall): Boolean {
                    if (submitting) return false
                    submitting = true
                    return try {
                        val done = repository.review(word, rating, newLimit, reviewLimit)
                        if (done != null) undo = done
                        done != null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
                        false
                    } finally {
                        submitting = false
                    }
                }
                var expanded by rememberSaveable { mutableStateOf(false) }
                var overflowing by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(20.dp)
                SwipeDeck(
                    depth = (queue.dueCount - 1).coerceIn(0, 2),
                    layers = MaterialTheme.colorScheme.surfaceContainerHighest to
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = shape,
                    enabled = revealed && !submitting,
                    thresholds = ReviewSwipe,
                    streak = word.streak,
                    onSwipe = ::rate,
                    onFlight = { flying = it },
                    modifier = Modifier.fillMaxWidth(),
                ) { swipes, actions ->
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 2.dp,
                        onClick = { revealed = true },
                        modifier = actions.fillMaxWidth().heightIn(min = 180.dp),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(word.word, style = MaterialTheme.typography.headlineLarge)
                            if (revealed) {
                                Text(
                                    word.definition ?: stringResource(R.string.vocabulary_no_definition),
                                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                                )
                                if (swipes) {
                                    Text(
                                        stringResource(R.string.vocabulary_swipe_hint),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(R.string.vocabulary_tap_to_reveal),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (revealed) {
                    val occurrences by remember(word.id) { repository.dao.occurrences(word.id) }
                        .collectAsStateWithLifecycle(emptyList())
                    val passage = occurrences.firstOrNull { (bookUrl == null || it.bookUrl == bookUrl) && !it.sentence.isNullOrBlank() }
                    if (overflowing || expanded) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.annotation_show_less else R.string.annotation_show_more))
                        }
                    }
                    if (word.notes.isNotBlank() || passage?.sentence != null) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (word.notes.isNotBlank()) Text(word.notes)
                                passage?.sentence?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((rating, label) in recallButtons) {
                            val interval = scheduleVocabulary(word.streak, rating, 0).intervalMinutes
                            OutlinedButton(
                                enabled = !submitting && !flying,
                                onClick = { scope.launch { rate(rating) } },
                            ) {
                                if (rating == Recall.AGAIN) {
                                    Icon(Icons.Outlined.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Column {
                                    Text(stringResource(label))
                                    Text(duration.formatInterval(interval), style = MaterialTheme.typography.labelSmall)
                                }
                                if (rating == Recall.GOOD) {
                                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
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
