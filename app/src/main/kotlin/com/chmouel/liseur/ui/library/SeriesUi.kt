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

/**
 * Filing a book into a series by hand.
 *
 * Built around typing rather than around choosing, because a library
 * with two hundred series cannot be a list you scroll to the end of.
 * The one field does both jobs: what is typed narrows the series
 * underneath it, and if none of them is the one, what is typed *is* the
 * new series. So the same gesture joins *The Expanse* and starts
 * *Discworld*, and neither is a mode the reader has to find first.
 *
 * The list is lazy and uncapped. A shelf of two hundred costs two
 * hundred rows nobody draws, and cutting it off at eight would hide
 * exactly the series a reader with two hundred is looking for.
 *
 * The volume number is optional and stays optional. A reader who knows
 * these three books go together but not in which order is better served
 * by a shelf holding all three than by a field they cannot fill in.
 */
@Composable
internal fun SeriesAssignDialog(
    book: Book,
    seriesNames: List<String>,
    onConfirm: (String?, Double?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(book.url) {
        mutableStateOf(TextFieldValue(book.seriesName.orEmpty()))
    }
    var volume by remember(book.url) {
        mutableStateOf(seriesIndexLabel(book.seriesIndex).orEmpty())
    }

    val typed = name.text.trim()
    val choices = remember(typed, seriesNames) { seriesChoices(typed, seriesNames) }

    val pick: (String) -> Unit = { chosen ->
        // Tapping the series already picked unpicks it, which is how a
        // list of one-tap choices is expected to behave and is the only
        // way back to an empty field without reaching for backspace.
        val next = if (chosen == typed) "" else chosen
        // The caret goes to the end so the next keystroke carries on
        // typing rather than overwriting the name just chosen.
        name = TextFieldValue(text = next, selection = TextRange(next.length))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.series_assign_title)) },
        text = {
            // One lazy list holding the fields as well as the series.
            // A scrolling list inside a scrolling column is not allowed
            // in Compose, and of the two only the list can be lazy —
            // which is the whole point on a shelf of any size.
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.series_assign_name)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
                item {
                    OutlinedTextField(
                        value = volume,
                        onValueChange = { typedVolume ->
                            // Anything that is not going to parse is
                            // simply not accepted, rather than taken and
                            // rejected by a dialog that then loses the
                            // whole edit.
                            if (typedVolume.matches(VOLUME_INPUT)) volume = typedVolume
                        },
                        label = { Text(stringResource(R.string.series_assign_volume)) },
                        singleLine = true,
                        enabled = typed.isNotEmpty(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                // Said only when there is nothing underneath to explain
                // itself: an empty list under a name nobody else uses
                // otherwise reads as a search that failed.
                if (choices.isEmpty() && typed.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.series_assign_new, typed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                if (choices.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.series_assign_existing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(choices, key = { it }) { choice ->
                        SeriesChoiceRow(
                            name = choice,
                            selected = choice == typed,
                            onClick = { pick(choice) },
                        )
                    }
                }
                // Emptying a text field is not an obvious way to say "no
                // series", so it is said in words rather than left to be
                // discovered by the reader who tries it.
                if (!book.seriesName.isNullOrBlank()) {
                    item {
                        Text(
                            text = stringResource(R.string.series_assign_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                // Only worth offering once there is something to undo.
                if (book.seriesOverridden) {
                    item {
                        TextButton(
                            onClick = onReset,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) {
                            Text(stringResource(R.string.series_assign_reset))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(typed.ifEmpty { null }, volume.replace(',', '.').toDoubleOrNull())
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * One series to join, and whether this book is joining it.
 *
 * The tick is drawn only on the chosen one and its space is held open
 * on the others, so a list settling on a choice does not shuffle its
 * own text sideways.
 */
@Composable
private fun SeriesChoiceRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // Selectable rather than clickable: a screen reader should
            // say this is one of a set and which one is chosen, not that
            // there is a button here called The Expanse.
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The series worth offering for what has been typed so far.
 *
 * An empty field offers the whole shelf, because a book with no series
 * is nearly always joining one that is already there. Once something is
 * typed the list narrows the same way the library search does, so a
 * half-remembered name still finds its shelf — and a name that matches
 * nothing leaves an empty list, which is exactly the moment the reader
 * is creating a series rather than joining one.
 *
 * What starts with what was typed comes first. On a long shelf the
 * difference between the answer being at the top and being nineteen
 * rows down is the difference between typing three letters and typing
 * the whole name.
 *
 * The one already chosen stays in the list rather than being filtered
 * out as redundant: it is what shows the reader their choice took.
 */
internal fun seriesChoices(typed: String, names: List<String>): List<String> {
    if (typed.isEmpty()) return names
    val matches = names.filter { it == typed || matchesLibrarySearch(typed, it, null) }
    val (leading, rest) = matches.partition { it.startsWith(typed, ignoreCase = true) }
    return leading + rest
}

/** A number, or a number on its way to being typed. */
private val VOLUME_INPUT = Regex("^\\d{0,4}([.,]\\d{0,2})?$")
