package com.chmouel.liseur.reader.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.reader.ReaderViewModel.SearchState
import org.readium.r2.shared.publication.Locator

/**
 * Searching inside the book.
 *
 * Hits are shown with the sentence they sit in and the searched words
 * picked out in bold, because a bare list of chapter names is no help in
 * deciding which of forty matches is the one you meant. Results appear as
 * the search works through the book rather than all at once at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: SearchState,
    theme: ReaderTheme,
    initialQuery: String,
    onSearch: (String) -> Unit,
    onHitSelected: (Locator) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (initialQuery.isBlank()) focus.requestFocus() else onSearch(initialQuery)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = theme.background,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = theme.foreground,
                                unfocusedTextColor = theme.foreground,
                            ),
                        )
                    },
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
                        navigationIconContentColor = theme.foreground,
                    ),
                )
                if (state is SearchState.Running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        val hits = state.hits()
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                hits.isNotEmpty() -> HitList(hits, theme, onHitSelected)

                state is SearchState.Done ->
                    Message(theme, stringResource(R.string.search_no_results, state.query))

                state is SearchState.Failure ->
                    Message(theme, stringResource(R.string.search_failed))

                state is SearchState.Running -> Unit

                else -> Message(theme, stringResource(R.string.search_prompt))
            }
        }
    }
}

@Composable
private fun HitList(
    hits: List<Locator>,
    theme: ReaderTheme,
    onHitSelected: (Locator) -> Unit,
) {
    Column {
        Text(
            text = pluralStringResource(R.plurals.search_result_count, hits.size, hits.size),
            style = MaterialTheme.typography.labelMedium,
            color = theme.foreground.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits, key = { it.hitKey() }) { hit ->
                SearchHitRow(hit, theme) { onHitSelected(hit) }
                HorizontalDivider(color = theme.foreground.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: Locator, theme: ReaderTheme, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        hit.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = theme.foreground.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = buildAnnotatedString {
                append(hit.text.before.orEmpty().tail(BEFORE_CHARS))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(hit.text.highlight.orEmpty())
                }
                append(hit.text.after.orEmpty().head(AFTER_CHARS))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = theme.foreground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Message(theme: ReaderTheme, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.foreground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * Readium hands back a generous slab of context on both sides. Left whole
 * it pushes the match itself off the bottom of a three-line snippet, which
 * defeats the point, so each side is cut back to roughly a line.
 */
private const val BEFORE_CHARS = 40
private const val AFTER_CHARS = 90

private fun String.tail(max: Int): String {
    val text = trimEnd('\n').replace('\n', ' ')
    if (text.length <= max) return text.trimStart()
    return "\u2026" + text.takeLast(max).substringAfter(' ', text.takeLast(max))
}

private fun String.head(max: Int): String {
    val text = trimStart('\n').replace('\n', ' ')
    if (text.length <= max) return text.trimEnd()
    return text.take(max).substringBeforeLast(' ') + "\u2026"
}

private fun Locator.hitKey(): String =
    "$href|${locations.progression}|${locations.position}|${text.highlight}|${text.before?.length}"

private fun SearchState.hits(): List<Locator> = when (this) {
    is SearchState.Running -> hits
    is SearchState.Done -> hits
    else -> emptyList()
}
