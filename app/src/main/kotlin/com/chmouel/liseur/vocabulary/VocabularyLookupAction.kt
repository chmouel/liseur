package com.chmouel.liseur.vocabulary

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
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
    AssistChip(
        enabled = !busy,
        onClick = { if (alreadySaved) confirmRemove = true else save() },
        label = {
            Text(stringResource(if (alreadySaved) R.string.vocabulary_saved_remove else R.string.vocabulary_save))
        },
    )
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
