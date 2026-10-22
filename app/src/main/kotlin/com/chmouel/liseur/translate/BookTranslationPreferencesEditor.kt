package com.chmouel.liseur.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.R
import com.chmouel.liseur.data.db.GlossaryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

@Composable
internal fun BookTranslationPreferencesEditor(
    container: AppContainer,
    bookUrl: String,
    target: String,
    position: (suspend () -> Locator?)?,
    onApplied: () -> Unit,
) {
    val feature = container.translate as? ServiceTranslate ?: return
    val scope = rememberCoroutineScope()
    val choice by feature.choice.collectAsState(initial = emptyList())
    val appSettings by container.appSettings.settings.collectAsState(initial = null)
    var open by remember(bookUrl, target) { mutableStateOf(false) }
    var loaded by remember(bookUrl, target) { mutableStateOf(false) }
    var saved by remember(bookUrl, target) { mutableStateOf(BookTranslationPreferences()) }
    var draft by remember(bookUrl, target) { mutableStateOf(BookTranslationPreferences()) }
    var names by remember(bookUrl, target) { mutableStateOf(emptyList<String>()) }
    var result by remember(bookUrl, target) { mutableStateOf<Pair<String, String>?>(null) }
    var resultDraft by remember(bookUrl, target) { mutableStateOf<BookTranslationPreferences?>(null) }
    var resultChoice by remember(bookUrl, target) { mutableStateOf<List<Any?>?>(null) }
    var failure by remember(bookUrl, target) { mutableStateOf<TranslationError?>(null) }
    var supported by remember(bookUrl, target) { mutableStateOf(false) }
    var destination by remember(bookUrl, target) { mutableStateOf("") }
    var busy by remember(bookUrl, target) { mutableStateOf(false) }
    var saving by remember(bookUrl, target) { mutableStateOf(false) }
    var request by remember(bookUrl, target) { mutableStateOf<Job?>(null) }
    // What the choice was last, so the empty list this starts with cannot cancel a translation asked of the real one.
    var seen by remember(bookUrl, target) { mutableStateOf<List<Any?>?>(null) }
    DisposableEffect(bookUrl, target) {
        onDispose { request?.cancel() }
    }
    LaunchedEffect(bookUrl, target, choice) {
        val service = feature.service.first()
        supported = service.supportsPinned
        destination = feature.destination(service).orEmpty()
        if (!loaded) {
            saved = feature.preferences(bookUrl, target)
            draft = saved
            loaded = true
        } else if (seen?.isNotEmpty() == true) {
            // What a translation depends on changed: one on its way was asked of the old.
            request?.cancel()
            busy = false
        }
        seen = choice
    }
    LaunchedEffect(open, appSettings?.codexEnabled) {
        names = emptyList()
        if (open && supported) {
            names = try { container.cachedTranslationNames(bookUrl, position?.invoke()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        }
    }
    if (!supported || !loaded) return
    val edit: (BookTranslationPreferences) -> Unit = {
        draft = it
        request?.cancel()
        busy = false
        failure = null
    }
    val close = {
        request?.cancel()
        busy = false
        draft = saved
        result = null
        resultDraft = null
        failure = null
        open = false
    }
    TextButton(onClick = { if (open) close() else open = true }) { Text(stringResource(R.string.book_translation_preferences)) }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.direction,
            onValueChange = { edit(draft.copy(direction = it)) },
            label = { Text(stringResource(R.string.book_translation_direction)) },
            placeholder = { Text(stringResource(R.string.book_translation_direction_example)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.book_translation_terms))
        draft.terms.forEachIndexed { index, term ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = term.original,
                    onValueChange = { text -> edit(draft.copy(terms = draft.terms.mapIndexed { i, old -> if (i == index) old.copy(original = text) else old })) },
                    label = { Text(stringResource(R.string.book_translation_original)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = term.preferred,
                    onValueChange = { text -> edit(draft.copy(terms = draft.terms.mapIndexed { i, old -> if (i == index) old.copy(preferred = text) else old })) },
                    label = { Text(stringResource(R.string.book_translation_preferred)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { edit(draft.copy(terms = draft.terms.filterIndexed { i, _ -> i != index })) }) {
                    Text(stringResource(R.string.book_preferences_remove))
                }
            }
        }
        TextButton(onClick = { edit(draft.copy(terms = draft.terms + TranslationTerm("", ""))) }) { Text(stringResource(R.string.book_translation_add_term)) }
        if (names.isNotEmpty()) {
            TextButton(onClick = {
                request?.cancel()
                request = scope.launch {
                    val valid = try { container.cachedTranslationNames(bookUrl, position?.invoke()) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList() }
                    currentCoroutineContext().ensureActive()
                    val existing = draft.terms.map { it.original }.toSet()
                    edit(draft.copy(terms = draft.terms + valid.filterNot { it in existing }.map { TranslationTerm(it, it) }))
                }
            }) { Text(stringResource(R.string.book_translation_suggest_names)) }
        }
        if (destination.isNotEmpty()) Text(stringResource(R.string.translation_settings_privacy, destination))
        TextButton(enabled = !busy, onClick = {
            request?.cancel()
            val asked = draft.copy(terms = draft.terms.toList())
            failure = null
            busy = true
            request = scope.launch {
                try {
                    val (source, passage) = container.translationPassage(bookUrl, position?.invoke())
                    if (source != null && TranslationLanguages.same(source, target)) throw TranslationError.Unsupported()
                    val s = container.appSettings.settings.first()
                    val service = feature.service.first()
                    val owner = service.owner(s)
                    val generation = owner?.let { container.serverConnections.generation(it).value }
                    suspend fun guard() {
                        if (container.appSettings.settings.first() != s || owner?.let { container.serverConnections.generation(it).value } != generation) {
                            throw TranslationError.Changed()
                        }
                    }
                    val run = service.openPinned(source, target, s, ::guard, asked)
                    val text = try { run.translate(passage, null) } finally { run.close() }
                    if (container.appSettings.settings.first() != s || owner?.let { container.serverConnections.generation(it).value } != generation) {
                        throw TranslationError.Changed()
                    }
                    currentCoroutineContext().ensureActive()
                    resultChoice = choice
                    result = passage to text
                    resultDraft = asked
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TranslationError) {
                    failure = e
                } catch (_: Exception) {
                    failure = TranslationError.Failed("preview unavailable")
                } finally {
                    busy = false
                }
            }
        }) { Text(stringResource(if (busy) R.string.book_translation_preview_wait else R.string.book_translation_preview)) }
        result?.let { (original, translation) ->
            if (resultDraft != draft || resultChoice != choice) Text(stringResource(R.string.book_translation_preview_outdated))
            Text(original)
            Text(translation)
        }
        failure?.let { Text(errorSentence(it, destination, "", "")) }
        Row {
            TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
            TextButton(enabled = !saving, onClick = {
                val applied = draft.copy(terms = draft.terms.filter { it.original.isNotBlank() && it.preferred.isNotBlank() }.distinctBy { it.original }.sortedBy { it.original })
                request?.cancel()
                saving = true
                scope.launch {
                    try {
                        container.database.withTransaction {
                            container.database.bookAiPreferencesDao().setTranslation(bookUrl, applied.direction)
                            val dao = container.database.glossaryDao()
                            val existing = dao.all(bookUrl).filter { it.target == target && it.enabled }
                            existing.filter { old -> applied.terms.none { it.original == old.term } }.forEach { dao.delete(it.id) }
                            applied.terms.forEach { term ->
                                val entry = existing.firstOrNull { it.term == term.original }
                                    ?: GlossaryEntry(bookUrl = bookUrl, term = term.original, target = target, updatedAt = System.currentTimeMillis())
                                dao.upsert(entry.copy(translation = term.preferred, enabled = true, updatedAt = System.currentTimeMillis()))
                            }
                        }
                        saved = applied
                        draft = applied
                        busy = false
                        open = false
                        result = null
                        onApplied()
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(R.string.read_aloud_voice_sheet_apply)) }
        }
    }
}
