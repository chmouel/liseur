package com.chmouel.liseur.vocabulary

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VocabularyReviewScreen(
    repository: VocabularyRepository,
    queue: VocabularyQueue,
    bookUrl: String?,
    newLimit: Int,
    reviewLimit: Int,
    sessionCount: Int,
    onRated: () -> Unit,
    onUndone: () -> Unit,
    onOpenPassage: (VocabularyOccurrence) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val word = queue.card
    val speaker = rememberWordSpeaker()
    var undo by remember { mutableStateOf<VocabularyReviewUndo?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var revealed by rememberSaveable(word?.id, word?.repetitions) { mutableStateOf(false) }
    var flying by remember(word?.id, word?.repetitions) { mutableStateOf(false) }
    suspend fun rate(rating: Recall): Boolean {
        val card = word ?: return false
        if (submitting) return false
        submitting = true
        return try {
            val done = repository.review(card, rating, newLimit, reviewLimit)
            if (done != null) {
                undo = done
                onRated()
            }
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
    BoxWithConstraints(modifier) {
        val compact = maxHeight < 360.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
            ReviewProgress(sessionCount, queue, compact, undo != null && !submitting && !flying) {
                val previous = undo ?: return@ReviewProgress
                submitting = true
                scope.launch {
                    try {
                        if (repository.undoReview(previous)) onUndone()
                        undo = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
                    } finally {
                        submitting = false
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (word == null) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.vocabulary_review_done), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = onDone) { Text(stringResource(R.string.back)) }
                    }
                } else {
                    val occurrences by remember(word.id) { repository.dao.occurrences(word.id) }
                        .collectAsStateWithLifecycle(emptyList())
                    val sources = occurrences.filter { bookUrl == null || it.bookUrl == bookUrl }
                    val passage = sources.firstOrNull { !it.sentence.isNullOrBlank() }
                        ?: sources.firstOrNull { it.locator != null }
                    val shape = RoundedCornerShape(24.dp)
                    key(word.id, word.repetitions) {
                        SwipeDeck(
                            depth = (queue.dueCount - 1).coerceIn(0, 2),
                            layers = MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = shape,
                            enabled = revealed && !submitting,
                            thresholds = ReviewSwipe,
                            streak = word.streak,
                            onSwipe = ::rate,
                            onFlight = { flying = it },
                            modifier = Modifier.fillMaxWidth(),
                        ) { swipes, actions ->
                            ReviewCard(word, passage, revealed, swipes, speaker, { revealed = true }, onOpenPassage, actions)
                        }
                    }
                }
            }
            if (word != null) {
                if (revealed) {
                    RecallTiles(word, !submitting && !flying, compact, Modifier.padding(bottom = if (compact) 6.dp else 12.dp)) { rating ->
                        scope.launch { rate(rating) }
                    }
                } else {
                    Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Text(stringResource(R.string.vocabulary_reveal))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewProgress(sessionCount: Int, queue: VocabularyQueue, compact: Boolean, canUndo: Boolean, onUndo: () -> Unit) {
    val total = sessionCount + queue.dueCount
    val progress = if (total == 0) 0f else sessionCount.toFloat() / total
    val description = stringResource(R.string.vocabulary_session_progress, sessionCount, total)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(if (compact) 40.dp else 64.dp).semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 5.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text("$sessionCount/$total", style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vocabulary_review_progress, queue.completedToday, queue.dueCount), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.undo))
        }
    }
}

@Composable
private fun ReviewCard(
    word: VocabularyWord,
    passage: VocabularyOccurrence?,
    revealed: Boolean,
    swipes: Boolean,
    speaker: WordSpeaker,
    onReveal: () -> Unit,
    onOpenPassage: (VocabularyOccurrence) -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val eInk = LocalEInk.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    Surface(
        onClick = onReveal,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (eInk) scheme.outline else scheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(
                    if (eInk) scheme.surface else lerp(scheme.surface, scheme.primaryContainer, 0.35f),
                    if (eInk) scheme.surface else scheme.surfaceContainerHigh,
                )))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    word.word, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                if (speaker.supports(word.language)) {
                    IconButton(onClick = { speaker.speak(word) }) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.vocabulary_pronounce, word.word))
                    }
                }
            }
            if (word.language != "und") {
                Surface(color = scheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                    Text(word.language.uppercase(Locale.ROOT), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (revealed) {
                Row {
                    Box(Modifier.size(width = 3.dp, height = 48.dp).background(if (eInk) scheme.onSurface else scheme.primary, RoundedCornerShape(2.dp)))
                    Column(Modifier.padding(start = 12.dp)) {
                        SelectionContainer {
                            Text(
                                word.definition ?: stringResource(R.string.vocabulary_no_definition),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = if (expanded) Int.MAX_VALUE else 6,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
                            )
                        }
                        if (overflowing || expanded) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.annotation_show_less else R.string.annotation_show_more))
                            }
                        }
                    }
                }
                if (word.notes.isNotBlank()) SelectionContainer { Text(word.notes) }
                if (passage != null) {
                    Surface(color = scheme.surface.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            passage.sentence?.takeIf { it.isNotBlank() }?.let { sentence ->
                                val highlight = remember(sentence, word.word) { highlightWord(sentence, word.word) }
                                val quote = buildAnnotatedString {
                                    append(sentence)
                                    highlight?.let { addStyle(SpanStyle(background = scheme.primaryContainer, fontWeight = FontWeight.Bold), it.first, it.last + 1) }
                                }
                                SelectionContainer { Text(quote, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyLarge) }
                            }
                            Text(passage.bookTitle, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (passage.locator != null && passage.bookUrl.isNotEmpty()) {
                        TextButton(onClick = { onOpenPassage(passage) }) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.padding(end = 8.dp).size(20.dp))
                            Text(stringResource(R.string.vocabulary_open_passage))
                        }
                    }
                }
                if (swipes) Text(stringResource(R.string.vocabulary_swipe_hint), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.vocabulary_tap_to_reveal), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private val recallButtons = listOf(
    Triple(Recall.AGAIN, R.string.vocabulary_again, Icons.Outlined.Replay),
    Triple(Recall.HARD, R.string.vocabulary_hard, Icons.AutoMirrored.Outlined.CallMade),
    Triple(Recall.GOOD, R.string.vocabulary_good, Icons.Outlined.Check),
    Triple(Recall.EASY, R.string.vocabulary_easy, Icons.Outlined.KeyboardDoubleArrowRight),
)

@Composable
private fun RecallTiles(word: VocabularyWord, enabled: Boolean, compact: Boolean, modifier: Modifier, onRate: (Recall) -> Unit) {
    val format = rememberIntervalFormat()
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 328.dp || fontScale > 1.3f) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recallButtons.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (rating, label, icon) ->
                        RecallTile(
                            rating, stringResource(label), icon,
                            format.formatInterval(scheduleVocabulary(word.streak, rating, 0).intervalMinutes),
                            enabled, compact, Modifier.weight(1f),
                        ) { onRate(rating) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecallTile(rating: Recall, label: String, icon: ImageVector, interval: String, enabled: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = recallColors(rating)
    Surface(
        onClick = onClick, enabled = enabled, color = colors.container, contentColor = colors.accent,
        border = BorderStroke(1.dp, colors.accent.copy(alpha = if (LocalEInk.current) 1f else 0.6f)),
        shape = RoundedCornerShape(18.dp), modifier = modifier,
    ) {
        if (compact) {
            Row(
                Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, Modifier.size(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    Text(interval, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        } else {
            Column(
                Modifier.heightIn(min = 104.dp).padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, contentDescription = null, Modifier.size(24.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                Text(interval, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}
