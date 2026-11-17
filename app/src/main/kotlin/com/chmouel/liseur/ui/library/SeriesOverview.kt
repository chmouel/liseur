package com.chmouel.liseur.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.theme.DarkHomePalette
import com.chmouel.liseur.ui.theme.homePalette
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

/**
 * A series on the Series tab: the volume to open next large on the left,
 * and beside it the name, the author, how many books, what comes next,
 * every volume in a row and a rail of what has been read.
 *
 * The card takes a faint wash of the featured cover's colour, so a
 * column of series reads as a column of different books rather than a
 * stack of identical boxes. On e-ink it is an outline and nothing more.
 */
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
    val eInk = LocalEInk.current
    val palette = homePalette()
    val dark = palette == DarkHomePalette
    val shape = RoundedCornerShape(22.dp)
    val accent = palette?.progress ?: MaterialTheme.colorScheme.primary
    val card = palette?.card ?: Color.Transparent
    val tint = rememberCoverTint(featured)
    val wash by animateColorAsState(
        targetValue = when {
            palette == null -> Color.Transparent
            tint != null -> lerp(card, tint, if (dark) 0.38f else 0.16f)
            else -> lerp(card, palette.heroWash, if (dark) 0.9f else 0.8f)
        },
        animationSpec = tween(durationMillis = 400),
        label = "seriesWash",
    )
    val background = if (palette == null) {
        Modifier
    } else {
        Modifier.background(Brush.linearGradient(listOf(wash, card), end = Offset(900f, 700f)))
    }
    val state = seriesStateDescription(shelf)
    Surface(
        shape = shape,
        color = card,
        border = when {
            eInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            palette != null -> BorderStroke(1.dp, palette.cardBorder)
            else -> null
        },
        shadowElevation = if (palette != null && !dark) 3.dp else 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .then(background)
                .clickable(onClickLabel = stringResource(R.string.series_open), onClick = onSeriesSelected)
                .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeriesBannerCover(
                book = featured,
                progress = downloads[featured.url],
                onClick = { onBookSelected(featured) },
                onLongClick = { onBookLongPress(featured) },
                shape = RoundedCornerShape(12.dp),
                elevation = 6.dp,
                modifier = Modifier.width(116.dp).aspectRatio(2f / 3f),
            )
            Column(
                Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                        .semantics(mergeDescendants = true) { stateDescription = state },
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent),
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(
                            text = shelf.name,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        shelf.author?.takeIf { it.isNotBlank() }?.let { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.bodyMedium,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Icon(
                        Icons.Outlined.Layers,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = pluralStringResource(R.plurals.series_book_count, shelf.volumes.size, shelf.volumes.size),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                val upNext = when {
                    next != null -> stringResource(
                        if (next.inProgress) R.string.series_continue else R.string.series_start,
                        next.book.displayTitle,
                    )
                    shelf.complete -> stringResource(R.string.series_all_known_read)
                    else -> null
                }
                upNext?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(shelf.volumes, key = { it.book.url }) { volume ->
                        SeriesBannerCover(
                            book = volume.book,
                            progress = downloads[volume.book.url],
                            onClick = { onBookSelected(volume.book) },
                            onLongClick = { onBookLongPress(volume.book) },
                            shape = RoundedCornerShape(8.dp),
                            elevation = 3.dp,
                            index = volume.index,
                            modifier = Modifier.width(52.dp).aspectRatio(2f / 3f),
                        )
                    }
                }
                SeriesProgressRail(
                    shelf = shelf,
                    read = accent,
                    untouched = palette?.track ?: MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette?.onPill ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
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
    shape: Shape,
    elevation: Dp,
    modifier: Modifier = Modifier,
    index: Double? = null,
) {
    val eInk = LocalEInk.current
    val description = book.displayTitle
    val state = bookStateDescription(book, progress)
    Box(
        modifier = modifier
            .then(if (eInk) Modifier else Modifier.shadow(elevation, shape))
            .clip(shape)
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
        BookCover(book = book, shape = shape, modifier = Modifier.fillMaxSize().alpha(if (book.finished) 0.6f else 1f))
        VolumeBadge(index, Modifier.align(Alignment.TopStart))
        when {
            progress != null -> DownloadOverlay(progress.fraction, progress.queued, Modifier.matchParentSize())
            book.downloadState != DownloadState.DOWNLOADED -> OnServerBadge(Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The volume number on a thumbnail: [SeriesIndexRibbon] cut down to the size of one. */
@Composable
private fun VolumeBadge(index: Double?, modifier: Modifier = Modifier) {
    val label = seriesIndexLabel(index) ?: return
    Text(
        text = stringResource(R.string.series_volume_number, label),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        fontWeight = FontWeight.Bold,
        color = CoverBadgeContent,
        modifier = modifier
            .clip(RoundedCornerShape(bottomEnd = 6.dp))
            .background(CoverBadgeScrim)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
