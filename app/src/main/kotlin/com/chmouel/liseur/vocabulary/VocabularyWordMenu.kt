package com.chmouel.liseur.vocabulary

import android.content.ClipData
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.reader.annotations.shareText
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.launch

internal fun Modifier.vocabularySelectionPress(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        // Reserve the press for text selection, leaving movement available to the deck.
        awaitFirstDown(requireUnconsumed = false).consume()
    }
}

@Composable
internal fun VocabularyWordMenu(
    repository: VocabularyRepository,
    id: Long,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenPassage: (VocabularyOccurrence) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val word by remember(repository, id) { repository.dao.observeWord(id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val sources by remember(repository, id) { repository.dao.occurrences(id) }
        .collectAsStateWithLifecycle(emptyList())
    word?.let {
        VocabularyWordMenu(
            repository, it, sources.firstOrNull { source -> source.locator != null && source.bookUrl.isNotEmpty() },
            expanded, onDismiss, onOpenPassage, onEdit,
        )
    }
}

@Composable
internal fun VocabularyWordMenu(
    repository: VocabularyRepository,
    word: VocabularyWord,
    passage: VocabularyOccurrence?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenPassage: (VocabularyOccurrence) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var removing by remember(word.id) { mutableStateOf(false) }
    val removeColor = if (LocalEInk.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    DropdownMenu(expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vocabulary_copy_word)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
            enabled = word.word.isNotBlank(),
            onClick = {
                onDismiss()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(word.word, word.word))) }
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vocabulary_share_word)) },
            leadingIcon = { Icon(Icons.Outlined.Share, null) },
            enabled = word.word.isNotBlank(),
            onClick = {
                onDismiss()
                context.shareText(word.word, null)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vocabulary_edit_card)) },
            leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
            onClick = { onDismiss(); onEdit(word.id) },
        )
        if (passage != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vocabulary_open_passage)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) },
                onClick = { onDismiss(); onOpenPassage(passage) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vocabulary_remove_word), color = removeColor) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = removeColor) },
            onClick = { onDismiss(); removing = true },
        )
    }
    if (removing) VocabularyWordDeleteDialog(repository, word, { removing = false }, { removing = false })
}
