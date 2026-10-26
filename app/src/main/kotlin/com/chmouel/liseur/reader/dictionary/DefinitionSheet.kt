package com.chmouel.liseur.reader.dictionary

import android.icu.util.ULocale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.chmouel.liseur.ui.BusyIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
import com.chmouel.liseur.translate.TranslationLanguages
import com.chmouel.liseur.tts.VoiceLabel
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.windowWidth
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Kindle-style definition card: the word, its senses, and a way out to a
 * real dictionary app or to the full entry when the short answer is not
 * enough.
 *
 * Nothing is fetched until [enabled] is true. Until then the card explains
 * which site would be asked and offers to turn the lookup on, so the first
 * word anyone defines is also the moment they decide whether the app may
 * talk to a dictionary at all.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DefinitionSheet(
    word: String,
    languages: List<String>,
    enabled: Boolean,
    baseUrl: String,
    altLanguage: String?,
    onEnable: () -> Unit,
    onOpenInDictionaryApp: (String) -> Unit,
    onOpenInBrowser: (term: String, site: String) -> Unit,
    onDismiss: () -> Unit,
    onSelectText: (() -> Unit)? = null,
    client: WiktionaryClient = remember { WiktionaryClient() },
) {
    val term = remember(word) { normaliseLookupTerm(word) }
    var state by remember(term, languages, enabled, baseUrl) {
        mutableStateOf<DictionaryState>(
            if (enabled) DictionaryState.Loading else DictionaryState.Disabled,
        )
    }

    LaunchedEffect(term, languages, enabled, baseUrl) {
        if (!enabled) return@LaunchedEffect
        state = client.define(term, languages, baseUrl) { initial ->
            withContext(Dispatchers.Main.immediate) { state = initial }
        }
    }

    val found = state as? DictionaryState.Found
    val entry = found?.term?.ifBlank { null } ?: term
    val source = WiktionaryEditions.codeOf(baseUrl) ?: "en"
    val target = remember(found, source, altLanguage) {
        found?.let { translationTarget(it.senses, source, altLanguage) }
    }
    // Fetched with the definition, so the button can name the word itself.
    // Keyed so new inputs start from Loading, never pairing a flag with another language's word.
    val translations = key(entry, baseUrl, target) {
        produceState<TranslationsState>(TranslationsState.Loading) {
            value = if (target == null) TranslationsState.Empty else client.translations(entry, baseUrl, target)
        }.value
    }
    var showTranslations by remember(entry, baseUrl, target) { mutableStateOf(false) }
    val site = remember(baseUrl, target) { target?.let { translationSite(baseUrl, it) } }
    // The word's entry on the target's own Wiktionary, written in that
    // language: asked for only once the reader opens the translations, then
    // kept while this card is open.
    val targetClient = remember { WiktionaryClient() }
    var targetEntry by remember(entry, site, source) { mutableStateOf<DictionaryState?>(null) }
    LaunchedEffect(showTranslations, entry, site, source) {
        if (!showTranslations || site == null) return@LaunchedEffect
        if (targetEntry != null && targetEntry != DictionaryState.Loading) return@LaunchedEffect
        targetEntry = DictionaryState.Loading
        targetEntry = targetClient.define(entry, listOf(source), site, strict = true) { initial ->
            withContext(Dispatchers.Main.immediate) { targetEntry = initial }
        }
    }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = term,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            when (val current = state) {
                DictionaryState.Disabled -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.dictionary_disabled_explanation,
                            DictionaryUrl.hostOf(baseUrl),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onEnable) {
                        Text(stringResource(R.string.dictionary_enable))
                    }
                }

                else -> LookupResult(current, term) { onOpenInBrowser(it, baseUrl) }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSelectText != null) {
                    TextButton(onClick = onSelectText) {
                        Text(stringResource(R.string.dictionary_select_text))
                    }
                }
                val translated = (translations as? TranslationsState.Found)?.first
                if (target != null && translated != null) {
                    TranslationsPill(target, translated, onClick = { showTranslations = true })
                }
                TextButton(onClick = { onOpenInDictionaryApp(term) }) {
                    Text(stringResource(R.string.dictionary_other_app))
                }
                TextButton(onClick = { onOpenInBrowser(term, baseUrl) }) {
                    Text(stringResource(R.string.dictionary_open_full_entry))
                }
            }
        }
    }

    val sections = (translations as? TranslationsState.Found)?.sections
    if (showTranslations && target != null && sections != null) {
        TranslationsSheet(
            term = entry,
            baseUrl = baseUrl,
            sections = sections,
            target = target,
            site = site,
            targetEntry = targetEntry,
            onOpenInBrowser = onOpenInBrowser,
            onDismiss = { showTranslations = false },
        )
    }
}

/** What a lookup came to, once there is something to say: loading, the senses, or why not. */
@Composable
private fun LookupResult(state: DictionaryState, term: String, onOpenInBrowser: (String) -> Unit) {
    when (state) {
        DictionaryState.Disabled -> Unit

        DictionaryState.Loading -> Loading()

        is DictionaryState.Found -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.senses.forEach { Sense(it, onOpenInBrowser) }
        }

        DictionaryState.NotFound -> Text(
            text = stringResource(R.string.dictionary_not_found, term),
            style = MaterialTheme.typography.bodyMedium,
        )

        is DictionaryState.Failed -> Failure(state.message, state.host)
    }
}

@Composable
private fun Loading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BusyIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.dictionary_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Failure(message: String?, host: String?) {
    Text(
        text = when {
            host != null && message != null -> stringResource(R.string.dictionary_failed_host, host, message)
            message != null -> stringResource(R.string.dictionary_failed_reason, message)
            else -> stringResource(R.string.dictionary_failed)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Sense(sense: DictionarySense, onOpenInBrowser: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sense.partOfSpeech.isNotBlank()) {
            Text(
                text = sense.partOfSpeech,
                style = MaterialTheme.typography.labelLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        sense.definitions.take(MAX_CARD_DEFINITIONS).forEachIndexed { index, definition ->
            Text(
                text = "${index + 1}. $definition",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        sense.resolved.values.distinct().forEach { resolved ->
            TextButton(onClick = { onOpenInBrowser(resolved.term) }) {
                Text(stringResource(R.string.dictionary_base_entry, resolved.term))
            }
            resolved.senses.forEach { Sense(it, onOpenInBrowser) }
            resolved.problem?.let { problem ->
                Text(
                    text = stringResource(
                        when (problem) {
                            DefinitionProblem.MISSING -> R.string.dictionary_base_missing
                            DefinitionProblem.FAILED -> R.string.dictionary_base_failed
                            DefinitionProblem.LIMIT -> R.string.dictionary_base_limit
                            DefinitionProblem.UNRESOLVED -> R.string.dictionary_base_unresolved
                        },
                        resolved.term,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** [target]'s flag, where the language has a country it is most likely written in. */
private fun flagOf(target: String): String? =
    VoiceLabel.flag(ULocale.addLikelySubtags(ULocale.forLanguageTag(target)).toLanguageTag())

/**
 * The way to [target]'s translations, showing the first of them,
 * [translated], behind the language's flag: "🇫🇷 eau".
 */
@Composable
private fun TranslationsPill(target: String, translated: String, onClick: () -> Unit) {
    val ui = LocalConfiguration.current.locales[0]
    val flag = remember(target) { flagOf(target) }
    val description = stringResource(
        R.string.dictionary_translations_button_description,
        Locale.forLanguageTag(target).getDisplayName(ui).ifBlank { target },
        translated,
    )
    AssistChip(
        onClick = onClick,
        label = { Text(listOfNotNull(flag, translated).joinToString(" ")) },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick { onClick(); true }
        },
    )
}

/**
 * [term] in [target], over the definitions. Its scrim is clear, as the
 * card underneath already dims the page; dismissing it goes back to that
 * card.
 *
 * With a [site] in that language, this is [targetEntry], the word's own
 * entry there, written in that language ("wing" on fr.wiktionary: «Aile
 * d'un avion…»). Without one, or when that site has no such entry, it is
 * the translation table of [sections], whose words each open their own
 * entry on [site] in a sheet of their own: Back closes it and returns to
 * the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationsSheet(
    term: String,
    baseUrl: String,
    sections: List<TranslationSection>,
    target: String,
    site: String?,
    targetEntry: DictionaryState?,
    onOpenInBrowser: (term: String, site: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalConfiguration.current.locales[0]
    // Mid-sentence, so the locale's own casing: "En anglais", "auf Englisch".
    val language = Locale.forLanguageTag(target).getDisplayName(ui).ifBlank { target }
    var opened by remember(term, target) { mutableStateOf<String?>(null) }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Not asked yet only for the frame before the request starts.
            val entryState = targetEntry ?: DictionaryState.Loading
            if (site != null && (entryState == DictionaryState.Loading || entryState is DictionaryState.Found)) {
                EntryHeader(term, languageLabel(target))
                LookupResult(entryState, term) { onOpenInBrowser(it, site) }
                TextButton(onClick = { onOpenInBrowser(term, site) }) {
                    Text(stringResource(R.string.dictionary_open_full_entry))
                }
                return@Column
            }

            EntryHeader(term, stringResource(R.string.dictionary_translations_in, language))
            (targetEntry as? DictionaryState.Failed)?.let { Failure(it.message, it.host) }
            val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
            sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = section.partOfSpeech,
                        style = MaterialTheme.typography.labelLarge,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    section.groups.forEach { group ->
                        Text(
                            text = buildAnnotatedString {
                                if (group.gloss.isNotBlank()) {
                                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(group.gloss) }
                                    append(": ")
                                }
                                group.terms.forEachIndexed { index, translated ->
                                    if (index > 0) append(", ")
                                    if (site == null) {
                                        append(translated)
                                    } else {
                                        withLink(LinkAnnotation.Clickable(translated, link) { opened = translated }) {
                                            append(translated)
                                        }
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            TextButton(onClick = { onOpenInBrowser(term, baseUrl) }) {
                Text(stringResource(R.string.dictionary_open_full_entry))
            }
        }
    }

    val word = opened
    if (word != null && site != null) {
        TranslatedEntry(word, target, site, onOpenInBrowser, onDismiss = { opened = null })
    }
}

/**
 * [word]'s own entry on [site], the Wiktionary of [target]: "eau" defined
 * in French, for a reader who asked for French translations of "water".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslatedEntry(
    word: String,
    target: String,
    site: String,
    onOpenInBrowser: (term: String, site: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val client = remember { WiktionaryClient() }
    val state by produceState<DictionaryState>(DictionaryState.Loading, word, site) {
        value = client.define(word, listOf(Locale.forLanguageTag(target).language), site, strict = true) { initial ->
            withContext(Dispatchers.Main.immediate) { value = initial }
        }
    }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryHeader(word, languageLabel(target))
            LookupResult(state, word) { onOpenInBrowser(it, site) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.back))
                }
                TextButton(onClick = { onOpenInBrowser(word, site) }) {
                    Text(stringResource(R.string.dictionary_open_full_entry))
                }
            }
        }
    }
}

/** A sheet's word and, under it, what it is about: "In French", "🇫🇷 French". */
@Composable
private fun EntryHeader(word: String, subtitle: String) {
    Column {
        Text(
            text = word,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [target]'s flag and name in the reader's language: "🇫🇷 French". */
@Composable
private fun languageLabel(target: String): String {
    val ui = LocalConfiguration.current.locales[0]
    val flag = remember(target) { flagOf(target) }
    return listOfNotNull(flag, TranslationLanguages.name(target, ui)).joinToString(" ")
}
