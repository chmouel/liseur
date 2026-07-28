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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

/** One line of the contents, with how deeply it is nested. */
data class ContentsEntry(val depth: Int, val link: Link)

/**
 * The whole table of contents, on its own screen.
 *
 * A book's contents can run to hundreds of lines and is the thing people
 * jump around in, so it gets the full height of the display rather than a
 * sheet that has to be fought to scroll. The chapter being read is marked
 * and scrolled to, so opening the contents always starts from where you
 * are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsScreen(
    publication: Publication,
    theme: ReaderTheme,
    currentHref: String?,
    onEntrySelected: (Link) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = theme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reader_contents)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.foreground,
                    navigationIconContentColor = theme.foreground,
                ),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.reader_no_contents),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.foreground.copy(alpha = 0.7f),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
