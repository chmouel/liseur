package com.chmouel.liseur.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chmouel.liseur.R
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.util.Locale
import kotlinx.coroutines.launch

private sealed interface SheetLoad {
    data object Loading : SheetLoad
    data object NotSetUp : SheetLoad
    class Loaded(val sheet: VoiceSheet) : SheetLoad
}

/**
 * Picks the language a book is read in and the voice for it. Looking
 * changes nothing; Apply remembers the voice for the language and reads
 * on in it. With [pending], the book waits for the answer before a word
 * is spoken, and dismissing the sheet gives up reading it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadAloudVoiceSheet(
    feature: SpeechReadAloud,
    bookId: String,
    pending: PendingChoice?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<SheetLoad>(SheetLoad.Loading) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        load = SheetLoad.Loading
        load = feature.voiceSheet(bookId)?.let(SheetLoad::Loaded) ?: SheetLoad.NotSetUp
    }
    val dismiss = {
        if (pending != null) feature.cancelChoice(bookId)
        onDismiss()
    }

    LiseurModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.read_aloud_voice_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when (val l = load) {
                SheetLoad.Loading -> Note(stringResource(R.string.read_aloud_voice_sheet_loading))
                SheetLoad.NotSetUp -> {
                    Note(stringResource(R.string.read_aloud_notice_not_set_up), error = true)
                    Buttons(
                        onSettings = { settingsOpen = true },
                        onCancel = dismiss,
                        onApply = null,
                    )
                }
                is SheetLoad.Loaded -> Choice(
                    sheet = l.sheet,
                    onSettings = { settingsOpen = true },
                    onCancel = dismiss,
                    onApply = { language, voice ->
                        scope.launch {
                            // Nothing saved: the service, server or model changed meanwhile.
                            if (feature.applyVoice(l.sheet, language, voice)) onDismiss() else reload++
                        }
                    },
                )
            }
        }
    }

    if (settingsOpen) {
        val close: () -> Unit = {
            settingsOpen = false
            reload++
        }
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) { feature.SettingsScreen(onBack = close) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.Choice(
    sheet: VoiceSheet,
    onSettings: () -> Unit,
    onCancel: () -> Unit,
    onApply: (language: String, voice: String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val catalogue = sheet.catalogue
    var language by remember(sheet) { mutableStateOf(sheet.language) }
    var voice by remember(sheet) {
        mutableStateOf(
            sheet.choice?.takeIf { SpeechLanguage.primary(it.language) == sheet.language }?.voice
                ?: sheet.language?.let { VoiceResolver.voiceFor(it, catalogue, sheet.preferences) },
        )
    }
    var applying by remember(sheet) { mutableStateOf(false) }
    val languages = remember(sheet, locale) { languageChoices(sheet, locale) }

    sheet.reason?.let { reason ->
        Note(
            when (reason) {
                ChoiceReason.Missing -> stringResource(R.string.read_aloud_voice_reason_missing)
                ChoiceReason.Ambiguous -> stringResource(R.string.read_aloud_voice_reason_ambiguous)
                ChoiceReason.NoVoice -> stringResource(
                    R.string.read_aloud_voice_reason_no_voice,
                    (sheet.book as? BookLanguage.Known)?.tag?.let { languageName(it, locale) }.orEmpty(),
                )
                ChoiceReason.CatalogueFailed -> stringResource(R.string.read_aloud_voice_reason_failed)
            },
        )
    }

    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        OutlinedTextField(
            value = language?.let { languageName(it, locale) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.read_aloud_voice_sheet_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(languageName(choice, locale)) },
                    onClick = {
                        open = false
                        if (choice != language) {
                            language = choice
                            voice = VoiceResolver.voiceFor(choice, catalogue, sheet.preferences)
                        }
                    },
                )
            }
        }
    }

    Column(
        Modifier
            .padding(top = 8.dp)
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .selectableGroup(),
    ) {
        val chosen = language
        if (chosen != null) {
            val speaking = catalogue.voices.filter { it.speaks(chosen) }
            val unclassified = catalogue.voices.filter { it.languages == null }
            if (speaking.isEmpty()) {
                Note(stringResource(R.string.read_aloud_voice_sheet_none, languageName(chosen, locale)))
            }
            speaking.forEach { candidate ->
                VoiceRow(sheet, candidate, candidate.accent(chosen), candidate.id == voice, locale) { voice = candidate.id }
            }
            if (unclassified.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.read_aloud_voice_sheet_other),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.read_aloud_voice_sheet_other_detail, languageName(chosen, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                unclassified.forEach { candidate ->
                    VoiceRow(sheet, candidate, null, candidate.id == voice, locale) { voice = candidate.id }
                }
            }
        }
        if (catalogue.failed) Note(stringResource(R.string.read_aloud_voice_sheet_failed), error = true)
    }

    val offerSettings = sheet.pending != null || catalogue.failed || catalogue.voices.isEmpty() ||
        language?.let { l -> catalogue.voices.none { it.speaks(l) } } == true
    val ready = language != null && voice != null && !applying
    Buttons(
        onSettings = onSettings.takeIf { offerSettings },
        onCancel = onCancel,
        onApply = {
            val l = language
            val v = voice
            if (l != null && v != null) {
                applying = true
                onApply(l, v)
            }
        }.takeIf { ready },
    )
}

@Composable
private fun VoiceRow(
    sheet: VoiceSheet,
    voice: CatalogueVoice,
    accent: String?,
    selected: Boolean,
    locale: Locale,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(sheet.service.voiceLabel(voice.id), style = MaterialTheme.typography.bodyLarge)
            if (accent != null) {
                val country = Locale.forLanguageTag(accent).getDisplayCountry(locale)
                Text(
                    text = listOfNotNull(VoiceLabel.flag(accent), country).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The sheet's buttons; a null action leaves its button out, or disabled for [onApply]. */
@Composable
private fun Buttons(onSettings: (() -> Unit)?, onCancel: () -> Unit, onApply: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSettings != null) {
            TextButton(onClick = onSettings) { Text(stringResource(R.string.read_aloud_voice_sheet_settings)) }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { onApply?.invoke() }, enabled = onApply != null) {
                Text(stringResource(R.string.read_aloud_voice_sheet_apply))
            }
        }
    }
}

/** [language]'s name in [locale], capitalized as a list entry. */
private fun languageName(language: String, locale: Locale): String =
    Locale.forLanguageTag(language).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

/**
 * The languages offered: the book's and the session's first, then the rest
 * by name. Voices that do not say what they speak may be given any
 * language, so then every language is offered.
 */
private fun languageChoices(sheet: VoiceSheet, locale: Locale): List<String> {
    val first = buildList {
        when (val book = sheet.book) {
            is BookLanguage.Known -> add(book.tag)
            is BookLanguage.Ambiguous -> addAll(book.tags)
            BookLanguage.Missing -> Unit
        }
        sheet.choice?.let { add(it.language) }
    }.map(SpeechLanguage::primary).distinct()
    val others = sheet.languages.toMutableSet()
    if (sheet.catalogue.voices.any { it.languages == null }) {
        Locale.getISOLanguages().mapNotNullTo(others) { SpeechLanguage.normalize(it) }
    }
    return first + (others - first.toSet()).sortedBy { languageName(it, locale).lowercase(locale) }
}
