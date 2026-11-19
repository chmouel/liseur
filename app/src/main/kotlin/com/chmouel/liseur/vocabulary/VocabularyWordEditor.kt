package com.chmouel.liseur.vocabulary

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LocalEInk
import com.chmouel.liseur.ui.theme.SystemBarIcons
import com.chmouel.liseur.ui.theme.isDarkSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VocabularyWordEditor(repository: VocabularyRepository, id: Long, onDismiss: () -> Unit) {
    val word by remember(repository, id) { repository.dao.observeWord(id) }
        .collectAsStateWithLifecycle(initialValue = null)
    word?.let { VocabularyWordEditor(repository, it, onDismiss) }
}

@Composable
internal fun VocabularyWordEditor(repository: VocabularyRepository, word: VocabularyWord, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val quiet = if (LocalEInk.current) scheme.onSurface else scheme.onSurfaceVariant
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialMeaning = rememberSaveable(word.id) { word.definition.orEmpty() }
    val initialNotes = rememberSaveable(word.id) { word.notes }
    var meaning by rememberSaveable(word.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialMeaning, TextRange(initialMeaning.length)))
    }
    var notes by rememberSaveable(word.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialNotes, TextRange(initialNotes.length)))
    }
    var confirmingDiscard by rememberSaveable(word.id) { mutableStateOf(false) }
    var focusNotes by rememberSaveable(word.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val meaningFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    val dirty = meaning.text != initialMeaning || notes.text != initialNotes
    val close = {
        if (!busy) {
            if (dirty) confirmingDiscard = true else onDismiss()
        }
    }
    val keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true)

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(word.id) {
            if (focusNotes) notesFocus.requestFocus() else meaningFocus.requestFocus()
            keyboard?.show()
        }
        Surface(Modifier.fillMaxSize(), color = scheme.surface, contentColor = scheme.onSurface) {
            SystemBarIcons(dark = isDarkSurface(scheme.surface))
            Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = close, enabled = !busy) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close))
                        }
                        Text(
                            stringResource(R.string.vocabulary_edit_card),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp),
                        )
                        TextButton(enabled = dirty && !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    repository.edit(word.id, meaning.text, notes.text)
                                    onDismiss()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    Toast.makeText(context, R.string.vocabulary_error, Toast.LENGTH_SHORT).show()
                                } finally {
                                    busy = false
                                }
                            }
                        }) {
                            Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    HorizontalDivider()
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(word.word, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                        OutlinedTextField(
                            value = meaning,
                            onValueChange = { meaning = it.copy(text = it.text.take(24000)) },
                            enabled = !busy,
                            label = { Text(stringResource(R.string.vocabulary_meaning)) },
                            placeholder = { Text(stringResource(R.string.vocabulary_meaning_hint)) },
                            supportingText = { Text(stringResource(R.string.vocabulary_meaning_help), color = quiet) },
                            minLines = 5,
                            maxLines = 12,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            keyboardOptions = keyboardOptions,
                            modifier = Modifier.fillMaxWidth().focusRequester(meaningFocus).onFocusChanged {
                                if (it.isFocused) focusNotes = false
                            },
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it.copy(text = it.text.take(12000)) },
                            enabled = !busy,
                            label = { Text(stringResource(R.string.vocabulary_personal_note)) },
                            supportingText = { Text(stringResource(R.string.vocabulary_note_help), color = quiet) },
                            minLines = 3,
                            maxLines = 8,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            keyboardOptions = keyboardOptions,
                            modifier = Modifier.fillMaxWidth().focusRequester(notesFocus).onFocusChanged {
                                if (it.isFocused) focusNotes = true
                            },
                        )
                    }
                }
            }
        }
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.annotation_note_discard_title)) },
            text = { Text(stringResource(R.string.vocabulary_discard_changes)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.annotation_note_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.annotation_note_keep_editing)) }
            },
        )
    }
}
