package com.chmouel.liseur.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.calibre.DownloadProgress
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.data.db.DownloadState
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.seriesIndexLabel

internal const val SERIES_CONTROLS_KEY = "controls"
internal fun seriesBannerKey(seriesKey: String): String = "series:$seriesKey"

@Composable
internal fun SeriesOverview(
    series: List<SeriesShelf>,
    downloads: Map<String, DownloadProgress>,
    listState: LazyListState,
    controls: @Composable () -> Unit,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: (SeriesShelf) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = SERIES_CONTROLS_KEY) { controls() }
        items(series, key = { seriesBannerKey(it.key) }) { shelf ->
            SeriesBanner(shelf, downloads, onBookSelected, onBookLongPress, { onSeriesSelected(shelf) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesBanner(
    shelf: SeriesShelf,
    downloads: Map<String, DownloadProgress>,
    onBookSelected: (Book) -> Unit,
    onBookLongPress: (Book) -> Unit,
    onSeriesSelected: () -> Unit,
) {
    val next = shelf.nextUp
    val featured = next?.book ?: shelf.cover
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onSeriesSelected),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = shelf.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shelf.author?.takeIf { it.isNotBlank() }?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SeriesBannerCover(
                    book = featured,
                    progress = downloads[featured.url],
                    onClick = { onBookSelected(featured) },
                    onLongClick = { onBookLongPress(featured) },
                    modifier = Modifier.width(104.dp).aspectRatio(2f / 3f),
                )
                Column(Modifier.weight(1f)) {
                    val title = when {
                        next != null -> stringResource(
                            if (next.inProgress) R.string.series_continue else R.string.series_start,
                            next.book.displayTitle,
                        )
                        shelf.complete -> stringResource(R.string.series_all_known_read)
                        else -> featured.displayTitle
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.series_book_count, shelf.volumes.size, shelf.volumes.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shelf.volumes, key = { it.book.url }) { volume ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SeriesBannerCover(
                                    book = volume.book,
                                    progress = downloads[volume.book.url],
                                    onClick = { onBookSelected(volume.book) },
                                    onLongClick = { onBookLongPress(volume.book) },
                                    modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
                                )
                                seriesIndexLabel(volume.index)?.let { number ->
                                    Text(
                                        text = stringResource(R.string.series_volume_number, number),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesBannerCover(
    book: Book,
    progress: DownloadProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = book.displayTitle
    val state = bookStateDescription(book, progress)
    Box(
        modifier = modifier
            .combinedClickable(
                onClickLabel = stringResource(R.string.read_book),
                onLongClickLabel = stringResource(R.string.book_actions),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = state
            },
    ) {
        BookCover(book = book, modifier = Modifier.fillMaxSize().alpha(if (book.finished) 0.6f else 1f))
        when {
            progress != null -> DownloadOverlay(progress.fraction, progress.queued, Modifier.matchParentSize())
            book.downloadState != DownloadState.DOWNLOADED -> OnServerBadge(Modifier.align(Alignment.TopEnd))
        }
    }
}
