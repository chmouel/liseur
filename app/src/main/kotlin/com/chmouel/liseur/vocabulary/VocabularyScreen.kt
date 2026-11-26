package com.chmouel.liseur.vocabulary

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.insights.UsageEvent
import com.chmouel.liseur.ui.Segment
import com.chmouel.liseur.ui.SegmentedChoiceRow
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class VocabularyTab { REVIEW, WORDS, STATS }

private val statuses = listOf(
    R.string.vocabulary_all_words, R.string.vocabulary_new, R.string.vocabulary_learning, R.string.vocabulary_learned,
)
private val sorts = listOf(R.string.sort_added, R.string.vocabulary_alphabetical, R.string.vocabulary_status, R.string.vocabulary_frequency)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VocabularyScreen(
    repository: VocabularyRepository,
    onBack: () -> Unit,
    onOpenPassage: (VocabularyOccurrence) -> Unit,
    currentBook: VocabularyBook? = null,
    onOpenSettings: (() -> Unit)? = null,
    /** Opens on the review, and leaves the screen when the review closes. */
    reviewOnly: Boolean = false,
) {
    val context = LocalContext.current
    val preferences = context.container.appSettings
    val settings by preferences.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val newLimit = settings.vocabularyNewLimit
    val reviewLimit = settings.vocabularyReviewLimit
    val list = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var bookUrl by rememberSaveable { mutableStateOf<String?>(currentBook?.bookUrl) }
    var status by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var grouped by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(if (reviewOnly) VocabularyTab.REVIEW else VocabularyTab.WORDS) }
    var sessionCount by rememberSaveable { mutableIntStateOf(0) }
    var reportedOpening by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!reportedOpening) {
            context.container.usageReporting.capture(UsageEvent.VOCABULARY_OPENED)
            reportedOpening = true
        }
    }
    val reviewBook = currentBook?.bookUrl ?: bookUrl
    val queue by remember(repository, reviewBook, newLimit, reviewLimit) {
        repository.reviewQueue(reviewBook, newLimit, reviewLimit)
    }.collectAsStateWithLifecycle(initialValue = null)
    val stats by remember(repository, reviewBook) {
        repository.dao.statistics(reviewBook)
    }.collectAsStateWithLifecycle(initialValue = null)
    val selectedId = selected
    LaunchedEffect(search, bookUrl, status, sort, grouped) { list.scrollToItem(0) }
    LaunchedEffect(searching) { if (searching) list.scrollToItem(0) }
    val back = {
        when {
            selected != null -> selected = null
            tab == VocabularyTab.REVIEW -> if (reviewOnly) onBack() else tab = VocabularyTab.WORDS
            tab == VocabularyTab.STATS -> tab = VocabularyTab.WORDS
            searching -> {
                searching = false
                search = ""
            }
            else -> onBack()
        }
    }
    BackHandler(selected != null || tab != VocabularyTab.WORDS || searching, onBack = back)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.vocabulary_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (tab == VocabularyTab.WORDS && selected == null) {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) search = ""
                        }) {
                            Icon(
                                if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                stringResource(if (searching) R.string.clear_search else R.string.vocabulary_search),
                            )
                        }
                        if (onOpenSettings != null) VocabularyMenu(repository, onOpenSettings)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            SegmentedChoiceRow(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                VocabularyTab.entries.forEachIndexed { index, choice ->
                    Segment(
                        index,
                        VocabularyTab.entries.size,
                        selected = tab == choice,
                        onClick = { tab = choice; selected = null },
                        icon = {},
                    ) {
                        BadgedBox(badge = {
                            val due = queue?.dueCount ?: 0
                            if (choice == VocabularyTab.REVIEW && due > 0) Badge { Text(due.toString()) }
                        }) {
                            Row(Modifier.padding(end = if (choice == VocabularyTab.REVIEW) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (choice == VocabularyTab.REVIEW) Icon(Icons.Outlined.Style, null, Modifier.size(18.dp))
                                Text(stringResource(when (choice) {
                                    VocabularyTab.REVIEW -> R.string.vocabulary_tab_review
                                    VocabularyTab.WORDS -> R.string.vocabulary_tab_words
                                    VocabularyTab.STATS -> R.string.vocabulary_tab_stats
                                }))
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val content = Modifier.widthIn(max = 680.dp).fillMaxSize()
                if (tab == VocabularyTab.REVIEW) {
                    queue?.let {
                        VocabularyReviewScreen(
                            repository, it, reviewBook, newLimit, reviewLimit, sessionCount,
                            { sessionCount++ }, { sessionCount = (sessionCount - 1).coerceAtLeast(0) },
                            onOpenPassage, back, content,
                        )
                    }
                } else if (tab == VocabularyTab.STATS) {
                    VocabularyStatsScreen(repository, reviewBook, stats, content)
                } else if (selectedId != null) {
                    VocabularyDetail(repository, selectedId, currentBook?.bookUrl, onOpenPassage, { selected = null }, content)
                } else {
                    val showGroups = grouped && bookUrl == null
                    val words by remember(search, bookUrl, status, sort, showGroups) {
                        repository.dao.words(normalizeVocabularySearch(search.trim()), bookUrl, status, sort, showGroups)
                    }.collectAsStateWithLifecycle(initialValue = null)
                    val books by remember(repository) { repository.dao.books() }.collectAsStateWithLifecycle(emptyList())
                    LazyColumn(content, state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            Column(
                                Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                currentBook?.let { Text(it.bookTitle, style = MaterialTheme.typography.titleMedium) }
                                stats?.takeIf { it.total > 0 }?.let {
                                    Text(
                                        stringResource(R.string.vocabulary_counts, it.total, it.newWords, it.total - it.newWords - it.learned, it.learned),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (searching) {
                                    OutlinedTextField(
                                        value = search,
                                        onValueChange = { search = it },
                                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                        label = { Text(stringResource(R.string.vocabulary_search)) },
                                        singleLine = true,
                                    )
                                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (currentBook == null) {
                                        VocabularyBookFilter(books, bookUrl) { bookUrl = it }
                                        if (bookUrl == null && books.isNotEmpty()) {
                                            FilterChip(grouped, { grouped = !grouped }, label = { Text(stringResource(R.string.vocabulary_group_books)) })
                                        }
                                    }
                                    VocabularyFilter(
                                        stringResource(statuses[status]), status != 0,
                                        statuses.map { stringResource(it) },
                                    ) { status = it }
                                    VocabularyFilter(
                                        stringResource(sorts[sort]), sort != 0,
                                        sorts.map { stringResource(it) },
                                    ) { sort = it }
                                }
                            }
                        }
                        val rows = words.orEmpty()
                        if (words != null && rows.isEmpty()) item {
                            Text(
                                stringResource(
                                    if (search.isBlank() && status == 0 && stats?.total == 0) R.string.vocabulary_empty
                                    else R.string.vocabulary_no_matches,
                                ),
                                Modifier.padding(24.dp),
                            )
                        }
                        items(rows.size, key = { index -> "${rows[index].wordId}:${rows[index].groupUrl.orEmpty()}" }) { index ->
                            val item = rows[index]
                            if (showGroups && (index == 0 || item.groupUrl != rows[index - 1].groupUrl)) {
                                Text(
                                    item.groupTitle?.ifBlank { null } ?: stringResource(R.string.vocabulary_no_book),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            ListItem(
                                headlineContent = { Text(item.word) },
                                supportingContent = {
                                    Column {
                                        item.definition?.let {
                                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text(stringResource(statuses[when {
                                            item.repetitions == 0 -> 1
                                            item.streak < 6 -> 2
                                            else -> 3
                                        }]), style = MaterialTheme.typography.labelMedium)
                                    }
                                },
                                modifier = Modifier.clickable { selected = item.wordId },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabularyBookFilter(books: List<VocabularyBook>, selected: String?, onSelect: (String?) -> Unit) {
    val all = stringResource(R.string.vocabulary_all_books)
    var open by remember { mutableStateOf(false) }
    FilterChip(
        selected = selected != null,
        onClick = { open = true },
        label = { Text(books.firstOrNull { it.bookUrl == selected }?.bookTitle ?: all, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(all) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem(headlineContent = { Text(all) }, modifier = Modifier.clickable {
                            open = false
                            onSelect(null)
                        })
                    }
                    items(books, key = { it.bookUrl }) { book ->
                        ListItem(
                            headlineContent = { Text(book.bookTitle) },
                            modifier = Modifier.clickable {
                                open = false
                                onSelect(book.bookUrl)
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun VocabularyFilter(label: String, selected: Boolean, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { expanded = false; onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun VocabularyDetail(
    repository: VocabularyRepository,
    id: Long,
    bookUrl: String?,
    onOpenPassage: (VocabularyOccurrence) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    val word by remember(id) { repository.dao.observeWord(id) }.collectAsStateWithLifecycle(initialValue = null)
    val occurrences by remember(id) { repository.dao.occurrences(id) }.collectAsStateWithLifecycle(emptyList())
    val item = word ?: return
    val scope = rememberCoroutineScope()
    var editWord by rememberSaveable(id) { mutableStateOf(false) }
    var deleteWord by rememberSaveable(id) { mutableStateOf(false) }
    var editPassage by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(item.word, style = MaterialTheme.typography.headlineMedium)
            if (item.language != "und") {
                val language = Locale.forLanguageTag(item.language).displayName
                Text(language, style = MaterialTheme.typography.labelLarge)
            }
        }
        item { Text(item.definition ?: stringResource(R.string.vocabulary_no_definition)) }
        if (item.notes.isNotBlank()) item { Text(item.notes) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editWord = true }) { Text(stringResource(R.string.annotation_note_edit)) }
                TextButton(onClick = { deleteWord = true }) { Text(stringResource(R.string.delete)) }
            }
        }
        items(occurrences.filter { bookUrl == null || it.bookUrl == bookUrl }, key = { it.id }) { source ->
            HorizontalDivider()
            Text(source.bookTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            source.sentence?.takeIf { it.isNotBlank() }?.let { Text(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.locator != null && source.bookUrl.isNotEmpty()) {
                    TextButton(onClick = { onOpenPassage(source) }) { Text(stringResource(R.string.vocabulary_open_passage)) }
                }
                TextButton(onClick = { editPassage = source.id }) { Text(stringResource(R.string.vocabulary_edit_context)) }
            }
        }
    }
    if (editWord) {
        VocabularyWordEditor(repository, item) { editWord = false }
    }
    occurrences.firstOrNull { it.id == editPassage }?.let { source ->
        var sentence by rememberSaveable(source.id) { mutableStateOf(source.sentence.orEmpty()) }
        VocabularyEditDialog(stringResource(R.string.vocabulary_edit_context), { editPassage = null }, {
            scope.launch { repository.editSentence(source.id, sentence); editPassage = null }
        }) {
            OutlinedTextField(sentence, { sentence = it.take(4000) }, maxLines = 6, label = { Text(stringResource(R.string.vocabulary_sentence)) })
        }
    }
    if (deleteWord) {
        VocabularyWordDeleteDialog(repository, item, { deleteWord = false }, onDelete)
    }
}

@Composable
internal fun VocabularyWordDeleteDialog(
    repository: VocabularyRepository,
    word: VocabularyWord,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vocabulary_confirm_delete, word.word)) },
        text = { Text(stringResource(R.string.vocabulary_delete_detail)) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        repository.deleteWord(word.id)
                        onDeleted()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun VocabularyEditDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
