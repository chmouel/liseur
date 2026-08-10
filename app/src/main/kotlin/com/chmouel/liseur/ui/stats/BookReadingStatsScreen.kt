package com.chmouel.liseur.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.liseursync.WorkInsights
import com.chmouel.liseur.domain.BookReadingStats
import com.chmouel.liseur.ui.BusyIndicator
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * How much of one book has been read, and when it was last opened.
 *
 * Reached from the book's own long-press sheet, so it opens already
 * knowing which book is meant and never has to be searched for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReadingStatsScreen(
    title: String,
    state: BookReadingStatsUiState,
    onBack: () -> Unit,
    serverInsights: WorkInsights? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = if (state is BookReadingStatsUiState.Ready) {
                Alignment.TopStart
            } else {
                Alignment.Center
            },
        ) {
            if (state is BookReadingStatsUiState.Loading) {
                BusyIndicator()
                return@Box
            }
            Column(
                modifier = Modifier
                    .widthIn(max = contentWidthCap(windowWidth()))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (state is BookReadingStatsUiState.Empty) {
                    Text(
                        text = stringResource(R.string.reading_stats_book_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                val stats = (state as BookReadingStatsUiState.Ready).stats
                Text(
                    text = stringResource(R.string.reading_stats_total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = readingDuration(stats.totalMs),
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(
                        R.string.reading_stats_last_read,
                        Instant.ofEpochMilli(stats.lastReadAt)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(lastReadFormat()),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (stats.finished) {
                        stringResource(R.string.state_finished)
                    } else {
                        stats.progression?.let {
                            stringResource(
                                R.string.reading_stats_progress,
                                (it * 100).toInt(),
                            )
                        }.orEmpty()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only ever shown when the server had one to give. It
                // knows how fast this reader gets through books on every
                // device, which this screen cannot work out on its own,
                // and it answers null rather than guessing when it has
                // nothing to divide by. That null is respected here: no
                // estimate beats an invented one.
                serverInsights?.etaSeconds?.let { seconds ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(
                            R.string.reading_stats_time_left,
                            readingDuration(TimeUnit.SECONDS.toMillis(seconds.toLong())),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
