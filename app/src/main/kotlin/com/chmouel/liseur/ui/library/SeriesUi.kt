package com.chmouel.liseur.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.SeriesShelf
import com.chmouel.liseur.domain.matchesLibrarySearch
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.ui.LocalEInk

/**
 * Where a book sits in its series, under its title: *The Expanse · #3*.
 *
 * Deliberately quiet, in the same style and colour as the author's name.
 * It is context for the title above it, not a second title, and a shelf
 * where every card shouts two lines is a shelf nobody can scan.
 */
@Composable
internal fun SeriesLine(book: Book, modifier: Modifier = Modifier) {
    val series = book.seriesName?.takeIf { it.isNotBlank() } ?: return
    val index = seriesIndexLabel(book.seriesIndex)
    Text(
        text = if (index == null) {
            series
        } else {
            stringResource(R.string.series_position, series, index)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The volume number, in the corner of the cover.
 *
 * The other two corners are spoken for — the server badge top right, the
 * finished tick bottom right — and the number wants to be readable
 * against artwork rather than against a surface, so it brings its own
 * dark ground the way those badges do.
 */
@Composable
internal fun SeriesIndexRibbon(index: Double?, modifier: Modifier = Modifier) {
    val label = seriesIndexLabel(index) ?: return
    Surface(
        shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        color = CoverBadgeScrim,
        contentColor = CoverBadgeContent,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.series_volume_number, label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * A whole series as one card: a pile of books rather than a book.
 *
 * The cover shown is the volume you would open next, and behind it two
 * offset slivers stand for the rest of the pile — which is what makes a
 * series legible at shelf-scanning speed, before any of the text is
 * read. The count sits on the corner, and a rail along the bottom fills
 * in a segment per volume finished, so how far through a series you are
 * is answerable without opening it.
 *
 * On e-paper the pile is drawn as outlines and the rail as blocks. The
 * fan relies on translucency and a soft edge, neither of which survives
 * a screen with sixteen greys and no compositing to speak of.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SeriesStackCard(
    shelf: SeriesShelf,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val eInk = LocalEInk.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    val description = seriesStateDescription(shelf)

    Column(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
                onClickLabel = stringResource(R.string.series_open),
                onLongClickLabel = stringResource(R.string.series_actions),
            )
            .semantics(mergeDescendants = true) { stateDescription = description },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Room to the right and below for the pile to stand in,
                // so the fan is drawn beside the cover rather than over
                // the card next to it.
                .aspectRatio(2f / 3f * 0.92f),
        ) {
            // The two behind, furthest first. Insets rather than
            // rotations: a fan of tilted covers looks like a mistake at
            // this size, and a stepped pile reads instantly.
            repeat(2) { layer ->
                val depth = (2 - layer)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = (depth * 5).dp, bottom = (depth * 5).dp)
                        .padding(start = (10 - depth * 5).dp, top = (10 - depth * 5).dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (eInk) Color.Transparent
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .border(
                            BorderStroke(1.dp, outline.copy(alpha = if (eInk) 0.9f else 0.5f)),
                            RoundedCornerShape(10.dp),
                        ),
                )
            }
            BookCover(
                book = shelf.cover,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp, bottom = 10.dp)
                    .alpha(if (shelf.complete) 0.55f else 1f),
            )
            SeriesCountBadge(
                count = shelf.volumes.size,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 6.dp),
            )
        }
        SeriesProgressRail(
            shelf = shelf,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, end = 10.dp),
        )
        Column(
            modifier = Modifier
                .padding(top = 6.dp)
                .heightIn(min = 56.dp),
        ) {
            Text(
                text = shelf.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            shelf.author?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How many books are in the pile. */
@Composable
private fun SeriesCountBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * One segment per volume, filled for the ones that are read.
 *
 * A bar showing a percentage would say the same thing less usefully:
 * segments are countable, so "three of five" is read off the shelf
 * without a number being written anywhere.
 */
@Composable
private fun SeriesProgressRail(shelf: SeriesShelf, modifier: Modifier = Modifier) {
    val read = MaterialTheme.colorScheme.primary
    val started = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val untouched = MaterialTheme.colorScheme.surfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.height(4.dp),
    ) {
        shelf.volumes.forEach { volume ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            volume.finished -> read
                            volume.inProgress -> started
                            else -> untouched
                        },
                    ),
            )
        }
    }
}

/**
 * How far through a pile of books the reader is, in words.
 *
 * Both the line under the title and what a screen reader is told are
 * built from this, because a reader who cannot see the progress rail is
 * the one who most needs to be told a book is on the go. Counting them
 * separately is how "two started" ends up drawn on screen and never
 * said aloud.
 */
@Composable
private fun seriesMetaParts(shelf: SeriesShelf): List<String> = buildList {
    add(
        pluralStringResource(
            R.plurals.series_book_count,
            shelf.volumes.size,
            shelf.volumes.size,
        ),
    )
    if (shelf.finishedCount > 0) {
        add(stringResource(R.string.series_read_count, shelf.finishedCount))
    }
    if (shelf.inProgressCount > 0) {
        add(stringResource(R.string.series_in_progress_count, shelf.inProgressCount))
    }
}

/** What a screen reader should say about a pile of books. */
@Composable
internal fun seriesStateDescription(shelf: SeriesShelf): String =
    seriesMetaParts(shelf).joinToString(stringResource(R.string.series_meta_separator))

/** A pile of books, and how far through it the reader is. */
@Composable
internal fun SeriesSummaryLine(shelf: SeriesShelf, modifier: Modifier = Modifier) {
    Text(
        text = seriesMetaParts(shelf)
            .joinToString(stringResource(R.string.series_meta_separator)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun seriesSourceLabel(book: Book): String? = when {
    book.seriesOverridden -> stringResource(R.string.series_source_personal)
    book.catalogSeriesSource == "folder" -> stringResource(R.string.series_source_folder)
    book.catalogSeriesSource == "shared" -> stringResource(R.string.series_source_shared)
    book.catalogSeriesSource == "personal" -> stringResource(R.string.series_source_personal)
    else -> null
}

/** A number, or a number on its way to being typed. */
internal val VOLUME_INPUT = Regex("^\\d{0,4}([.,]\\d{0,2})?$")
