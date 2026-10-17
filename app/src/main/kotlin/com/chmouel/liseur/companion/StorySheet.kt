package com.chmouel.liseur.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.BusyBar
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import org.readium.r2.shared.publication.Locator

/** The story so far: the chapters before the place it was opened from, with their stored summaries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorySheet(
    panel: StoryPanel,
    onChapter: (Locator) -> Unit,
    onDismiss: () -> Unit,
    // Held by the caller: the sheet leaves the screen during a peek, and
    // Return should land where the reader was in the list.
    list: LazyListState = rememberLazyListState(),
) {
    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.story_title), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
            when {
                panel.loading -> BusyBar()
                panel.failed -> Text(stringResource(R.string.story_failed))
                panel.chapters.isEmpty() -> Text(stringResource(R.string.story_nothing_before))
                else -> LazyColumn(Modifier.weight(1f), state = list) {
                    item(key = "story:about") {
                        Text(
                            stringResource(R.string.story_about),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(panel.chapters, key = { it.unit }) { chapter ->
                        StoryRow(chapter, onChapter)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryRow(chapter: StoryChapter<Locator>, onChapter: (Locator) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            chapter.title ?: stringResource(R.string.codex_chapter, chapter.unit + 1),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        when (val recap = chapter.recap) {
            is StoryRecap.Summary -> recap.sentences.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            else -> Text(
                stringResource(
                    when (recap) {
                        StoryRecap.NoSummary -> R.string.story_no_summary
                        StoryRecap.Unreadable -> R.string.story_unreadable
                        else -> R.string.story_missing
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val start = chapter.start
        TextButton(onClick = { start?.let(onChapter) }, enabled = start != null) {
            Text(stringResource(R.string.story_go))
        }
        if (start == null) {
            Text(
                stringResource(R.string.story_go_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
