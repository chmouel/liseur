package com.chmouel.liseur.reader.dictionary

import android.icu.util.ULocale
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.chmouel.liseur.ui.BusyIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.R
import com.chmouel.liseur.domain.DictionaryUrl
import com.chmouel.liseur.domain.WiktionaryEditions
import com.chmouel.liseur.translate.TranslationLanguages
import com.chmouel.liseur.tts.VoiceLabel
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import com.chmouel.liseur.ui.contentWidthCap
import com.chmouel.liseur.ui.settings.OfflineDictionaryPickerSheet
import com.chmouel.liseur.ui.settings.dictionaryLanguageTitle
import com.chmouel.liseur.ui.windowWidth
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun shouldUseOnlineLookup(
    offlineSelected: Boolean,
    enabled: Boolean,
    localResult: Result<OfflineDictionary.Lookup?>?,
): Boolean = !offlineSelected || (
    enabled && localResult != null &&
        (localResult.isFailure || localResult.getOrNull()?.entries.isNullOrEmpty())
    )

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
    fallbackWords: List<String>,
    languages: List<String>,
    bookLanguage: String? = null,
    enabled: Boolean,
    baseUrl: String,
    altLanguage: String?,
    onEnable: () -> Unit,
    onOpenInDictionaryApp: (String) -> Unit,
    onOpenInBrowser: (term: String, site: String) -> Unit,
    onDismiss: () -> Unit,
    onLookupSuggestion: (String, String?) -> Unit,
    onSelectText: (() -> Unit)? = null,
    selectionActions: (@Composable () -> Unit)? = null,
    selectedDefinitionActions: (@Composable (String, String?) -> Unit)? = null,
    client: WiktionaryClient = remember { WiktionaryClient() },
) {
    val context = LocalContext.current
    val offlineState by OfflineDictionary.state(context).collectAsState()
    val offline = remember(offlineState.packs, languages, bookLanguage) {
        OfflineDictionary.matchFor(context, languages, bookLanguage)
    }
    val term = word.trim()
    // Keyed, not just re-run: produceState keeps its last value when its keys change, and a
    // previous word's miss must never let this word go online before its own lookup finishes.
    val localResult = key(offlineState.packs, offline?.id, term, fallbackWords) {
        produceState<Result<OfflineDictionary.Lookup?>?>(null) {
            val pack = offline ?: return@produceState
            value = try {
                Result.success(OfflineDictionary.lookup(context, pack.id, term, fallbackWords))
            } catch (error: java.io.IOException) {
                Result.failure(error)
            }
        }.value
    }
    val suggestions = key(offlineState.packs, offline?.id, term, fallbackWords) {
        produceState(emptyList<String>(), localResult) {
            val pack = offline ?: return@produceState
            if (localResult?.isSuccess != true || localResult.getOrNull() != null) return@produceState
            value = try {
                OfflineDictionary.suggestions(context, pack.id, term)
            } catch (_: java.io.IOException) {
                emptyList()
            }
        }.value
    }
    var choosingOffline by rememberSaveable { mutableStateOf(false) }
    val offlineLookup = localResult?.getOrNull()
    val offlineEntries = offlineLookup?.entries
    val definitionPack = offlineLookup?.pack ?: offline
    // When an offline dictionary is active, only reach over the network if the
    // reader has online lookup enabled AND the offline pack had no entry for this word.
    val useOnline = shouldUseOnlineLookup(offline != null, enabled, localResult)

    var state by remember(term, fallbackWords, languages, enabled, baseUrl, useOnline) {
        mutableStateOf<DictionaryState>(
            if (enabled && useOnline) DictionaryState.Loading else DictionaryState.Disabled,
        )
    }

    LaunchedEffect(term, fallbackWords, languages, enabled, baseUrl, useOnline) {
        if (!enabled || !useOnline) return@LaunchedEffect
        state = client.define(term, languages, baseUrl, fallbackWords = fallbackWords) { initial ->
            withContext(Dispatchers.Main.immediate) { state = initial }
        }
    }

    val offlineResolved = definitionPack != null && offlineEntries?.isNotEmpty() == true
    val found = (state as? DictionaryState.Found)?.takeIf { !offlineResolved }
    val entry = found?.term?.ifBlank { null }
        ?: offlineEntries?.firstOrNull()?.headword?.ifBlank { null }
        ?: term
    val source = if (offlineResolved && definitionPack.language.isNotBlank()) {
        OfflineDictionary.primaryLanguage(definitionPack.language)
    } else {
        WiktionaryEditions.codeOf(baseUrl) ?: "en"
    }
    val target = remember(found, definitionPack, offlineResolved, source, baseUrl, altLanguage, enabled) {
        when {
            found != null -> translationTarget(found.senses, source, altLanguage)
            // Wiktionary's translation table is in the site's own language, so it only
            // describes the offline entry when the pack is in that language too.
            offlineResolved && enabled && OfflineDictionary.samePrimary(
                definitionPack.language,
                WiktionaryEditions.codeOf(baseUrl) ?: "en",
            ) -> translationTarget(
                listOf(DictionarySense("", emptyList(), language = source)),
                source,
                altLanguage,
            )
            else -> null
        }
    }
    var showTranslations by remember(entry, baseUrl, target) { mutableStateOf(false) }
    var translationAttempt by remember(entry, baseUrl, target) { mutableIntStateOf(0) }
    // Fetched with an online definition so the pill can name the word itself. An offline hit
    // sends nothing until the reader asks for the translations.
    val fetchTranslations = enabled && target != null && (!offlineResolved || showTranslations)
    val translations = key(entry, baseUrl, target, fetchTranslations, translationAttempt) {
        produceState<TranslationsState>(TranslationsState.Loading) {
            value = if (!fetchTranslations) {
                TranslationsState.Empty
            } else {
                client.translations(entry, baseUrl, target, fresh = translationAttempt > 0)
            }
        }.value
    }
    val site = remember(baseUrl, target) { target?.let { translationSite(baseUrl, it) } }
    // The word's entry on the target's own Wiktionary, written in that
    // language: asked for only once the reader opens the translations, then
    // kept while this card is open.
    val targetClient = remember { WiktionaryClient() }
    var targetEntry by remember(entry, site, source) { mutableStateOf<DictionaryState?>(null) }
    LaunchedEffect(showTranslations, entry, site, source, translationAttempt) {
        if (!showTranslations || site == null) return@LaunchedEffect
        if (targetEntry is DictionaryState.Found) return@LaunchedEffect
        targetEntry = DictionaryState.Loading
        targetEntry = targetClient.define(entry, listOf(source), site, strict = true) { initial ->
            withContext(Dispatchers.Main.immediate) { targetEntry = initial }
        }
    }

    LiseurModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val entryScroll = key(term, source) { rememberScrollState() }
        var selection by remember(term, source) { mutableStateOf<Pair<Any, String>?>(null) }
        var focusedOwner by remember(term, source) { mutableStateOf<Any?>(null) }
        var actionsFocused by remember(term, source) { mutableStateOf(false) }
        val actionsFocusRequester = remember { FocusRequester() }
        val selectedOwner = selection?.first
        LaunchedEffect(selectedOwner, focusedOwner, actionsFocused) {
            if (selectedOwner != null && selectedOwner !== focusedOwner && !actionsFocused) {
                selection = null
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = contentWidthCap(windowWidth()))
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val onSelection: (Any, String?) -> Unit = { owner, text ->
                if (text != null) selection = owner to text
                else if (selection?.first === owner) selection = null
            }
            // Only the entry scrolls, so a long one never pushes the actions off screen.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(entryScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val firstPronunciation = offlineEntries?.firstOrNull()
                    ?.takeIf { it.headword == term }
                    ?.pronunciation
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = term,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (onSelectText != null) {
                            AssistChip(
                                onClick = onSelectText,
                                label = { Text(stringResource(R.string.dictionary_select_text)) },
                            )
                        }
                    }
                    if (firstPronunciation != null) {
                        Text(
                            text = firstPronunciation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CompositionLocalProvider(
                    LocalDefinitionSelection provides if (selectedDefinitionActions != null) onSelection else null,
                    LocalDefinitionActionFocus provides DefinitionActionFocus(
                        requester = actionsFocusRequester,
                        ownsSelection = { selection?.first === it },
                        onFocus = { owner, focused ->
                            if (focused) focusedOwner = owner
                            else if (focusedOwner === owner) focusedOwner = null
                        },
                    ),
                ) {
                    if (offline != null) {
                        val result = localResult
                        when {
                            result == null -> Loading()
                            result.isFailure -> {
                                if (enabled) {
                                    LookupResult(state, term) { onOpenInBrowser(it, baseUrl) }
                                } else {
                                    Text(
                                        text = stringResource(R.string.offline_dictionary_error),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            offlineEntries.isNullOrEmpty() -> {
                                if (enabled) {
                                    LookupResult(state, term) { onOpenInBrowser(it, baseUrl) }
                                } else {
                                    LookupResult(DictionaryState.NotFound, term) {}
                                }
                                if (suggestions.isNotEmpty() && state !is DictionaryState.Found) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        suggestions.forEach { suggestion ->
                                            AssistChip(
                                                onClick = {
                                                    onLookupSuggestion(suggestion, offline.language.takeIf(String::isNotBlank))
                                                },
                                                label = { Text(suggestion) },
                                            )
                                        }
                                    }
                                }
                            }
                            else -> {
                                OfflineEntriesContent(
                                    queriedTerm = term,
                                    entries = offlineEntries,
                                    pack = definitionPack ?: offline,
                                )
                            }
                        }
                    } else when (val current = state) {
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
                            OfflineSetupRow(
                                operation = offlineState.operation,
                                onChoose = { choosingOffline = true },
                            )
                        }

                        else -> LookupResult(current, term) { onOpenInBrowser(it, baseUrl) }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val translated = (translations as? TranslationsState.Found)?.first
                    if (target != null && translated != null) {
                        TranslationsPill(target, translated, onClick = { showTranslations = true })
                    } else if (offlineResolved && target != null) {
                        DictionaryLink(
                            label = stringResource(
                                R.string.dictionary_translations_in,
                                TranslationLanguages.name(target, LocalConfiguration.current.locales[0]),
                            ),
                            onClick = {
                                // A failed or empty answer is asked for again; a found one is just shown.
                                if (showTranslations && translations != TranslationsState.Loading) translationAttempt++
                                showTranslations = true
                            },
                        )
                        val waitingForEntry = site != null && (targetEntry == null || targetEntry == DictionaryState.Loading)
                        if (showTranslations && (translations == TranslationsState.Loading || waitingForEntry)) {
                            Loading()
                        } else if (showTranslations && (translations is TranslationsState.Failed || targetEntry is DictionaryState.Failed)) {
                            Text(
                                text = stringResource(R.string.dictionary_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (showTranslations && translations is TranslationsState.Empty) {
                            Text(
                                text = stringResource(
                                    R.string.dictionary_translations_empty,
                                    entry,
                                    TranslationLanguages.name(target, LocalConfiguration.current.locales[0]),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    DictionaryLink(
                        label = stringResource(R.string.dictionary_other_app),
                        onClick = { onOpenInDictionaryApp(term) },
                    )
                    if (enabled || offline == null) {
                        DictionaryLink(
                            label = stringResource(R.string.dictionary_open_full_entry),
                            onClick = { onOpenInBrowser(entry, baseUrl) },
                        )
                    }
                }
            }

            val selectedText = selection?.second
            if (selectedText != null && selectedDefinitionActions != null) {
                val selectionLanguage = if (offlineResolved) {
                    definitionPack.language.takeIf(String::isNotBlank)
                } else {
                    source
                }
                Box(
                    Modifier
                        .focusRequester(actionsFocusRequester)
                        .onFocusChanged { actionsFocused = it.hasFocus }
                        .focusGroup(),
                ) {
                    selectedDefinitionActions(selectedText, selectionLanguage)
                }
            } else {
                selectionActions?.invoke()
            }
        }
    }

    if (choosingOffline) {
        OfflineDictionaryPickerSheet(
            pinnedLanguage = languages.firstOrNull(),
            onDismiss = { choosingOffline = false },
        )
    }

    val sections = (translations as? TranslationsState.Found)?.sections
    // The word's own entry on the target site is enough to open, even when the table is empty or failed.
    if (showTranslations && target != null && (sections != null || targetEntry is DictionaryState.Found)) {
        TranslationsSheet(
            term = entry,
            baseUrl = baseUrl,
            sections = sections.orEmpty(),
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

/** The way to an offline dictionary from the card, and how its download is going. */
@Composable
private fun OfflineSetupRow(operation: OfflineDictionary.Operation, onChoose: () -> Unit) {
    val context = LocalContext.current
    val ui = LocalConfiguration.current.locales[0]
    when (operation) {
        is OfflineDictionary.Operation.Downloading -> Text(
            text = stringResource(
                R.string.offline_dictionary_downloading,
                dictionaryLanguageTitle(operation.language, ui),
                android.text.format.Formatter.formatShortFileSize(context, operation.downloadedBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        is OfflineDictionary.Operation.Unpacking -> Text(
            text = stringResource(R.string.offline_dictionary_working),
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> {
            if (operation is OfflineDictionary.Operation.Failed) {
                Text(
                    text = stringResource(R.string.offline_dictionary_download_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onChoose) {
                Text(stringResource(R.string.dictionary_download_offline))
            }
        }
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
            DefinitionText(
                text = AnnotatedString(definition),
                displayPrefix = "${index + 1}. ",
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

@Composable
private fun DictionaryLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    )
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

@Composable
private fun OfflineEntriesContent(
    queriedTerm: String,
    entries: List<StarDictEntry>,
    pack: OfflineDictionary.Pack,
) {
    val ui = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEachIndexed { entryIndex, entry ->
            if (entryIndex > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Exact, so "A" (the letter) and "a" are told apart.
                val showBaseHeadword = entry.headword != queriedTerm
                if (showBaseHeadword) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = entry.headword,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.alignByBaseline(),
                        )
                        if (entry.pronunciation != null) {
                            Text(
                                text = entry.pronunciation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                }

                entry.sections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (section.heading.isNotBlank()) {
                            Text(
                                text = section.heading,
                                style = MaterialTheme.typography.labelLarge,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        var number = 0
                        section.items.forEach { itemHtml ->
                            val isSubItem = itemHtml.startsWith("— ")
                            val prefix = if (isSubItem) {
                                "    "
                            } else {
                                number++
                                "$number. "
                            }
                            val rendered = AnnotatedString.fromHtml(itemHtml)
                            DefinitionText(
                                text = rendered,
                                displayPrefix = prefix,
                                style = if (isSubItem) {
                                    MaterialTheme.typography.bodySmall
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                color = if (isSubItem) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    Color.Unspecified
                                },
                            )
                        }
                    }
                }

                entry.notes.take(2).forEach { noteHtml ->
                    DefinitionText(
                        text = AnnotatedString.fromHtml(noteHtml),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (entry.sections.isEmpty() && entry.notes.isEmpty() && entry.fallbackHtml != null) {
                    DefinitionText(
                        text = AnnotatedString.fromHtml(entry.fallbackHtml),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        val provenance = if (pack.isReaderDict) {
            val langLabel = pack.language.takeIf(String::isNotBlank)?.let { dictionaryLanguageTitle(it, ui) }
            listOfNotNull(
                langLabel,
                stringResource(R.string.offline_dictionary_attribution),
            ).joinToString(" · ")
        } else {
            pack.name
        }
        if (provenance.isNotBlank()) {
            Text(
                text = provenance,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val LocalDefinitionSelection = staticCompositionLocalOf<((Any, String?) -> Unit)?> { null }

private class DefinitionActionFocus(
    val requester: FocusRequester,
    val ownsSelection: (Any) -> Boolean,
    val onFocus: (Any, Boolean) -> Unit,
)

private val LocalDefinitionActionFocus = compositionLocalOf<DefinitionActionFocus?> { null }

private object DefinitionTextToolbar : TextToolbar {
    override val status = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DefinitionText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color = Color.Unspecified,
    displayPrefix: String = "",
) {
    val displayedText = remember(text, displayPrefix) {
        if (displayPrefix.isEmpty()) text else buildAnnotatedString {
            append(displayPrefix)
            append(text)
        }
    }
    val onSelection = LocalDefinitionSelection.current
    if (onSelection == null) {
        Text(text = displayedText, style = style, color = color)
        return
    }

    val owner = remember(displayedText) { Any() }
    val reportSelection by rememberUpdatedState(onSelection)
    val actionFocus = checkNotNull(LocalDefinitionActionFocus.current)
    val reportFocus by rememberUpdatedState(actionFocus.onFocus)
    val links = remember(displayedText) { displayedText.getLinkAnnotations(0, displayedText.length) }
    val linkColor = MaterialTheme.colorScheme.primary
    val styledText = remember(displayedText, linkColor) {
        if (links.isEmpty()) displayedText else buildAnnotatedString {
            append(displayedText)
            links.forEach { link ->
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        .merge(link.item.styles?.style),
                    link.start,
                    link.end,
                )
            }
        }
    }
    var value by remember(displayedText) { mutableStateOf(TextFieldValue(displayedText)) }
    var focused by remember(displayedText) { mutableStateOf(false) }
    DisposableEffect(owner) {
        onDispose {
            reportSelection(owner, null)
            reportFocus(owner, false)
        }
    }
    val contentColor = LocalContentColor.current
    val textColor = color.takeOrElse { contentColor }
    val uriHandler = LocalUriHandler.current
    fun openLink(link: LinkAnnotation) {
        val listener = link.linkInteractionListener
        if (listener != null) {
            listener.onClick(link)
        } else if (link is LinkAnnotation.Url) {
            runCatching { uriHandler.openUri(link.url) }
        }
    }
    var layout by remember(displayedText) { mutableStateOf<TextLayoutResult?>(null) }
    val linkTaps = if (links.isEmpty()) Modifier else Modifier.pointerInput(links, uriHandler) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial)
            } ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                return@awaitEachGesture
            }
            val result = layout ?: return@awaitEachGesture
            val line = result.getLineForVerticalPosition(up.position.y)
            val offset = result.getOffsetForPosition(up.position)
            val link = links.firstOrNull { range ->
                val start = maxOf(range.start, result.getLineStart(line))
                val end = minOf(range.end, result.getLineEnd(line, visibleEnd = true))
                (offset - 1..offset).any { index ->
                    index in start until end && result.getBoundingBox(index).contains(up.position)
                }
            }?.item ?: return@awaitEachGesture
            openLink(link)
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides DefinitionTextToolbar) {
        BasicTextField(
            value = value.copy(annotatedString = styledText),
            onValueChange = { next ->
                value = next
                val range = next.selection
                if (focused) {
                    val start = maxOf(displayPrefix.length, range.min)
                    val selected = if (start < range.max) next.text.substring(start, range.max).trim() else ""
                    reportSelection(owner, selected.takeIf(String::isNotEmpty))
                }
            },
            readOnly = true,
            textStyle = style.copy(color = textColor),
            onTextLayout = { if (links.isNotEmpty()) layout = it },
            decorationBox = { innerTextField ->
                Box {
                    innerTextField()
                    val result = layout
                    if (result != null) {
                        links.forEach { link ->
                            if (link.start < link.end) key(link) {
                                val firstLine = result.getLineForOffset(link.start)
                                val lastLine = result.getLineForOffset(link.end - 1)
                                val label = displayedText.text.substring(link.start, link.end)
                                (firstLine..lastLine).mapNotNull { line ->
                                    val start = maxOf(link.start, result.getLineStart(line))
                                    val end = minOf(link.end, result.getLineEnd(line, visibleEnd = true))
                                    if (start >= end) null
                                    else result.getPathForRange(start, end).getBounds().takeUnless { it.isEmpty }
                                }.forEachIndexed { index, bounds ->
                                    DefinitionLinkTarget(
                                        label = label,
                                        bounds = bounds,
                                        keyboardFocusable = index == 0,
                                        onOpen = { openLink(link.item) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(linkTaps)
                .filterTextContextMenuComponents { false }
                .focusProperties {
                    if (actionFocus.ownsSelection(owner)) {
                        next = actionFocus.requester
                        down = actionFocus.requester
                    }
                }
                .onFocusChanged {
                    focused = it.isFocused
                    reportFocus(owner, focused)
                },
        )
    }
}

@Composable
private fun DefinitionLinkTarget(
    label: String,
    bounds: Rect,
    keyboardFocusable: Boolean,
    onOpen: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    with(LocalDensity.current) {
        Box(
            Modifier
                .absoluteOffset(bounds.left.toDp(), bounds.top.toDp())
                .size(bounds.width.toDp(), bounds.height.toDp())
                .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        onOpen()
                        true
                    }
                }
                .onKeyEvent { event ->
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter -> {
                            if (event.type == KeyEventType.KeyUp) onOpen()
                            true
                        }
                        else -> false
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .focusable(enabled = keyboardFocusable),
        )
    }
}
