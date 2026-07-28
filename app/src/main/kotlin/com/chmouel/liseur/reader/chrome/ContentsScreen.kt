package com.chmouel.liseur.reader.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.annotations.HighlightTint
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/** One line of the contents, with how deeply it is nested. */
data class ContentsEntry(val depth: Int, val link: Link)

/** The four ways of getting around a book. */
private enum class ContentsTab(val labelRes: Int) {
    CONTENTS(R.string.reader_contents),
    BOOKMARKS(R.string.reader_bookmarks),
    HIGHLIGHTS(R.string.reader_highlights),
    NOTES(R.string.reader_notes),
}

/**
 * Everything you can use to move around a book, on its own screen.
 *
 * A book's contents can run to hundreds of lines and is the thing people
 * jump around in, so it gets the full height of the display rather than a
 * sheet that has to be fought to scroll. Bookmarks, highlights and notes
 * sit alongside as tabs, because in practice they are used for the same
 * thing: getting back to a particular place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsScreen(
    publication: Publication,
    theme: ReaderTheme,
    currentHref: String?,
    annotations: List<BookAnnotation>,
    onEntrySelected: (Link) -> Unit,
    onAnnotationSelected: (BookAnnotation) -> Unit,
    onAnnotationDeleted: (BookAnnotation) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ContentsTab.CONTENTS) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = theme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.reader_navigate)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    },
                    actions = {
                        if (annotations.isNotEmpty() && tab != ContentsTab.CONTENTS) {
                            IconButton(onClick = onExport) {
                                Icon(
                                    Icons.Outlined.Share,
                                    contentDescription =
                                    stringResource(R.string.annotation_export),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = theme.background,
                        titleContentColor = theme.foreground,
                        navigationIconContentColor = theme.foreground,
                        actionIconContentColor = theme.foreground,
                    ),
                )
                TabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = theme.background,
                    contentColor = theme.foreground,
                ) {
                    ContentsTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            selectedContentColor = theme.foreground,
                            unselectedContentColor = theme.foreground.copy(alpha = 0.6f),
                            text = {
                                Text(
                                    text = stringResource(entry.labelRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                ContentsTab.CONTENTS -> ContentsList(
                    publication = publication,
                    theme = theme,
                    currentHref = currentHref,
                    onEntrySelected = onEntrySelected,
                )

                ContentsTab.BOOKMARKS -> AnnotationList(
                    annotations = annotations.filter {
                        it.kind == AnnotationKind.BOOKMARK.name
                    },
                    theme = theme,
                    emptyRes = R.string.reader_no_bookmarks,
                    onSelected = onAnnotationSelected,
                    onDeleted = onAnnotationDeleted,
                )

                ContentsTab.HIGHLIGHTS -> AnnotationList(
                    annotations = annotations.filter {
                        it.kind != AnnotationKind.BOOKMARK.name
                    },
                    theme = theme,
                    emptyRes = R.string.reader_no_highlights,
                    onSelected = onAnnotationSelected,
                    onDeleted = onAnnotationDeleted,
                )

                ContentsTab.NOTES -> AnnotationList(
                    annotations = annotations.filter { !it.note.isNullOrBlank() },
                    theme = theme,
                    emptyRes = R.string.reader_no_notes,
                    onSelected = onAnnotationSelected,
                    onDeleted = onAnnotationDeleted,
                )
            }
        }
    }
}

@Composable
private fun ContentsList(
    publication: Publication,
    theme: ReaderTheme,
    currentHref: String?,
    onEntrySelected: (Link) -> Unit,
) {
    val entries = remember(publication) { publication.tableOfContents.flatten() }
    // Contents entries point at an anchor inside a chapter file, while the
    // reader usually only knows which file it is in. Match the anchor when we
    // have one, otherwise fall back to the first entry of that file so the
    // chapter itself is highlighted rather than its last sub-section.
    val currentIndex = remember(entries, currentHref) {
        val here = currentHref ?: return@remember -1
        val exact = entries.indexOfFirst { it.link.href.toString() == here }
        if (exact >= 0) return@remember exact
        val file = here.substringBefore('#')
        entries.indexOfFirst { it.link.href.toString().substringBefore('#') == file }
    }
    val listState = rememberLazyListState()

    // Land on the chapter being read rather than at the top of a long book.
    LaunchedEffect(currentIndex) {
        if (currentIndex > 2) listState.scrollToItem(currentIndex - 2)
    }

    if (entries.isEmpty()) {
        EmptyMessage(theme, R.string.reader_no_contents)
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(
            entries,
            key = { index, entry -> "$index:${entry.link.href}" },
        ) { index, entry ->
            ContentsRow(
                entry = entry,
                theme = theme,
                current = index == currentIndex,
                onClick = { onEntrySelected(entry.link) },
            )
            HorizontalDivider(color = theme.foreground.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun AnnotationList(
    annotations: List<BookAnnotation>,
    theme: ReaderTheme,
    emptyRes: Int,
    onSelected: (BookAnnotation) -> Unit,
    onDeleted: (BookAnnotation) -> Unit,
) {
    if (annotations.isEmpty()) {
        EmptyMessage(theme, emptyRes)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(annotations, key = { it.id }) { annotation ->
            AnnotationRow(
                annotation = annotation,
                theme = theme,
                onClick = { onSelected(annotation) },
                onDelete = { onDeleted(annotation) },
            )
            HorizontalDivider(color = theme.foreground.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun AnnotationRow(
    annotation: BookAnnotation,
    theme: ReaderTheme,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (annotation.kind != AnnotationKind.BOOKMARK.name) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(HighlightTint.fromName(annotation.tint).color),
            )
        }
        Column(Modifier.weight(1f)) {
            val where = listOfNotNull(
                annotation.chapter?.takeIf { it.isNotBlank() },
                annotation.position?.let { stringResource(R.string.reader_page_short, it) },
            ).joinToString(" · ")
            if (where.isNotEmpty()) {
                Text(
                    text = where,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.foreground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            annotation.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.foreground,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            annotation.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = theme.foreground.copy(alpha = 0.75f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.annotation_delete),
                tint = theme.foreground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun EmptyMessage(theme: ReaderTheme, textRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.foreground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun ContentsRow(
    entry: ContentsEntry,
    theme: ReaderTheme,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = 20.dp + (entry.depth * 16).dp,
                end = 20.dp,
                top = 14.dp,
                bottom = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A bar rather than a tick: it reads as "you are here" without
        // stealing attention from the titles around it.
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(50))
                .background(if (current) theme.foreground else Color.Transparent),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.link.title.orEmpty().ifBlank {
                    stringResource(R.string.reader_untitled_section)
                },
                style = if (entry.depth == 0) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = theme.foreground.copy(alpha = if (entry.depth == 0) 1f else 0.82f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun List<Link>.flatten(depth: Int = 0): List<ContentsEntry> =
    flatMap { link ->
        listOf(ContentsEntry(depth, link)) + link.children.flatten(depth + 1)
    }
