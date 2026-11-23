package com.chmouel.liseur.vocabulary

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VocabularyLookupAction(
    repository: VocabularyRepository,
    lookup: VocabularyLookup,
    autoSave: Boolean,
    definition: String?,
    translationLanguage: String?,
) {
    val normalized = lookup.normalized ?: return
    val found by remember(lookup) { repository.dao.observeWord(normalized, lookup.language) }
        .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val eInk = LocalEInk.current
    var busy by remember(lookup) { mutableStateOf(false) }
    var savedId by remember(lookup) { mutableStateOf<Long?>(null) }
    var confirmRemove by remember(lookup) { mutableStateOf(false) }
    var handled by remember(lookup) { mutableStateOf(false) }

    fun save(existingOnly: Boolean = false) {
        if (busy) return
        busy = true
        if (!existingOnly) handled = true
        scope.launch {
            try {
                savedId = repository.collect(lookup, definition, translationLanguage, existingOnly = existingOnly)
                if (savedId != null) handled = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(autoSave, lookup) {
        if (!handled && (autoSave || lookup.occurrence != null)) save(existingOnly = !autoSave)
    }
    LaunchedEffect(savedId, definition, translationLanguage) {
        val id = savedId ?: return@LaunchedEffect
        try {
            repository.enrich(id, definition, translationLanguage)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
        }
    }

    val current = found
    val alreadySaved = current != null || savedId != null
    val actionLabel = stringResource(if (alreadySaved) R.string.vocabulary_remove_word else R.string.vocabulary_save)
    val onChipClick = { if (alreadySaved) confirmRemove = true else save() }
    val containerColor = when {
        eInk -> Color.Transparent
        alreadySaved -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        eInk -> MaterialTheme.colorScheme.onSurface
        alreadySaved -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        eInk && alreadySaved -> BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        eInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        alreadySaved -> null
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (busy) 0.45f else 1f)
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                selected = alreadySaved
                onClick(label = actionLabel) {
                    onChipClick()
                    true
                }
            }
            .clickable(enabled = !busy, onClick = onChipClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (alreadySaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(
                    if (alreadySaved) R.string.vocabulary_badge_saved else R.string.vocabulary_badge_save,
                ),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.vocabulary_confirm_delete, lookup.word)) },
            text = { Text(stringResource(R.string.vocabulary_delete_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    val id = current?.id ?: savedId
                    if (id != null) {
                        handled = true
                        busy = true
                        scope.launch {
                            try {
                                repository.deleteWord(id)
                                savedId = null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

}
