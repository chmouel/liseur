package com.chmouel.liseur.companion

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.BusyBar
import com.chmouel.liseur.ui.LocalEInk
import org.readium.r2.shared.publication.Locator

/** What the Story tab can ask of the companion. */
@Immutable
class StoryActions(
    val allow: () -> Unit,
    val decline: () -> Unit,
    val summarize: (unit: Int) -> Unit,
    val summarizeMissing: () -> Unit,
    val write: () -> Unit,
    val stop: () -> Unit,
)

/**
 * The Story tab: the running summary first, then the earlier chapters
 * newest first, so the one just read sits under the summary.
 */
internal fun LazyListScope.storyTab(
    story: StoryView?,
    actions: StoryActions,
    onChapter: (Locator) -> Unit,
    onChooseModel: () -> Unit,
    onCharacter: (String) -> Unit = {},
) {
    when {
        story == null || story.loading -> item(key = "story:loading") { BusyBar() }
        story.failed -> item(key = "story:failed") { StoryNote(stringResource(R.string.story_failed)) }
        story.chapters.isEmpty() -> item(key = "story:empty") { StoryNote(stringResource(R.string.story_nothing_before)) }
        else -> {
            if (story.asking) item(key = "story:consent") { StoryConsent(story.service, actions) }
            story.work?.let { work -> item(key = "story:work") { StoryWorkStrip(work, actions.stop) } }
            // Before consent an empty card only repeats what the consent card asks.
            if (!story.asking || story.soFar.text != null || story.soFar.writing) {
                item(key = "story:sofar") { StorySoFarCard(story.soFar, actions, onChooseModel) }
            }
            if (story.missing > 0 && !story.asking) item(key = "story:missing") {
                SummarizeMissing(story.missing, story.service, actions.summarizeMissing)
            }
            val newestFirst = story.chapters.asReversed()
            itemsIndexed(newestFirst, key = { _, chapter -> "story:${chapter.unit}" }) { index, chapter ->
                StoryRow(
                    chapter = chapter,
                    first = index == 0,
                    last = index == newestFirst.lastIndex,
                    onSummarize = { actions.summarize(chapter.unit) },
                    onChapter = onChapter,
                    onChooseModel = onChooseModel,
                    onCharacter = onCharacter,
                )
            }
        }
    }
}

@Composable
private fun StoryNote(text: String) {
    Text(
        text,
        Modifier.padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Text cut at [lines], with a Show more / Show less button only when there is more to show. */
@Composable
private fun ExpandableText(text: String, style: TextStyle, lines: Int) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column {
        Text(
            text,
            style = style,
            maxLines = if (open) Int.MAX_VALUE else lines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) overflows = it.hasVisualOverflow },
            modifier = Modifier.animateContentSize(),
        )
        if (open || overflows) {
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(vertical = 8.dp)) {
                Text(stringResource(if (open) R.string.story_less else R.string.story_more))
            }
        }
    }
}

@Composable
private fun StoryCard(content: @Composable () -> Unit) {
    val eInk = LocalEInk.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = if (eInk) CardDefaults.outlinedCardColors() else CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun StoryConsent(service: String?, actions: StoryActions) {
    StoryCard {
        Text(stringResource(R.string.story_consent_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.story_consent, service ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = actions.allow) { Text(stringResource(R.string.story_allow)) }
            TextButton(onClick = actions.decline) { Text(stringResource(R.string.story_not_now)) }
        }
    }
}

@Composable
private fun StoryWorkStrip(work: StoryWork, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val doing = when {
                work.working != null && work.batches > 1 ->
                    stringResource(R.string.story_working_part, work.working + 1, work.batch, work.batches)
                work.working != null -> stringResource(R.string.story_working, work.working + 1)
                work.summarizing -> stringResource(R.string.story_writing)
                else -> null
            }
            Column(Modifier.weight(1f)) {
                doing?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (work.waiting > 0) {
                    Text(
                        pluralStringResource(R.plurals.story_waiting, work.waiting, work.waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onStop) { Text(stringResource(R.string.story_stop)) }
        }
        BusyBar()
    }
}

@Composable
private fun StorySoFarCard(soFar: StorySoFar, actions: StoryActions, onChooseModel: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val text = soFar.text
    StoryCard {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (text != null) Modifier.clickable(role = Role.Button) { open = !open } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.story_so_far),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (text != null) {
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(if (open) R.string.story_less else R.string.story_more),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (text != null && open) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        if (text != null) {
            soFar.through?.let { through ->
                Text(
                    if (through == 0) stringResource(R.string.story_covers_first)
                    else stringResource(R.string.story_covers, through + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (soFar.unsummarized.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.story_unsummarized,
                        soFar.unsummarized.size,
                        StorySummaries.ranges(soFar.unsummarized.map { it + 1 }, "–"),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (!soFar.writing && soFar.failed == null) {
            Text(
                stringResource(if (soFar.writable) R.string.story_none_yet else R.string.story_none_possible),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val failed = soFar.failed
        when {
            soFar.writing -> {
                Text(
                    stringResource(R.string.story_updating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BusyBar()
            }
            failed != null -> FailureMessage(failed, onRetry = actions.write, onChooseModel = onChooseModel)
            soFar.writable && !soFar.current -> {
                if (text != null) {
                    Text(
                        stringResource(R.string.story_outdated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = actions.write) {
                    Text(stringResource(if (text == null) R.string.story_write else R.string.story_update))
                }
            }
        }
        if ((text != null && open) || soFar.writing) {
            Text(
                stringResource(R.string.story_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummarizeMissing(count: Int, service: String?, onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val label = pluralStringResource(R.plurals.story_summarize_missing, count, count)
    OutlinedButton(onClick = { confirming = true }, modifier = Modifier.padding(bottom = 12.dp)) { Text(label) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(label) },
            text = { Text(pluralStringResource(R.plurals.story_confirm_missing, count, count, service ?: "")) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onConfirm() }) { Text(stringResource(R.string.story_summarize)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One chapter on the rail: its node, title and state, then the summary and what can be done with it. */
@Composable
private fun StoryRow(
    chapter: StoryChapter<Locator>,
    first: Boolean,
    last: Boolean,
    onSummarize: () -> Unit,
    onChapter: (Locator) -> Unit,
    onChooseModel: () -> Unit,
    onCharacter: (String) -> Unit,
) {
    val recap = chapter.recap
    val progress = chapter.progress
    val summarized = recap is StoryRecap.Summary
    val failed = progress is StoryProgress.Failed
    val colors = MaterialTheme.colorScheme
    val node = when {
        failed -> colors.error
        summarized -> colors.primary
        else -> colors.outline
    }
    val line = colors.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = RAIL_X.toPx()
                val y = NODE_Y.toPx()
                val r = NODE_RADIUS.toPx()
                val stroke = 2.dp.toPx()
                if (!first) drawLine(line, Offset(x, 0f), Offset(x, y - r), stroke)
                if (!last) drawLine(line, Offset(x, y + r), Offset(x, size.height), stroke)
                if (summarized) drawCircle(node, r, Offset(x, y))
                else drawCircle(node, r - stroke / 2, Offset(x, y), style = Stroke(stroke))
                if (progress is StoryProgress.Working) drawCircle(node, r / 2, Offset(x, y))
            }
            .padding(start = RAIL_WIDTH, bottom = 16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The number ties the row to "covers chapters 1–9" and the work strip.
                val title = chapter.title?.let { "${chapter.unit + 1} · $it" }
                    ?: chapterLabel(ChapterRange(chapter.unit, chapter.unit), emptyList())
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    statusLabel(chapter)?.let { (label, color) ->
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
                chapter.start?.let { start ->
                    IconButton(onClick = { onChapter(start) }) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = stringResource(R.string.story_go, title))
                    }
                }
            }
            if (recap is StoryRecap.Summary) {
                ExpandableText(recap.sentences.joinToString(" "), MaterialTheme.typography.bodyMedium, CHAPTER_LINES)
            }
            if (chapter.characters.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chapter.characters.forEach { name ->
                        SuggestionChip(onClick = { onCharacter(name) }, label = { Text(name) })
                    }
                }
            }
            if (chapter.start == null) {
                Text(
                    stringResource(R.string.story_go_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (progress is StoryProgress.Failed) {
                FailureMessage(progress.failed, onRetry = onSummarize, onChooseModel = onChooseModel)
            } else if (chapter.askable) {
                FlowRow {
                    TextButton(onClick = onSummarize) {
                        Text(stringResource(if (recap == StoryRecap.Unreadable) R.string.story_summarize_again else R.string.story_summarize))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(chapter: StoryChapter<*>): Pair<String, Color>? {
    val colors = MaterialTheme.colorScheme
    return when (chapter.progress) {
        StoryProgress.Queued -> stringResource(R.string.story_status_queued) to colors.onSurfaceVariant
        is StoryProgress.Working -> stringResource(R.string.story_status_working) to colors.primary
        is StoryProgress.Failed -> stringResource(R.string.story_status_failed) to colors.error
        StoryProgress.Idle -> when (chapter.recap) {
            StoryRecap.Missing -> stringResource(R.string.story_status_missing) to colors.onSurfaceVariant
            StoryRecap.NoSummary -> stringResource(R.string.story_no_summary) to colors.onSurfaceVariant
            StoryRecap.Unreadable -> stringResource(R.string.story_unreadable) to colors.error
            is StoryRecap.Summary -> null
        }
    }
}

private const val CHAPTER_LINES = 3
private val RAIL_WIDTH = 28.dp
private val RAIL_X = 8.dp
private val NODE_Y = 12.dp
private val NODE_RADIUS = 6.dp
