package com.chmouel.liseur.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.Book
import com.chmouel.liseur.domain.OTHER_INITIAL
import com.chmouel.liseur.domain.RankedSeries
import com.chmouel.liseur.domain.SeriesPickOption
import com.chmouel.liseur.domain.displayAuthor
import com.chmouel.liseur.domain.displayTitle
import com.chmouel.liseur.domain.rankSeriesOptions
import com.chmouel.liseur.domain.recentSeries
import com.chmouel.liseur.domain.seriesIndexLabel
import com.chmouel.liseur.domain.seriesKey
import com.chmouel.liseur.domain.seriesInitial
import com.chmouel.liseur.domain.sortKey
import com.chmouel.liseur.domain.suggestedSeries
import com.chmouel.liseur.domain.suggestedVolume
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import kotlinx.coroutines.launch

/**
 * Filing a book into a series.
 *
 * The dialog this replaces asked a library of two hundred series to fit
 * in a box the size of a message, with its own text fields scrolling
 * away inside the list of choices. This is a sheet the height of the
 * screen: the book and the search stay pinned at the top, the number
 * and the buttons stay pinned at the bottom, and everything between
 * them is the shelf.
 *
 * Searching and naming are separate here, which they were not before.
 * The field on top *finds*; choosing is a tap on a row, and starting a
 * new series is a tap on a row that says so. One field doing both jobs
 * reads fine on a library of six and stops being answerable on a
 * library of two hundred, where nearly everything typed is a search and
 * the reader cannot tell whether the app agrees.
 *
 * Nothing is written until Save. Suggestions, the recent shelves and
 * the offered volume number are guesses, and a guess that costs a
 * glance is worth making; a guess that files a book is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesPickerSheet(
    book: Book,
    options: List<SeriesPickOption>,
    canResetSharedSeries: Boolean = false,
    onConfirm: (String?, Double?) -> Unit,
    onReset: () -> Unit,
    onResetShared: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var query by remember(book.url) { mutableStateOf("") }
    var chosen by remember(book.url) {
        mutableStateOf(book.seriesName?.takeIf { it.isNotBlank() })
    }
    var volume by remember(book.url) {
        mutableStateOf(seriesIndexLabel(book.seriesIndex).orEmpty())
    }
    // The number brought in with the book belongs to its old series. A
    // number typed in this sheet belongs to the reader and travels with
    // a later change of mind; an inherited or suggested one does not.
    var volumeTyped by remember(book.url) { mutableStateOf(false) }

    val typed = query.trim()
    val ranked = remember(typed, options) { rankSeriesOptions(typed, options) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // A narrowed list read from wherever the reader had scrolled to is a
    // list of results they cannot see. Every keystroke puts the best
    // answer back under the field that asked for it.
    LaunchedEffect(typed) { listState.scrollToItem(0) }

    val pick: (SeriesPickOption) -> Unit = { option ->
        // Tapping the chosen one unpicks it, which is how a list of
        // one-tap choices is expected to behave and is the only way back
        // to "no series" other than the row that says so.
        if (seriesKey(chosen) == option.key) {
            chosen = null
        } else {
            chosen = option.name
            // Offered, not imposed: a shelf numbered to eight suggests
            // nine, and a reader who does not know where this one goes
            // clears it. The old series's number is a suggestion too:
            // it must not turn Star Wars into volume four merely because
            // this book used to be volume four of The Expanse.
            if (!volumeTyped) {
                volume = seriesIndexLabel(suggestedVolume(option)).orEmpty()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxHeight()
                // The buttons are at the bottom and the field that
                // raises the keyboard is at the top, so without this the
                // reader types their way out of reach of Save.
                .imePadding(),
        ) {
            SeriesPickerHeader(
                book = book,
                query = query,
                onQueryChange = { query = it },
            )
            HorizontalDivider()
            SeriesPickerList(
                sections = pickerSections(
                    book = book,
                    options = options,
                    ranked = ranked,
                    typed = typed,
                    canResetSharedSeries = canResetSharedSeries,
                ),
                chosen = chosen,
                listState = listState,
                onPick = pick,
                onCreate = {
                    chosen = it
                    // Nothing to suggest from a series that does not
                    // exist yet; a suggestion made by another shelf
                    // must not follow the book into this one.
                    if (!volumeTyped) {
                        volume = ""
                    }
                },
                onRemove = { chosen = null },
                onReset = onReset,
                onResetShared = onResetShared,
                onJump = { index -> scope.launch { listState.animateScrollToItem(index) } },
                modifier = Modifier.weight(1f),
            )
            HorizontalDivider()
            SeriesPickerFooter(
                chosen = chosen,
                volume = volume,
                onVolumeChange = { typedVolume ->
                    // Anything that is not going to parse is simply not
                    // accepted, rather than taken and rejected by a
                    // sheet that then loses the whole edit.
                    if (typedVolume.matches(VOLUME_INPUT)) {
                        volume = typedVolume
                        volumeTyped = true
                    }
                },
                onCancel = onDismiss,
                onSave = {
                    onConfirm(chosen, volume.replace(',', '.').toDoubleOrNull())
                },
            )
        }
    }
}

/** The book being filed, and the field that finds where to file it. */
@Composable
private fun SeriesPickerHeader(book: Book, query: String, onQueryChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                book = book,
                modifier = Modifier
                    .width(44.dp)
                    .aspectRatio(2f / 3f),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                seriesSourceLabel(book)?.let {
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
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.series_assign_name)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(
                                R.string.series_assign_clear_search,
                            ),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp),
        )
    }
}

/**
 * The shelf, in sections, with a letter rail beside it.
 *
 * The rail is the answer to a library that does not fit: two hundred
 * series is thirty screens of scrolling and one tap of jumping. It is
 * only worth its width once there is enough to get lost in, and it has
 * nothing to point at while a search is narrowing the list, so it comes
 * and goes with both.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesPickerList(
    sections: List<PickerSection>,
    chosen: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPick: (SeriesPickOption) -> Unit,
    onCreate: (String) -> Unit,
    onRemove: () -> Unit,
    onReset: () -> Unit,
    onResetShared: () -> Unit,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val jumps = remember(sections) { letterJumps(sections) }
    Row(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
        ) {
            sections.forEach { section ->
                section.label?.let { label ->
                    stickyHeader(key = "header:$label") {
                        SectionHeader(label)
                    }
                }
                items(section.entries, key = { "${section.label}/${it.key}" }) { entry ->
                    when (entry) {
                        is PickerEntry.Series -> SeriesOptionRow(
                            ranked = entry.ranked,
                            // Keyed rather than spelled: the shelf
                            // shows its commonest spelling, which is
                            // not always the one this book stored.
                            selected = entry.ranked.option.key == seriesKey(chosen),
                            onClick = { onPick(entry.ranked.option) },
                        )

                        is PickerEntry.Create -> ActionRow(
                            icon = Icons.Outlined.Add,
                            label = stringResource(R.string.series_assign_create, entry.name),
                            selected = entry.name == chosen,
                            onClick = { onCreate(entry.name) },
                        )

                        is PickerEntry.Remove -> ActionRow(
                            icon = Icons.Outlined.Close,
                            label = stringResource(R.string.series_assign_remove),
                            selected = chosen == null,
                            onClick = onRemove,
                        )

                        is PickerEntry.Reset -> Box(Modifier.padding(horizontal = 12.dp)) {
                            TextButton(
                                onClick = { if (entry.shared) onResetShared() else onReset() },
                            ) {
                                Text(
                                    stringResource(
                                        if (entry.shared) {
                                            R.string.series_assign_reset_shared
                                        } else {
                                            R.string.series_assign_reset
                                        },
                                    ),
                                )
                            }
                        }

                        is PickerEntry.Note -> Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                }
            }
        }
        if (jumps.size > 1) {
            LetterRail(jumps = jumps, onJump = onJump)
        }
    }
}

/** A landmark in the list: a letter, or the name of a section. */
@Composable
private fun SectionHeader(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
}

/**
 * One series to file into, with enough around the name to choose by.
 *
 * The cover is what a reader recognises a shelf by long before they
 * read its name, and the count is what tells the eight-volume *Expanse*
 * from a neighbour spelled almost the same. What the search matched is
 * emboldened, so a row that came back for a reason shows the reason.
 */
@Composable
private fun SeriesOptionRow(ranked: RankedSeries, selected: Boolean, onClick: () -> Unit) {
    val option = ranked.option
    val eInk = LocalEInk.current
    val background by animateColorAsState(
        targetValue = when {
            !selected -> Color.Transparent
            eInk -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        label = "seriesRowBackground",
    )
    val count = pluralStringResource(
        R.plurals.series_book_count,
        option.volumeCount,
        option.volumeCount,
    )
    val separator = stringResource(R.string.series_meta_separator)
    val chosenLabel = stringResource(R.string.series_assign_selected)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (eInk) Color.Transparent else background)
            // Selectable rather than clickable: a screen reader should
            // say this is one of a set and which one is chosen, not that
            // there is a button here called The Expanse.
            .selectable(selected = selected, onClick = onClick)
            .semantics(mergeDescendants = true) {
                stateDescription = if (selected) "$chosenLabel$separator$count" else count
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        BookCover(
            book = option.cover,
            modifier = Modifier
                .width(36.dp)
                .aspectRatio(2f / 3f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = highlighted(option.name, ranked.matches),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(option.author, count).joinToString(separator),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SelectedTick(selected)
    }
}

/** Starting a new series, or taking the book out of the one it is in. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(36.dp)
                .padding(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SelectedTick(selected)
    }
}

/**
 * The tick on the chosen row.
 *
 * Its space is held open on the others, so a list settling on a choice
 * does not shuffle its own text sideways. On e-paper it simply appears:
 * a spring drawn at three frames a second is a smear, not a flourish.
 */
@Composable
private fun SelectedTick(selected: Boolean) {
    val eInk = LocalEInk.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        label = "seriesRowTick",
    )
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (selected || (!eInk && scale > 0f)) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .scale(if (eInk) 1f else scale),
            )
        }
    }
}

/** A–Z down the edge, for a library too long to scroll. */
@Composable
private fun LetterRail(jumps: List<Pair<String, Int>>, onJump: (Int) -> Unit) {
    fun resolve(y: Float, height: Int): Int {
        val idx = (y / (height.toFloat() / jumps.size)).toInt()
        return idx.coerceIn(jumps.indices)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(jumps) {
                detectTapGestures { offset ->
                    onJump(jumps[resolve(offset.y, size.height)].second)
                }
            }
            .pointerInput(jumps) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        onJump(jumps[resolve(offset.y, size.height)].second)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onJump(jumps[resolve(change.position.y, size.height)].second)
                    },
                )
            },
    ) {
        jumps.forEachIndexed { i, (letter, _) ->
            val jumpTo = stringResource(R.string.series_assign_jump, letter)
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics {
                        onClick(label = jumpTo) { onJump(jumps[i].second); true }
                    },
            )
        }
    }
}

/** The number this book carries in the series, and the way out. */
@Composable
private fun SeriesPickerFooter(
    chosen: String?,
    volume: String,
    onVolumeChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 20.dp),
    ) {
        // What Save is about to write, said in words. The search field
        // no longer holds the name, so without this the reader's choice
        // lives only as a tick eight rows up the list they have since
        // scrolled past.
        Text(
            text = chosen ?: stringResource(R.string.series_assign_remove),
            style = MaterialTheme.typography.titleSmall,
            color = if (chosen == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = volume,
            onValueChange = onVolumeChange,
            label = { Text(stringResource(R.string.series_assign_volume)) },
            singleLine = true,
            // A number without a series is a number about nothing.
            enabled = chosen != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave) { Text(stringResource(R.string.save)) }
        }
    }
}

/**
 * What the list holds, in the order it holds it.
 *
 * With nothing typed the sheet opens on guesses and on what was read
 * lately, then the whole shelf under its letters — which is the only
 * arrangement that stays usable as a library grows, because the reader
 * never has to read past the part they are not looking in.
 *
 * With something typed there is one list, best first, because ranking
 * and sectioning fight: a match in the A section is not worth less than
 * one in the B section, and splitting them says it is. Above it sits
 * the way back from the dialog this replaced: a row that turns what was
 * typed into a new series, because a field that only searches leaves
 * the reader who typed a new name and hit Save with nowhere for it to
 * have gone.
 */
@Composable
private fun pickerSections(
    book: Book,
    options: List<SeriesPickOption>,
    ranked: List<RankedSeries>,
    typed: String,
    canResetSharedSeries: Boolean,
): List<PickerSection> {
    val suggestedLabel = stringResource(R.string.series_assign_suggested)
    val recentLabel = stringResource(R.string.series_assign_recent)
    val allLabel = stringResource(R.string.series_assign_existing)
    val otherLabel = stringResource(R.string.series_assign_other_initial)
    val emptyNote = stringResource(R.string.series_assign_none, typed)
    val hasSeries = !book.seriesName.isNullOrBlank()
    val canReset = book.seriesOverridden
    val canResetShared = !book.seriesOverridden &&
        canResetSharedSeries && book.catalogSeriesSource == "shared"
    val title = book.displayTitle
    val author = book.displayAuthor

    return remember(
        typed,
        ranked,
        options,
        hasSeries,
        canReset,
        canResetShared,
        title,
        author,
        suggestedLabel,
        recentLabel,
        allLabel,
        otherLabel,
        emptyNote,
    ) {
        buildList {
            val tail = buildList {
                if (hasSeries) add(PickerEntry.Remove)
                if (canReset) add(PickerEntry.Reset(shared = false))
                if (canResetShared) add(PickerEntry.Reset(shared = true))
            }

            if (typed.isNotEmpty()) {
                val typedKey = seriesKey(typed)
                val exact = typedKey.isNotEmpty() && ranked.any { it.option.key == typedKey }
                val head = buildList {
                    if (!exact) add(PickerEntry.Create(typed))
                    if (ranked.isEmpty()) add(PickerEntry.Note(emptyNote))
                }
                if (head.isNotEmpty()) add(PickerSection(null, head))
                if (ranked.isNotEmpty()) {
                    add(PickerSection(allLabel, ranked.map { PickerEntry.Series(it) }))
                }
                // Leaving a series and restoring one are not answers to
                // a search, and a reader who wants them is one tap on
                // the clear button away from where they live.
                return@buildList
            }

            // At the top, not after the alphabet. Two hundred series is
            // two hundred rows, the letter rail cannot aim at a section
            // with no letter, and restoring an overridden series has no
            // other way in — re-tapping the chosen row only unpicks it.
            if (tail.isNotEmpty()) add(PickerSection(null, tail))

            val suggested = suggestedSeries(title, author, options)
            if (suggested.isNotEmpty()) {
                add(PickerSection(suggestedLabel, suggested.map { it.asEntry() }))
            }
            val recent = recentSeries(options).filterNot { option ->
                suggested.any { it.key == option.key }
            }
            if (recent.isNotEmpty()) {
                add(PickerSection(recentLabel, recent.map { it.asEntry() }))
            }
            options
                // Sorted on the same key it is filed under, or *The
                // Dark Tower* lands in D and then sits under Dune,
                // because its article counts for the order and not for
                // the letter.
                .sortedWith(
                    compareBy<SeriesPickOption>(
                        { if (seriesInitial(it.name) == OTHER_INITIAL) 1 else 0 },
                        { seriesInitial(it.name) },
                        { sortKey(it.name) },
                    ),
                )
                .groupBy { seriesInitial(it.name) }
                .forEach { (initial, group) ->
                    add(
                        PickerSection(
                            label = if (initial == OTHER_INITIAL) otherLabel else initial,
                            entries = group.map { it.asEntry() },
                        ),
                    )
                }
        }
    }
}

private fun SeriesPickOption.asEntry() = PickerEntry.Series(RankedSeries(this, emptyList()))

/**
 * Where each letter's header sits in the list, for the rail to aim at.
 *
 * Counted rather than guessed, because a header is only drawn for a
 * section that has one and the suggested and recent sections push
 * everything down by however many rows they happen to hold.
 */
private fun letterJumps(sections: List<PickerSection>): List<Pair<String, Int>> {
    // Below a screenful or two there is nothing to get lost in, and a
    // rail costs width the names need more.
    if (sections.sumOf { it.entries.size } < RAIL_THRESHOLD) return emptyList()
    val jumps = mutableListOf<Pair<String, Int>>()
    var index = 0
    sections.forEach { section ->
        if (section.label != null) {
            if (section.label.length == 1) jumps += section.label to index
            index++
        }
        index += section.entries.size
    }
    return jumps
}

/** The name, with what the search matched picked out of it. */
@Composable
private fun highlighted(name: String, matches: List<IntRange>): AnnotatedString {
    if (matches.isEmpty()) return AnnotatedString(name)
    val emphasis = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    return buildAnnotatedString {
        var at = 0
        matches.forEach { range ->
            val start = range.first.coerceIn(0, name.length)
            val end = (range.last + 1).coerceIn(start, name.length)
            if (start < at) return@forEach
            append(name.substring(at, start))
            withStyle(emphasis) { append(name.substring(start, end)) }
            at = end
        }
        append(name.substring(at))
    }
}

/** A run of rows under one landmark, or under none. */
private data class PickerSection(val label: String?, val entries: List<PickerEntry>)

/** How many series it takes before the letter rail earns its width. */
private const val RAIL_THRESHOLD = 25

/** One row of the picker. */
private sealed interface PickerEntry {
    val key: String

    data class Series(val ranked: RankedSeries) : PickerEntry {
        override val key: String get() = "series:${ranked.option.key}"
    }

    data class Create(val name: String) : PickerEntry {
        override val key: String get() = "create"
    }

    data object Remove : PickerEntry {
        override val key: String get() = "remove"
    }

    /**
     * Restoring a personal claim and restoring a shared one are
     * different acts against different layers, and only an admin can
     * do the second. They are told apart here rather than guessed at
     * from the book's state further down.
     */
    data class Reset(val shared: Boolean) : PickerEntry {
        override val key: String get() = if (shared) "reset-shared" else "reset"
    }

    data class Note(val text: String) : PickerEntry {
        override val key: String get() = "note"
    }
}
