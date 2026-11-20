package com.chmouel.liseur.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.AnnotationKind
import com.chmouel.liseur.data.db.LibraryNote
import com.chmouel.liseur.data.settings.ReaderTheme
import com.chmouel.liseur.domain.sanitizeAuthor
import com.chmouel.liseur.domain.sanitizeTitle
import com.chmouel.liseur.reader.annotations.HighlightPalette
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.reader.annotations.NoteEditor
import com.chmouel.liseur.reader.annotations.NoteMarkdown
import com.chmouel.liseur.reader.annotations.citationText
import com.chmouel.liseur.reader.annotations.copySelection
import com.chmouel.liseur.reader.annotations.noteAnnotated
import com.chmouel.liseur.reader.annotations.shareText

private enum class NotesFilter { ALL, HIGHLIGHTS, NOTES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryNotesScreen(
    notes: List<LibraryNote>?,
    onBack: () -> Unit,
    onSave: (LibraryNote, String, HighlightTint?) -> Unit,
    dark: Boolean,
    palette: HighlightPalette,
    onOpen: (LibraryNote) -> Unit,
    onDelete: (LibraryNote) -> Unit = {},
) {
    val searchFocus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var recent by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(NotesFilter.ALL) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSnapshot by remember { mutableStateOf<LibraryNote?>(null) }
    val editing = notes?.firstOrNull { it.id == editingId }
        ?: editingSnapshot?.takeIf { it.id == editingId }
    var overflowOpen by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isNotBlank()) collapsed = emptyList()
    }

    val filtered = remember(notes, query, filter) {
        val trimmed = query.trim()
        notes.orEmpty().filter { note ->
            val matchesFilter = when (filter) {
                NotesFilter.ALL -> true
                NotesFilter.HIGHLIGHTS -> note.kind != AnnotationKind.BOOK_NOTE.name
                NotesFilter.NOTES -> !note.body.isNullOrBlank()
            }
            matchesFilter && (trimmed.isEmpty() || listOf(note.title, note.author, note.title?.let(::sanitizeTitle), sanitizeAuthor(note.author), note.chapter, note.passage, note.body, note.body?.let { NoteMarkdown.render(it).text })
                .any { it?.contains(trimmed, ignoreCase = true) == true })
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { it.bookId }.entries.toList()
    }
    val allBookIds = remember(grouped) { grouped.map { it.key } }
    val allCollapsed = remember(allBookIds, collapsed) {
        allBookIds.isNotEmpty() && allBookIds.all { it in collapsed }
    }

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.reader_notes),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 25.sp,
                                lineHeight = 28.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.library_notes_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (searching) {
                            searching = false
                            query = ""
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searching) R.string.clear_search else R.string.library_notes_search),
                        )
                    }
                    if (!recent && allBookIds.size > 1) {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options))
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (allCollapsed) {
                                                    R.string.library_notes_expand_all
                                                } else {
                                                    R.string.library_notes_collapse_all
                                                },
                                            ),
                                        )
                                    },
                                    onClick = {
                                        overflowOpen = false
                                        collapsed = if (allCollapsed) emptyList() else allBookIds
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.library_notes_search)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .focusRequester(searchFocus),
                    )
                    LaunchedEffect(Unit) { searchFocus.requestFocus() }
                }

                NotesModeToggle(
                    recent = recent,
                    onSelectRecent = { recent = it },
                    modifier = Modifier.padding(top = 4.dp),
                )

                NotesFilterRow(
                    selected = filter,
                    totalCount = filtered.size,
                    onSelect = { filter = it },
                )
            }

            Spacer(Modifier.height(6.dp))

            if (notes != null && filtered.isEmpty()) {
                Box(
                    Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (notes.isEmpty()) {
                            stringResource(R.string.library_notes_empty)
                        } else {
                            stringResource(R.string.library_notes_no_results)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val sectionRuleColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            val entryRuleColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

            LazyColumn(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                if (recent) {
                    itemsIndexed(filtered, key = { _, note -> "note:${note.id}" }) { index, note ->
                        RecentBookLabel(note)
                        NoteRow(
                            note = note,
                            indented = false,
                            onEdit = { editingSnapshot = note; editingId = note.id },
                            onOpen = { onOpen(note) },
                            onDelete = { onDelete(note) },
                        )
                        if (index < filtered.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = sectionRuleColor)
                        }
                    }
                } else {
                    grouped.forEachIndexed { groupIndex, (bookId, entries) ->
                        val expanded = bookId !in collapsed
                        item(key = "book:$bookId") {
                            BookHeading(
                                note = entries.first(),
                                expanded = expanded,
                                count = entries.size,
                                onToggle = {
                                    collapsed = if (bookId in collapsed) {
                                        collapsed - bookId
                                    } else {
                                        collapsed + bookId
                                    }
                                },
                            )
                        }
                        if (expanded) {
                            itemsIndexed(
                                items = entries,
                                key = { _, note -> "note:${note.id}" },
                            ) { entryIndex, note ->
                                NoteRow(
                                    note = note,
                                    indented = true,
                                    onEdit = { editingSnapshot = note; editingId = note.id },
                                    onOpen = { onOpen(note) },
                                    onDelete = { onDelete(note) },
                                )
                                if (entryIndex < entries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 15.dp),
                                        thickness = 0.5.dp,
                                        color = entryRuleColor,
                                    )
                                }
                            }
                        }
                        if (groupIndex < grouped.lastIndex) {
                            item(key = "divider:$bookId") {
                                HorizontalDivider(thickness = 0.5.dp, color = sectionRuleColor)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { note ->
        val bookNote = note.kind == AnnotationKind.BOOK_NOTE.name
        NoteEditor(
            passage = note.passage,
            initialNote = note.body.orEmpty(),
            initialTint = if (bookNote) null else note.tint?.let(HighlightTint::fromName) ?: HighlightTint.DEFAULT,
            theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT,
            palette = palette,
            titleRes = if (bookNote) R.string.annotation_book_note_title else R.string.annotation_note_title,
            hintRes = if (bookNote) R.string.annotation_book_note_hint else R.string.annotation_note_hint,
            clearRes = if (bookNote) R.string.annotation_note_delete else R.string.annotation_note_remove,
            canClear = !note.body.isNullOrBlank(),
            onSave = { body, tint -> onSave(note, body, tint); editingId = null },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun NotesModeToggle(
    recent: Boolean,
    onSelectRecent: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, borderColor), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment(
            selected = !recent,
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            label = stringResource(R.string.library_notes_by_book),
            onClick = { onSelectRecent(false) },
            modifier = Modifier.weight(1f),
        )
        Spacer(
            Modifier
                .width(1.dp)
                .fillMaxHeight(0.55f)
                .background(borderColor),
        )
        ModeSegment(
            selected = recent,
            icon = Icons.Outlined.Schedule,
            label = stringResource(R.string.sort_recent),
            onClick = { onSelectRecent(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeSegment(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(container)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesFilterRow(
    selected: NotesFilter,
    totalCount: Int,
    onSelect: (NotesFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterPill(
                selected = selected == NotesFilter.ALL,
                label = stringResource(R.string.library_notes_all),
                icon = null,
                onClick = { onSelect(NotesFilter.ALL) },
            )
            FilterPill(
                selected = selected == NotesFilter.HIGHLIGHTS,
                label = stringResource(R.string.reader_highlights),
                icon = Icons.Outlined.FormatQuote,
                onClick = { onSelect(NotesFilter.HIGHLIGHTS) },
            )
            FilterPill(
                selected = selected == NotesFilter.NOTES,
                label = stringResource(R.string.reader_notes),
                icon = Icons.Outlined.Description,
                onClick = { onSelect(NotesFilter.NOTES) },
            )
        }
        Text(
            text = pluralStringResource(R.plurals.library_notes_count, totalCount, totalCount),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilterPill(
    selected: Boolean,
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) {
        null
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
    }
    Surface(
        selected = selected,
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BookHeading(
    note: LibraryNote,
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(
            Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = note.title?.let(::sanitizeTitle) ?: stringResource(R.string.library_notes_missing_book),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 23.sp,
                    lineHeight = 27.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            sanitizeAuthor(note.author)?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = pluralStringResource(R.plurals.library_notes_count, count, count),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                maxLines = 1,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_section_expanded else R.string.settings_section_collapsed,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RecentBookLabel(note: LibraryNote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = note.title?.let(::sanitizeTitle) ?: stringResource(R.string.library_notes_missing_book),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 21.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        sanitizeAuthor(note.author)?.let { author ->
            Text(
                text = "· $author",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: LibraryNote,
    indented: Boolean,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val isBookNote = note.kind == AnnotationKind.BOOK_NOTE.name
    val canOpenInBook = !isBookNote && note.locatorJson.isNotBlank()
    val passageText = note.passage?.trim()?.takeIf { it.isNotEmpty() }
    val bodyText = note.body?.trim()?.takeIf { it.isNotEmpty() }
    val serifFamily = MaterialTheme.typography.titleLarge.fontFamily

    val primaryAction = {
        if (canOpenInBook) {
            onOpen()
        } else {
            onEdit()
        }
    }

    val shareOrCopyText = remember(passageText, bodyText, note.title, note.author) {
        val content = listOfNotNull(passageText, bodyText).joinToString("\n\n")
        citationText(
            text = content,
            title = note.title?.let(::sanitizeTitle),
            authors = listOfNotNull(sanitizeAuthor(note.author)),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = primaryAction,
                onLongClick = { menu = true },
            )
            .padding(
                start = if (indented) 15.dp else 0.dp,
                top = 10.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NoteSectionLabel(note = note, isBookNote = isBookNote)

            passageText?.let { excerpt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Spacer(
                        Modifier
                            .fillMaxHeight()
                            .width(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Text(
                        text = excerpt,
                        modifier = Modifier.padding(start = 11.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = serifFamily,
                            fontStyle = FontStyle.Italic,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            bodyText?.let { body ->
                val quiet = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = remember(body, quiet) { noteAnnotated(body, quiet) },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = if (!isBookNote) {
                        Modifier.clickable(onClick = onEdit)
                    } else {
                        Modifier
                    },
                )
            }
        }

        Box {
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canOpenInBook) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_notes_open)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                        onClick = { menu = false; onOpen() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.annotation_note_edit)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = { menu = false; onEdit() },
                )
                if (shareOrCopyText.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.selection_copy)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            menu = false
                            context.copySelection(shareOrCopyText)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.annotation_share)) },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = {
                            menu = false
                            context.shareText(shareOrCopyText, note.title?.let(::sanitizeTitle))
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.annotation_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun NoteSectionLabel(
    note: LibraryNote,
    isBookNote: Boolean,
) {
    val accent = MaterialTheme.colorScheme.primary
    val chapter = note.chapter?.trim()?.takeIf { it.isNotEmpty() }
    val kindLabel = stringResource(
        if (isBookNote) R.string.annotation_book_note_title else R.string.annotation_highlight,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (isBookNote) Icons.Outlined.Description else Icons.Outlined.FormatQuote,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        val text = if (!isBookNote && chapter != null) {
            "$chapter · $kindLabel"
        } else {
            kindLabel
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
            ),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
