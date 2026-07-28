package com.chmouel.liseur.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.compose.AndroidFragment
import com.chmouel.liseur.R
import kotlinx.coroutines.flow.drop
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    publication: Publication,
    onLocatorChanged: (Locator) -> Unit,
    onBack: () -> Unit,
) {
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var showToc by remember { mutableStateOf(false) }

    LaunchedEffect(navigator) {
        // The navigator replays its current locator on subscription; only
        // subsequent changes need persisting.
        navigator?.currentLocator?.drop(1)?.collect(onLocatorChanged)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AndroidFragment<EpubNavigatorFragment>(
            modifier = Modifier.fillMaxSize(),
        ) { fragment ->
            navigator = fragment
        }

        TopAppBar(
            title = {
                Text(
                    text = publication.metadata.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reader_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = { showToc = true }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.List,
                        contentDescription = stringResource(R.string.reader_contents),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ),
        )
    }

    if (showToc) {
        TableOfContentsSheet(
            publication = publication,
            onDismiss = { showToc = false },
            onEntrySelected = { link ->
                showToc = false
                publication.locatorFromLink(link)?.let { navigator?.go(it, animated = false) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableOfContentsSheet(
    publication: Publication,
    onDismiss: () -> Unit,
    onEntrySelected: (Link) -> Unit,
) {
    val entries = remember(publication) { publication.tableOfContents.flattenWithDepth() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.reader_contents),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(entries) { (depth, link) ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = link.title.orEmpty(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEntrySelected(link) }
                        .padding(start = (depth * 16).dp),
                )
            }
        }
    }
}

private fun List<Link>.flattenWithDepth(depth: Int = 0): List<Pair<Int, Link>> =
    flatMap { link -> listOf(depth to link) + link.children.flattenWithDepth(depth + 1) }

@Composable
fun ReaderLoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ReaderErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.reader_open_failed),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.reader_back),
            )
        }
    }
}
