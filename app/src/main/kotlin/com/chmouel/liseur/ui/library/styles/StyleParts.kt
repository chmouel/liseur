package com.chmouel.liseur.ui.library.styles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.ui.library.ArchivedBadge
import com.chmouel.liseur.ui.library.BookCover
import com.chmouel.liseur.ui.library.DownloadOverlay
import com.chmouel.liseur.ui.library.FinishedBadge
import com.chmouel.liseur.ui.library.OnServerBadge
import com.chmouel.liseur.ui.library.SeriesIndexRibbon
import com.chmouel.liseur.ui.library.bookStateDescription

/** Whether [progression] is worth a bar: begun, and not yet done. */
internal fun Double?.isUnderway(book: Book): Boolean =
    this != null && this > 0.0 && this < 1.0 && !book.finished

/**
 * A book as a cover alone, with the same badges the grid card carries:
 * the download in flight, a book still on the server, a finished one, and
 * the series number when [seriesIndex] asks for it.
 *
 * The title and author are not drawn, so they go to the screen reader
 * instead. With [title] the title is also written under the cover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverTile(
    book: Book,
    download: DownloadProgress?,
    progression: Double?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    seriesIndex: Boolean = false,
    title: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val state = bookStateDescription(book, download)
    val label = listOfNotNull(book.displayTitle, book.displayAuthor).joinToString(", ")
    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = stringResource(R.string.read_book),
                onLongClickLabel = stringResource(R.string.book_actions),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = state
            },
    ) {
        Box {
            BookCover(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .alpha(if (book.finished) 0.55f else 1f),
            )
            when {
                download != null -> DownloadOverlay(
                    fraction = download.fraction,
                    queued = download.queued,
                    modifier = Modifier.matchParentSize(),
                )

                book.downloadState != DownloadState.DOWNLOADED ->
                    OnServerBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
            if (book.finished && download == null) {
                FinishedBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            if (book.archived && download == null) {
                ArchivedBadge(Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            if (download == null && seriesIndex) {
                SeriesIndexRibbon(index = book.seriesIndex, modifier = Modifier.align(Alignment.TopStart))
            }
            // Inside the cover rather than under it, so a book being read
            // keeps the same height as its neighbours: titles in a strip
            // line up and every cover stands on the shelf.
            if (download == null && progression.isUnderway(book)) {
                LinearProgressIndicator(
                    progress = { progression!!.toFloat() },
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
        }
        if (title) {
            Text(
                text = book.displayTitle,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The title over a strip or a shelf, with what is on it on the right.
 * With [onClick] the whole line opens what it names, and says so with a
 * chevron.
 */
@Composable
internal fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onClickLabel = stringResource(R.string.series_open))
                } else {
                    Modifier
                },
            )
            .semantics { heading() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
